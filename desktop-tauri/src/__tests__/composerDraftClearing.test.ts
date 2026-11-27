import React, { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ComposerModal } from "../components/ComposerModal";
import {
  deleteCloudDraft, deleteLocalDraftInRust, fetchSingleCloudDraft, saveCloudDraft, saveLocalDraftInRust,
} from "../lib/api";
import type { CategoryMeta, CloudDraftItem, ComposerLaunchRequest, LocalDraftRecord } from "../lib/types";

vi.mock("../lib/api", async original => ({
  ...await original<typeof import("../lib/api")>(),
  fetchSingleCloudDraft: vi.fn(), saveCloudDraft: vi.fn(), deleteCloudDraft: vi.fn(),
  saveLocalDraftInRust: vi.fn(), deleteLocalDraftInRust: vi.fn(),
  fetchSiteCustomEmojis: vi.fn(async () => []), fetchCategoryAllowedTags: vi.fn(async () => ({ options: [] })),
}));

let host: HTMLDivElement, root: Root;
let local: LocalDraftRecord | undefined;
let remote: CloudDraftItem | null;
let sequence: number;
const reply: ComposerLaunchRequest = { action: "REPLY", topicId: 42, replyToPostNumber: 2 };
const onClose = vi.fn();
const exitGuard = { current: null as ((proceed: () => void) => void) | null };
function serverReply(raw: string, floor = 2): CloudDraftItem {
  return { draftKey: "topic_42", action: "REPLY", raw, title: "", tags: [], sequence, replyToPostNumber: floor };
}
beforeEach(() => {
  vi.useFakeTimers();
  vi.resetAllMocks();
  (globalThis as any).IS_REACT_ACT_ENVIRONMENT = true;
  local = undefined; remote = null; sequence = 4;
  vi.mocked(fetchSingleCloudDraft).mockImplementation(async () => ({ draft: remote, sequence }));
  vi.mocked(saveLocalDraftInRust).mockImplementation(async value => { local = value; });
  vi.mocked(deleteLocalDraftInRust).mockImplementation(async () => { local = undefined; });
  vi.mocked(saveCloudDraft).mockImplementation(async ({ data }) => {
    sequence++; remote = serverReply(data.reply); return sequence;
  });
  vi.mocked(deleteCloudDraft).mockImplementation(async (_, version) => { if (version === sequence) remote = null; });
  host = document.createElement("div"); document.body.append(host);
  root = createRoot(host);
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.useRealTimers();
});
async function open(request: ComposerLaunchRequest = reply, categories: CategoryMeta[] = []) {
  await act(async () => root.render(React.createElement(ComposerModal, {
    request, savedDraft: local, categories, exitGuardRef: exitGuard,
    onClose, onPublished: vi.fn(), onTopicEdited: vi.fn(), onTriggerVerify: vi.fn(),
  })));
}
async function typeField(field: HTMLInputElement | HTMLTextAreaElement, value: string) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(Object.getPrototypeOf(field), "value")!.set!.call(field, value);
    field.dispatchEvent(new Event("input", { bubbles: true }));
  });
}
const body = () => host.querySelector("textarea")!;
const close = () => act(async () => host.querySelector<HTMLButtonElement>('button[title="关闭"]')!.click());
const tick = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms); });

it.each([100, 500, 3000])("clears a reply after %i ms without asking to save it, including on reopen", async elapsed => {
  await open();
  await typeField(body(), "已经删掉的字"); await tick(elapsed);
  expect(Boolean(local)).toBe(elapsed >= 450);
  expect(Boolean(remote)).toBe(elapsed >= 2800);
  await typeField(body(), " \n");
  await close();
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(host.textContent).not.toContain("保存草稿？");
  await tick(9000);
  expect(local).toBeUndefined(); expect(remote).toBeNull();
  await act(async () => root.render(null));
  await open();
  expect(body().value).toBe("");
});

it("waits for the revision of an in-flight save before navigation and deletion", async () => {
  let release!: () => void;
  vi.mocked(saveCloudDraft).mockImplementationOnce(async ({ data }) => {
    await new Promise<void>(resolve => { release = resolve; });
    sequence++; remote = serverReply(data.reply); return sequence;
  });
  await open(); await typeField(body(), "曾经的回复"); await tick(3000);
  await typeField(body(), "");
  const navigate = vi.fn();
  await act(async () => exitGuard.current!(navigate));
  expect(navigate).not.toHaveBeenCalled();
  await act(async () => release());
  expect(deleteCloudDraft).toHaveBeenCalledExactlyOnceWith("topic_42", 5, true);
  expect(navigate).toHaveBeenCalledTimes(1); expect(local).toBeUndefined(); expect(remote).toBeNull();
});

