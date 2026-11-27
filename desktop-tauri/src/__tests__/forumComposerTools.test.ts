import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ComposerModal } from "../components/ComposerModal";
import { fetchForumTemplates, fetchPostRawMarkdown, fetchTopicDetail, recordForumTemplateUse, publishTopic, publishReply } from "../lib/api";
import { expandForumTemplate, parseForumTemplates } from "../lib/composerTemplates";
import { chartMarkup, dateMarkup, forumMarkup, forumTools, wrapMarkup } from "../lib/forumComposerTools";
import { renderDiscourseMarkdownPreview } from "../lib/markdownPreview";
import type { ComposerLaunchRequest, TopicDetailModel } from "../lib/types";

vi.mock("../lib/api", async original => ({
  ...await original<typeof import("../lib/api")>(),
  fetchForumTemplates: vi.fn(), recordForumTemplateUse: vi.fn(), fetchPostRawMarkdown: vi.fn(),
  fetchTopicDetail: vi.fn(), getCachedTopicDetail: vi.fn(() => null),
  fetchSingleCloudDraft: vi.fn(async () => ({ draft: null, sequence: 0 })),
  fetchSiteCustomEmojis: vi.fn(async () => []), fetchCategoryAllowedTags: vi.fn(async () => ({ options: [] })),
  publishTopic: vi.fn(), publishReply: vi.fn(),
}));
let host: HTMLDivElement, root: Root;
const template = { id: 17, title: "周报 %{my_username}", content: "你好 %{my_name}", tags: ["weekly"], usages: 2 };
beforeEach(() => {
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  vi.clearAllMocks();
  vi.mocked(fetchForumTemplates).mockResolvedValue([template]);
  vi.mocked(recordForumTemplateUse).mockResolvedValue(undefined);
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => { await act(async () => { root.unmount(); }); host.remove(); });

async function render(request: ComposerLaunchRequest, allowed = true) {
  await act(async () => { root.render(React.createElement(ComposerModal, {
    request, categories: [], currentUser: { userId: 7, username: "tester", displayName: "林", avatarTemplate: "", trustLevel: 2, canUseTemplates: allowed },
    onClose: vi.fn(), onTopicEdited: vi.fn(), onPublished: vi.fn(), onTriggerVerify: vi.fn(),
  })); });
}
function button(label: string) {
  const found = [...host.querySelectorAll("button")].find(b => b.textContent?.trim() === label || b.getAttribute("aria-label") === label);
  expect(found, label).toBeTruthy();
  return found!;
}
async function click(label: string) { await act(async () => { button(label).click(); }); }

it.each(["", "保留标题"])("inserts a server template at the caret in the real composer (title %s)", async initialTitle => {
  await render({ action: "NEW_TOPIC", initialRaw: "前文末尾", initialTitle });
  const body = host.querySelector("textarea")!;
  await act(async () => { body.setSelectionRange(2, 2); });
  await click("更多创作工具");
  expect(Object.values(forumTools).every(label => button(label))).toBe(true);
  expect(button("引用整个帖子").disabled).toBe(true);
  expect(fetchForumTemplates).not.toHaveBeenCalled();
  await click("插入模板");
  const item = [...host.querySelectorAll("button")].find(b => b.textContent?.includes(template.title))!;
  await act(async () => { item.click(); });
  expect(body.value).toBe("前文\n\n你好 林\n\n末尾");
  expect(host.querySelector<HTMLInputElement>('[placeholder="话题标题"]')?.value).toBe(initialTitle || "周报 tester");
  expect(recordForumTemplateUse).toHaveBeenCalledExactlyOnceWith(17);
  expect(publishTopic).not.toHaveBeenCalled();
});

it("preserves draft and selection if a template request fails, and can retry an empty account list", async () => {
  vi.mocked(fetchForumTemplates).mockRejectedValueOnce(new Error("连接已断开")).mockResolvedValueOnce([]);
  await render({ action: "NEW_TOPIC", initialRaw: "写到这里" });
  await click("更多创作工具"); await click("插入模板");
  expect(host.textContent).toContain("连接已断开");
  await click("重试");
  expect(host.textContent).toContain("当前账号没有可用模板");
  expect(host.querySelector("textarea")?.value).toBe("写到这里");
  expect(recordForumTemplateUse).not.toHaveBeenCalled();
});

it("builds a chart from editable zero and decimal rows, and wraps an existing selection as a footnote", async () => {
  await render({ action: "NEW_TOPIC", initialRaw: "甲乙丙" });
  const body = host.querySelector("textarea")!;
  await act(async () => { body.setSelectionRange(1, 2); });
  await click("更多创作工具"); await click("添加脚注");
  expect(body.value).toBe("甲^[乙]丙");
  await act(async () => { body.setSelectionRange(body.value.length, body.value.length); });
  await click("更多创作工具"); await click("构建图表");
  async function fill(label: string, value: string) {
    const input = host.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, value);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
  }
  expect(button("插入").disabled).toBe(true);
  await fill("第 1 行名称", "手机"); await fill("第 1 行数值", "0");
  await click("＋ 添加一行");
  await fill("第 2 行名称", "桌面"); await fill("第 2 行数值", "2.5");
  await click("插入");
  expect(body.value).toContain("[chart type='horizontalBar']\n手机 | 0\n桌面 | 2.5\n[/chart]");
  expect(publishTopic).not.toHaveBeenCalled();
});

