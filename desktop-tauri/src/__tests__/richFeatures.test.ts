import { describe, expect, it } from "vitest";
import { renderDiscourseMarkdownPreview } from "../lib/markdownPreview";
import { prepareCookedHtml } from "../lib/cooked";
import { highlightCodeElement } from "../lib/richCodeHighlight";
import { renderMathElement } from "../lib/richMathMermaid";
import { observeCookedRichContent } from "../lib/richDecorators";
import {
  clearFindHighlights,
  extractPostHeadings,
  highlightFindMatches,
} from "../components/ReaderEnhancements";
import { buildAuthorFeedbackPayload, LINDO_AUTHOR_USERNAME } from "../components/SecondaryScreens";
import {
  buildDiscourseSearchQuery,
  parseConnectHtml,
  parseSuggestedTopicsFromTopicDetail,
} from "../lib/api";

describe("markdownPreview — full GFM + Discourse + Obsidian syntax", () => {
  it("renders Screenshot 2 list directly followed by GFM table without blank line", () => {
    const raw = `- sdfsdf\\\n- asdfasdf\n| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |`;
    const html = renderDiscourseMarkdownPreview(raw);
    expect(html).toContain("<ul><li>sdfsdf</li><li>asdfasdf</li></ul>");
    expect(html).toContain("<table><thead><tr><th>列一</th><th>列二</th></tr></thead><tbody><tr><td>内容</td><td>内容</td></tr></tbody></table>");
  });

  it("renders Obsidian collapsible Callout + JSON fence + BBCode red bold + math", () => {
    const raw = `> [!Note]+ ~/.claude/settings.json
> \`\`\`json
> {"model": "opus[1m]"}
> \`\`\`

属性必须写为 [color=red]**字符串**[/color]，公式 $E=mc^2$，价格 $100 不误判。

$$
\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}
$$`;
    const html = renderDiscourseMarkdownPreview(raw);
    expect(html).toContain('details open class="lindo-callout lindo-callout-note"');
    expect(html).toContain("~/.claude/settings.json");
    expect(html).toContain('class="lang-json"');
    expect(html).toContain('data-has-custom-color="1"');
    expect(html).toContain("<strong>字符串</strong>");
    expect(html).toContain('<span class="math">E=mc^2</span>');
    expect(html).toContain("价格 $100 不误判");
    expect(html).toContain('<div class="math" data-math-display="1">');
  });

  it("renders Discourse polls, details, quotes, spoilers, images with scale, mentions, and shortcodes", () => {
    const raw = `[poll type=regular]\n* A\n* B\n[/poll]\n\n[details="展开"]\n内容\n[/details]\n\n![shot.png|800x600, 50%](upload://abc.png)\n\n@neo :rocket: [spoiler]secret[/spoiler]`;
    const html = renderDiscourseMarkdownPreview(raw, { "upload://abc.png": "blob:demo" });
    expect(html).toContain('class="lindo-preview-poll"');
    expect(html).toContain("<details open><summary>展开</summary>");
    expect(html).toContain('src="blob:demo"');
    expect(html).toContain("width:400px;max-width:50%");
    expect(html).toContain('class="mention"');
    expect(html).toContain("🚀");
    expect(html).toContain('<span class="spoiled"');
  });
});