it("a late lookup cannot reinsert old text after clearing a restored draft", async () => {
  let resolve!: (item: {draft: CloudDraftItem | null; sequence: number}) => void;
  vi.mocked(fetchSingleCloudDraft).mockImplementationOnce(() => new Promise(done => { resolve = done; }));
  remote = serverReply("上次保存的草稿");
  local = { key: "reply_42_2", action: "REPLY", raw: remote.raw, title: "", tags: [], sequence, updatedAt: 1, replyToPostNumber: 2 };
  await open(); await typeField(body(), ""); await close();
  expect(onClose).not.toHaveBeenCalled();
  await act(async () => resolve({ draft: remote, sequence }));
  expect(onClose).toHaveBeenCalledTimes(1); expect(local).toBeUndefined(); expect(remote).toBeNull();
});

it("preserves another floor's cloud draft and prompts for a new topic with only a title", async () => {
  remote = serverReply("另一楼", 3);
  await open(); await typeField(body(), "临时回复"); await tick(500); await typeField(body(), ""); await close();
  expect(deleteCloudDraft).not.toHaveBeenCalled(); expect(remote?.raw).toBe("另一楼");
  await act(async () => root.render(null));
  remote = null;
  await open({ action: "NEW_TOPIC" });
  await typeField(host.querySelector<HTMLInputElement>('input[placeholder="话题标题"]')!, "保留这个标题");
  await typeField(body(), "先写正文"); await typeField(body(), ""); await close();
  expect(host.textContent).toContain("保存草稿？");
  await act(async () => [...host.querySelectorAll("button")].find(b => b.textContent === "保存")!.click());
  expect(local?.title).toBe("保留这个标题"); expect(local?.raw).toBe("");
});

it("shows a retry only when the known cloud draft cannot be cleared", async () => {
  remote = serverReply("已经同步的草稿");
  vi.mocked(deleteCloudDraft).mockRejectedValueOnce(new Error("offline"));
  await open(); expect(body().value).toBe(remote.raw);
  await typeField(body(), ""); await close();
  expect(onClose).not.toHaveBeenCalled();
  expect(host.textContent).toContain("云端草稿未清除");
  await act(async () => [...host.querySelectorAll("button")].find(b => b.textContent === "重试")!.click());
  expect(onClose).toHaveBeenCalledTimes(1); expect(remote).toBeNull();
});

it("matches the restored draft again if the opening lookup failed", async () => {
  remote = serverReply("已保存的回复");
  local = { key: "reply_42", action: "REPLY", raw: remote.raw, title: "", tags: [], sequence, updatedAt: 5, replyToPostNumber: 2 };
  vi.mocked(fetchSingleCloudDraft).mockRejectedValueOnce(new Error("offline"));
  await open(); await typeField(body(), ""); await close();
  expect(fetchSingleCloudDraft).toHaveBeenCalledTimes(2);
  expect(deleteCloudDraft).toHaveBeenCalledExactlyOnceWith("topic_42", sequence, true);
  expect(onClose).toHaveBeenCalledTimes(1); expect(local).toBeUndefined(); expect(remote).toBeNull();
});

it("does not autosave discarded text while a failed cloud deletion awaits a choice", async () => {
  remote = serverReply("已同步的草稿");
  vi.mocked(deleteCloudDraft).mockRejectedValueOnce(new Error("offline"));
  await open(); await typeField(body(), "已经放弃的修改"); await tick(500);
  await close();
  await act(async () => [...host.querySelectorAll("button")].find(b => b.textContent === "不保存")!.click());
  expect(host.textContent).toContain("云端草稿未清除");
  await tick(3000);
  expect(local).toBeUndefined(); expect(saveCloudDraft).not.toHaveBeenCalled();
  await act(async () => [...host.querySelectorAll("button")].find(b => b.textContent === "直接退出")!.click());
  expect(onClose).toHaveBeenCalledTimes(1);
});

it("just opening and closing a blank composer leaves local and remote storage untouched", async () => {
  await open(); await close();
  expect(onClose).toHaveBeenCalledTimes(1);
  expect(deleteLocalDraftInRust).not.toHaveBeenCalled(); expect(deleteCloudDraft).not.toHaveBeenCalled();
  expect(saveLocalDraftInRust).not.toHaveBeenCalled(); expect(saveCloudDraft).not.toHaveBeenCalled();
});

it("a late category template respects a deliberately cleared body and still works while editing the title", async () => {
  const request: ComposerLaunchRequest = { action: "NEW_TOPIC", initialCategoryId: 4 };
  const category: CategoryMeta = { id: 4, name: "开发", displayName: "开发", slug: "dev", colorHex: "0088cc",
    description: "", readRestricted: false, topicTemplate: "复现步骤" };
  await open(request);
  await typeField(body(), "正文"); await typeField(body(), "");
  await open(request, [category]); expect(body().value).toBe("");
  await act(async () => root.render(null));
  await open(request);
  await typeField(host.querySelector<HTMLInputElement>('input[placeholder="话题标题"]')!, "只有标题");
  await open(request, [category]); expect(body().value).toBe("复现步骤");
});