it("opens the template and date tools with Discourse keyboard shortcuts and preserves the draft", async () => {
  await render({ action: "NEW_TOPIC", initialRaw: "正文" });
  const body = host.querySelector("textarea")!;
  await act(async () => { body.dispatchEvent(new KeyboardEvent("keydown", { key: "I", code: "KeyI", metaKey: true, shiftKey: true, bubbles: true })); });
  expect(host.querySelector("input[aria-label=搜索模板]")).toBeTruthy();
  await act(async () => { body.dispatchEvent(new KeyboardEvent("keydown", { key: ">", code: "Period", ctrlKey: true, shiftKey: true, bubbles: true })); });
  expect(host.querySelector('input[type="date"]')).toBeTruthy();
  expect(body.value).toBe("正文");
});

it("quotes the actual addressed floor, never the display snippet; observes permissions", async () => {
  vi.mocked(fetchTopicDetail).mockResolvedValue({ id: 42, title: "话题", posts: [{ id: 99, postNumber: 3, username: "Neo" }] } as TopicDetailModel);
  vi.mocked(fetchPostRawMarkdown).mockResolvedValue("完整的**原文**");
  await render({ action: "REPLY", topicId: 42, replyToPostNumber: 3, replyToSnippet: "预览截断" }, false);
  await click("更多创作工具");
  expect(button("插入模板").disabled).toBe(true);
  expect(button("插入目录").disabled).toBe(true);
  await click("引用整个帖子");
  expect(fetchPostRawMarkdown).toHaveBeenCalledExactlyOnceWith(99);
  expect(host.querySelector("textarea")?.value).toContain('[quote="Neo, post:3, topic:42"]\n完整的**原文**\n[/quote]');
  expect(publishReply).not.toHaveBeenCalled();
});

it("uses Discourse markup and keeps trusted variables, Unicode and selected multiline content intact", () => {
  expect(parseForumTemplates({ templates: [null, template] })).toEqual([template]);
  expect(() => parseForumTemplates({})).toThrow();
  expect(expandForumTemplate("%{my_username} %{reply_to_name,fallback:各位} %{unknown}", {my_username:"Neo"})).toBe("Neo 各位 %{unknown}");
  expect(forumMarkup("aiQuote", "甲\n乙")).toBe("> [!AI]\n> 甲\n> 乙");
  expect(forumMarkup("aiCode", "A```B")).toBe("```` text ai\nA```B\n````");
  expect(forumMarkup("toc", "")).toBe('<div data-theme-toc="true"> </div>');
  expect(forumMarkup("footnote", "注释")).toBe("^[注释]");
  expect(chartMarkup("统计", "人数", [{label:"安卓",value:"0"}, {label:"桌面",value:"2.5"}])).toBe("[chart type='horizontalBar' title='统计' xAxisTitle='人数']\n安卓 | 0\n桌面 | 2.5\n[/chart]");
  expect(chartMarkup("", "", [{label:"异常",value:""}])).toBeNull();
  expect(wrapMarkup("box", "id=test id", "甲")).toBe('[wrap=box id="test id"]\n甲\n[/wrap]');
  expect(wrapMarkup("bad]", "", "甲")).toBeNull();
  expect(dateMarkup("2026-11-27", "22:00", "Asia/Shanghai")).toBe('[date=2026-11-27 time=22:00:00 timezone="Asia/Shanghai"]');
  expect(dateMarkup("2026-02-30", "", "UTC")).toBeNull();
});

it("labels quotes, details, and code in the editor preview without interpreting code as markup", () => {
  const raw = (["aiQuote", "aiDetails", "aiCode"] as const).map(key => forumMarkup(key, "[details]literal[/details]")).join("\n\n");
  const doc = new DOMParser().parseFromString(renderDiscourseMarkdownPreview(raw), "text/html");
  expect(doc.body.textContent?.match(/AI 生成/g)).toHaveLength(3);
  expect(doc.querySelector("pre code")?.textContent).toBe("[details]literal[/details]");
  expect([...doc.querySelectorAll("summary")].some(el => el.textContent?.includes("AI 生成"))).toBe(true);
});