describe("richCodeHighlight — lazy Highlight.js core subset", () => {
  it("syntax-highlights JSON keys and strings cleanly and stays idempotent", () => {
    const pre = document.createElement("pre");
    const code = document.createElement("code");
    code.className = "lang-json";
    code.textContent = '{\n  "HTTPS_PROXY": "http://127.0.0.1:7890",\n  "cleanupPeriodDays": 720\n}';
    pre.appendChild(code);

    highlightCodeElement(code);
    expect(code.classList.contains("hljs")).toBe(true);
    expect(code.querySelector(".hljs-attr")?.textContent).toContain("HTTPS_PROXY");
    expect(code.querySelector(".hljs-string")?.textContent).toContain("127.0.0.1:7890");
    expect(code.querySelector(".hljs-number")?.textContent).toBe("720");

    const snapshot = code.innerHTML;
    highlightCodeElement(code);
    expect(code.innerHTML).toBe(snapshot);
  });

  it("auto-highlights untagged JSON blocks and skips lang-mermaid", () => {
    const untagged = document.createElement("code");
    untagged.textContent = '{"ok": true, "count": 42}';
    highlightCodeElement(untagged);
    expect(untagged.classList.contains("hljs")).toBe(true);

    const mermaidCode = document.createElement("code");
    mermaidCode.className = "lang-mermaid";
    mermaidCode.textContent = "graph TD; A-->B;";
    highlightCodeElement(mermaidCode);
    expect(mermaidCode.classList.contains("hljs")).toBe(false);
  });
});

describe("richMathMermaid — real KaTeX rendering", () => {
  it("renders inline and display math elements into KaTeX HTML", async () => {
    const inlineEl = document.createElement("span");
    inlineEl.className = "math";
    inlineEl.textContent = "E = mc^2";
    await renderMathElement(inlineEl);
    expect(inlineEl.classList.contains("lindo-math-rendered")).toBe(true);
    expect(inlineEl.querySelector(".katex")).not.toBeNull();

    const blockEl = document.createElement("div");
    blockEl.className = "math";
    blockEl.dataset.mathDisplay = "1";
    blockEl.textContent = "$$\\frac{a}{b}$$";
    await renderMathElement(blockEl);
    expect(blockEl.querySelector(".katex-display")).not.toBeNull();
  });
});

describe("ReaderEnhancements helpers & search query builder", () => {
  it("extracts headings from post body while skipping headings inside quotes/oneboxes", () => {
    const html = `
      <h1 id="h1">总览</h1>
      <aside class="quote"><h2>引用里的标题不算</h2></aside>
      <aside class="onebox"><h3>Onebox 里的也不算</h3></aside>
      <h2 id="h2">安装步骤</h2>
      <h3>常见问题</h3>
    `;
    const headings = extractPostHeadings(html);
    expect(headings).toHaveLength(3);
    expect(headings.map((h) => h.text)).toEqual(["总览", "安装步骤", "常见问题"]);
    expect(headings.map((h) => h.level)).toEqual([1, 2, 3]);
  });

  it("highlights in-topic find matches and unwraps cleanly without corrupting DOM", () => {
    const root = document.createElement("div");
    root.innerHTML = `<div class="lindo-cooked"><p>Claude Code 与 claude 配置</p><pre><button data-copy-code="1">复制</button><code>claude</code></pre></div>`;
    const hits = highlightFindMatches(root, "claude");
    expect(hits.length).toBe(3);
    expect(root.querySelectorAll("mark.lindo-find-hit").length).toBe(3);

    clearFindHighlights(root);
    expect(root.querySelectorAll("mark.lindo-find-hit").length).toBe(0);
    expect(root.querySelector("p")?.textContent).toBe("Claude Code 与 claude 配置");
  });

  it("builds Discourse advanced search queries accurately", () => {
    const q = buildDiscourseSearchQuery("proxy", "likes", {
      categorySlug: "develop",
      tag: "#Claude",
      author: "@neo",
      inTitle: true,
      solved: true,
      afterDays: 7,
    });
    expect(q).toContain("proxy");
    expect(q).toContain("#develop");
    expect(q).toContain("tags:Claude");
    expect(q).toContain("@neo");
    expect(q).toContain("in:title");
    expect(q).toContain("status:solved");
    expect(q).toMatch(/after:\d{4}-\d{2}-\d{2}/);
    expect(q).toContain("order:likes");
  });

  it("end-to-end observeCookedRichContent highlights code & renders KaTeX on a full Kitchen Sink post", async () => {
    const kitchenSinkRaw = `> [!Note]+ ~/.claude/settings.json
> \`\`\`json
> {"HTTPS_PROXY": "http://127.0.0.1:7890", "cleanupPeriodDays": 720}
> \`\`\`

值必须写为 [color=red]**字符串**[/color]，公式 $a^2 + b^2 = c^2$。

| 列一 | 列二 |
| --- | --- |
| A | B |`;
    const container = document.createElement("div");
    container.className = "lindo-cooked";
    container.innerHTML = prepareCookedHtml(renderDiscourseMarkdownPreview(kitchenSinkRaw));
    document.body.appendChild(container);

    const cleanup = observeCookedRichContent(container);
    // Wait briefly for lazy dynamic imports + idle callback
    await new Promise((r) => setTimeout(r, 150));

    expect(container.querySelector("details.lindo-callout-note[open]")).not.toBeNull();
    expect(container.querySelector("code.lang-json.hljs .hljs-attr")?.textContent).toContain("HTTPS_PROXY");
    expect(container.querySelector(".math.lindo-math-rendered .katex")).not.toBeNull();
    expect(container.querySelector(".lindo-table-wrap table")).not.toBeNull();
    expect(container.querySelector('[data-has-custom-color="1"] strong')?.textContent).toBe("字符串");

    cleanup();
    container.remove();
  });

  it("formats author feedback PM payload with category prefix, version env, and capped anonymized logs", () => {
    expect(LINDO_AUTHOR_USERNAME).toBe("jedilus");
    const payload = buildAuthorFeedbackPayload({
      category: "bug",
      title: "暗色模式下某个按钮发虚",
      body: "复现步骤：打开话题后点击右侧按钮",
      version: "1.0.57",
      platformLabel: "macOS",
      includeEnv: true,
      diagLogSnippet: "ERROR /t/123.json -> 500",
    });
    expect(payload.title).toBe("[Lindo PC · Bug 反馈] 暗色模式下某个按钮发虚");
    expect(payload.raw).toContain("Lindo Desktop v1.0.57");
    expect(payload.raw).toContain("macOS");
    expect(payload.raw).toContain('[details="诊断日志摘要（已脱敏）"]');
    expect(payload.raw).toContain("ERROR /t/123.json -> 500");

    const plain = buildAuthorFeedbackPayload({
      category: "ux",
      title: "[Lindo PC] 已有前缀不重复添加",
      body: "建议增加快捷键",
      version: "1.0.57",
      platformLabel: "Windows",
      includeEnv: false,
      diagLogSnippet: null,
    });
    expect(plain.title).toBe("[Lindo PC] 已有前缀不重复添加");
    expect(plain.raw).toBe("建议增加快捷键");
  });

  it("extracts original poster avatars and flair for suggested/related topics across Discourse schemas", () => {
    const parsed = parseSuggestedTopicsFromTopicDetail(
      {
        id: 100,
        users: [
          {
            id: 42,
            username: "trinity",
            name: "Trinity",
            avatar_template: "/user_avatar/linux.do/trinity/{size}/9_2.png",
          },
        ],
        suggested_topics: [
          {
            id: 201,
            title: "嵌入式 user 结构体的相关话题",
            posts_count: 15,
            views: 320,
            posters: [
              {
                extras: "latest",
                description: "最新发帖人",
                user: {
                  id: 99,
                  username: "morpheus",
                  avatar_template: "/user_avatar/linux.do/morpheus/{size}/2_2.png",
                },
              },
              {
                extras: null,
                description: "原始发帖人",
                user: {
                  id: 88,
                  username: "neo",
                  name: "Thomas Anderson",
                  avatar_template: "/user_avatar/linux.do/neo/{size}/1_2.png",
                  flair_name: "trust_level_3",
                  flair_url: "fa-shield",
                },
              },
            ],
          },
          {
            id: 202,
            title: "顶层 users 字典映射的相关话题",
            posts_count: 4,
            posters: [{ description: "Original Poster", user_id: 42 }],
          },
        ],
      },
      100
    );

    expect(parsed).toHaveLength(2);
    expect(parsed[0].originalPoster.userId).toBe(88);
    expect(parsed[0].originalPoster.username).toBe("neo");
    expect(parsed[0].originalPoster.name).toBe("Thomas Anderson");
    expect(parsed[0].originalPoster.avatarTemplate).toBe(
      "/user_avatar/linux.do/neo/{size}/1_2.png"
    );
    expect(parsed[0].originalPoster.flair?.name).toBe("trust_level_3");

    expect(parsed[1].originalPoster.userId).toBe(42);
    expect(parsed[1].originalPoster.username).toBe("trinity");
    expect(parsed[1].originalPoster.avatarTemplate).toBe(
      "/user_avatar/linux.do/trinity/{size}/9_2.png"
    );
  });

  it("parses connect.linux.do rings, bars, quota slots, and 6-month silence/ban status cards", () => {
    const html = `
      <h2 class="card-title">信任级别 3 的要求</h2>
      <div class="card-subtitle">过去 100 天内的数据</div>
      <span class="badge">已达到</span>
      <div class="tl3-ring">
        <div class="tl3-ring-circle met" style="--val: 78; --max: 50">
          <span class="tl3-ring-value"><span class="tl3-ring-current">78</span><span class="tl3-ring-target">/ 50</span></span>
        </div>
        <div class="tl3-ring-label">访问天数</div>
      </div>
      <div class="tl3-bar-item">
        <div class="tl3-bar-header"><span class="tl3-bar-label">回复话题</span><span class="tl3-bar-nums met">257/10</span></div>
        <div class="tl3-bar-track"><div class="tl3-bar-fill met" style="--val: 257; --max: 10"></div></div>
      </div>
      <div class="tl3-quota-card met">
        <div class="tl3-quota-header"><span class="tl3-quota-label">被举报帖子</span><span class="tl3-quota-nums">0 / 5</span></div>
      </div>
      <div class="tl3-status-card met">
        <div class="tl3-status-info"><div>被禁言</div><div>过去 6 个月</div></div>
        <div class="tl3-status-val">0</div>
      </div>
      <div class="tl3-status-card met">
        <div class="tl3-status-info"><div>被封禁</div><div>过去 6 个月</div></div>
        <div class="tl3-status-val">0</div>
      </div>
      <p>以上“话题”指主贴，“帖子”指传统意义的回复。</p>
      <p>已达到信任级别 3 要求，请保持。</p>
    `;
    const report = parseConnectHtml(html, "jedilus");
    expect(report).not.toBeNull();
    expect(report?.subtitle).toBe("@jedilus · 过去 100 天内的数据");
    expect(report?.isQualified).toBe(true);
    expect(report?.footerSummary).toBe("已达到信任级别 3 要求，请保持。");

    const ring = report?.metrics.find((m) => m.label === "访问天数");
    expect(ring?.kind).toBe("ring");
    expect(ring?.currentValue).toBe(78);

    const bar = report?.metrics.find((m) => m.label === "回复话题");
    expect(bar?.kind).toBe("bar");
    expect(bar?.currentValue).toBe(257);

    const quota = report?.metrics.find((m) => m.label === "被举报帖子");
    expect(quota?.kind).toBe("quota");
    expect(quota?.targetValue).toBe(5);

    const silence = report?.metrics.find((m) => m.label === "被禁言");
    expect(silence?.kind).toBe("status");
    expect(silence?.currentValue).toBe(0);
    expect(silence?.sublabel).toBe("过去 6 个月");

    const ban = report?.metrics.find((m) => m.label === "被封禁");
    expect(ban?.kind).toBe("status");
    expect(ban?.currentValue).toBe(0);
  });
});
