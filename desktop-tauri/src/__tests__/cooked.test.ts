import { describe, expect, it } from "vitest";
import {
  classifyExternalUrl,
  parseInternalForumLink,
  parseInternalTopicLink,
  prepareCookedHtml,
  prepareCookedHtmlCached,
} from "../lib/cooked";

describe("prepareCookedHtml — Discourse rich content & regressions", () => {
  it("transforms Obsidian collapsible [+]/[-] and static callouts matching Linux.do Web", () => {
    const shot1Html = `
      <blockquote>
        <p>[!Note]+ ~/.claude/settings.json</p>
        <pre><code class="lang-json">{\n  "model": "opus[1m]"\n}</code></pre>
      </blockquote>
      <blockquote>
        <p>[!Warning]- 默认收起的警告<br>这是正文第一行</p>
      </blockquote>
      <blockquote>
        <p>[!Tip] 不可折叠提示</p>
        <p>提示内容</p>
      </blockquote>
    `;
    const out = prepareCookedHtml(shot1Html, { currentTopicId: 100 });
    const doc = new DOMParser().parseFromString(out, "text/html");

    const noteDetails = doc.querySelector<HTMLDetailsElement>('details.lindo-callout.lindo-callout-note[data-callout="note"]');
    expect(noteDetails).not.toBeNull();
    expect(noteDetails?.hasAttribute("open")).toBe(true);
    expect(noteDetails?.querySelector("summary .lindo-callout-title-text")?.textContent?.trim()).toBe("~/.claude/settings.json");
    expect(noteDetails?.querySelector(".lindo-callout-body pre code.lang-json")).not.toBeNull();
    expect(noteDetails?.textContent).not.toContain("[!Note]+");

    const warnDetails = doc.querySelector<HTMLDetailsElement>('details.lindo-callout.lindo-callout-warning[data-callout="warning"]');
    expect(warnDetails).not.toBeNull();
    expect(warnDetails?.hasAttribute("open")).toBe(false);
    expect(warnDetails?.querySelector("summary .lindo-callout-title-text")?.textContent?.trim()).toBe("默认收起的警告");
    expect(warnDetails?.querySelector(".lindo-callout-body p")?.textContent?.trim()).toBe("这是正文第一行");

    const tipDiv = doc.querySelector('div.lindo-callout.lindo-callout-tip[data-callout="tip"]');
    expect(tipDiv).not.toBeNull();
    expect(tipDiv?.querySelector(".lindo-callout-title-text")?.textContent?.trim()).toBe("不可折叠提示");
    expect(tipDiv?.querySelector(".lindo-callout-body p")?.textContent?.trim()).toBe("提示内容");
  });

  it("preserves BBCode/inline custom text colors and rejects unsafe CSS values", () => {
    const html = `
      <p>必须写为 <span style="color: red;"><strong>字符串</strong></span></p>
      <p><span data-bbcode-color="#ef4444"><strong>危险提示</strong></span></p>
      <p><span data-bbcode-color="url(javascript:alert(1))">安全文本</span></p>
    `;
    const out = prepareCookedHtml(html);
    const doc = new DOMParser().parseFromString(out, "text/html");

    const redSpan = doc.querySelectorAll("span")[0];
    expect(redSpan.getAttribute("data-has-custom-color")).toBe("1");
    expect(redSpan.getAttribute("style")).toContain("red");

    const bbSpan = doc.querySelectorAll("span")[1];
    expect(bbSpan.getAttribute("data-has-custom-color")).toBe("1");
    expect(bbSpan.getAttribute("style")).toContain("#ef4444");

    const unsafeSpan = doc.querySelectorAll("span")[2];
    expect(unsafeSpan.getAttribute("data-has-custom-color")).toBeNull();
    expect(unsafeSpan.getAttribute("style") || "").not.toContain("javascript:");
  });

  it("handles cross-topic quotes without redundant '查看原帖' and adds quiet inline expand chevron", () => {
    const crossQuoteHtml = `
      <aside class="quote quote-modified" data-post="1" data-topic="888888">
        <div class="title">
          <div class="quote-controls"></div>
          <img alt="" width="24" height="24" src="/user_avatar/linux.do/haley/48/1_2.png" class="avatar">
          <div class="quote-title__text-content">
            <a href="//linux.do/t/any-router-guide/888888">Any牌路由器使用清障!</a>
            <a class="badge-category__wrapper" href="/c/develop/4">
              <svg class="fa d-icon d-icon-code"><use href="#code"></use></svg>
              <span>开发调优</span>
            </a>
          </div>
        </div>
        <blockquote><p>引用摘要...</p></blockquote>
      </aside>
    `;
    const out = prepareCookedHtml(crossQuoteHtml, { currentTopicId: 2496114 });
    const doc = new DOMParser().parseFromString(out, "text/html");

    const primaryLink = doc.querySelector<HTMLAnchorElement>("aside.quote .title a");
    expect(primaryLink?.getAttribute("href")).toBe("https://linux.do/t/any-router-guide/888888");
    expect(out).not.toContain("查看原帖");

    const expandBtn = doc.querySelector<HTMLButtonElement>("[data-quote-expand]");
    expect(expandBtn).not.toBeNull();
    expect(expandBtn?.getAttribute("data-quote-topic")).toBe("888888");
    expect(expandBtn?.getAttribute("data-quote-post")).toBe("1");
    expect(expandBtn?.getAttribute("aria-expanded")).toBe("false");

    // Missing `<use href="#code">` sprite must be replaced by real 13x13 inline SVG icon
    const svg = doc.querySelector("aside.quote svg.lindo-cooked-icon");
    expect(svg).not.toBeNull();
    expect(svg?.querySelector("use")).toBeNull();
    expect(svg?.querySelector("path")).not.toBeNull();
  });

  it("keeps compact '#N 楼 ›' jump + expand button on same-topic quotes, omits expand on data-full=true", () => {
    const html = `
      <aside class="quote" data-post="18" data-topic="2496114">
        <div class="title">neo:</div>
        <blockquote><p>同帖引用</p></blockquote>
      </aside>
      <aside class="quote" data-post="22" data-topic="2496114" data-full="true">
        <div class="title">neo:</div>
        <blockquote><p>完整引用</p></blockquote>
      </aside>
    `;
    const out = prepareCookedHtml(html, { currentTopicId: 2496114 });
    const doc = new DOMParser().parseFromString(out, "text/html");
    const quotes = doc.querySelectorAll("aside.quote");

    expect(quotes[0].querySelector("[data-quote-jump-floor]")?.textContent).toBe("#18 楼 ›");
    expect(quotes[0].querySelector("[data-quote-expand]")).not.toBeNull();
    expect(quotes[1].querySelector("[data-quote-expand]")).toBeNull();
  });

  it("preserves in-page #footnote and #heading anchors verbatim without prepending linux.do", () => {
    const html = `<p>正文<sup class="footnote-ref"><a href="#footnote-1-42">1</a></sup></p>`;
    const out = prepareCookedHtml(html);
    const doc = new DOMParser().parseFromString(out, "text/html");
    expect(doc.querySelector("a")?.getAttribute("href")).toBe("#footnote-1-42");
  });

  it("bridges same-origin video/audio uploads and converts HTTP iframes into graceful embed cards", () => {
    const html = `
      <video src="/uploads/default/original/demo.mp4"></video>
      <iframe src="//player.bilibili.com/player.html?bvid=BV1xx"></iframe>
      <iframe src="javascript:alert(1)"></iframe>
      <script>alert("xss")</script>
    `;
    const out = prepareCookedHtml(html);
    const doc = new DOMParser().parseFromString(out, "text/html");

    const video = doc.querySelector("video");
    expect(video?.getAttribute("data-lindo-media-src")).toBe("https://linux.do/uploads/default/original/demo.mp4");
    expect(video?.hasAttribute("controls")).toBe(true);
    expect(video?.getAttribute("preload")).toBe("metadata");

    const embedLink = doc.querySelector<HTMLAnchorElement>(".lindo-embed-card a.lindo-embed-link");
    expect(embedLink?.getAttribute("href")).toBe("https://player.bilibili.com/player.html?bvid=BV1xx");
    expect(embedLink?.textContent).toContain("Bilibili");

    expect(doc.querySelector("iframe")).toBeNull();
    expect(doc.querySelector("script")).toBeNull();
  });

  it("wraps bare tables in horizontal-scroll wrapper without double-wrapping .md-table", () => {
    const html = `
      <table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>
      <div class="md-table"><table><tr><th>C</th></tr></table></div>
    `;
    const out = prepareCookedHtml(html);
    const doc = new DOMParser().parseFromString(out, "text/html");
    expect(doc.querySelectorAll(".lindo-table-wrap").length).toBe(1);
    expect(doc.querySelectorAll(".md-table").length).toBe(1);
    expect(doc.querySelectorAll(".md-table .lindo-table-wrap").length).toBe(0);
  });

  it("memoizes short snippets via prepareCookedHtmlCached", () => {
    const snippet = `<p>前排合影 <img class="emoji" src="/images/emoji/twemoji/rocket.png" alt=":rocket:"></p>`;
    const first = prepareCookedHtmlCached(snippet);
    const second = prepareCookedHtmlCached(snippet);
    expect(first).toBe(second);
    expect(first).toContain("cdn.ldstatic.com/images/emoji/twemoji/rocket.png");
  });

  it("handles callouts with default localized title and inline code in title", () => {
    const html = `
      <blockquote><p>[!Danger]</p><p>不可逆操作</p></blockquote>
      <blockquote><p>[!Note]+ <code>~/.claude/settings.json</code></p><p>配置文件</p></blockquote>
    `;
    const out = prepareCookedHtml(html);
    const doc = new DOMParser().parseFromString(out, "text/html");
    const danger = doc.querySelector(".lindo-callout-danger");
    expect(danger?.querySelector(".lindo-callout-title-text")?.textContent?.trim()).toBe("危险");
    const note = doc.querySelector("details.lindo-callout-note");
    expect(note?.querySelector("summary .lindo-callout-title-text code")?.textContent).toBe(
      "~/.claude/settings.json"
    );
  });

  it("unwraps lightbox and plain image-only anchors while constraining external image links", () => {
    const html = `
      <div class="lightbox-wrapper">
        <a class="lightbox" href="https://cdn3.ldstatic.com/original/4X/7/c/9/foo.jpeg">
          <img src="https://cdn3.ldstatic.com/optimized/4X/7/c/9/foo.jpeg" width="244" height="500">
          <div class="meta">foo.jpeg</div>
        </a>
      </div>
      <p><a href="https://cdn3.ldstatic.com/original/4X/a/b/c/bar.png"><img src="https://cdn3.ldstatic.com/optimized/4X/a/b/c/bar.png"></a></p>
      <p><a href="https://github.com/owner/repo"><img src="https://example.com/banner.png"></a></p>
      <p><a href="https://cdn3.ldstatic.com/original/4X/x.jpeg">查看原图</a></p>
    `;
    const out = prepareCookedHtml(html);
    const doc = new DOMParser().parseFromString(out, "text/html");

    const lightboxImg = doc.querySelector<HTMLImageElement>(".lightbox-wrapper img");
    expect(lightboxImg?.getAttribute("data-full-src")).toBe(
      "https://cdn3.ldstatic.com/original/4X/7/c/9/foo.jpeg"
    );
    expect(doc.querySelector(".lightbox-wrapper a")).toBeNull();
    expect(doc.querySelector(".lightbox-wrapper .meta")).toBeNull();

    const paragraphs = doc.querySelectorAll("p");
    expect(paragraphs[0]?.querySelector("a")).toBeNull();
    expect(paragraphs[0]?.querySelector("img")?.getAttribute("data-full-src")).toBe(
      "https://cdn3.ldstatic.com/original/4X/a/b/c/bar.png"
    );

    const repoAnchor = paragraphs[1]?.querySelector<HTMLAnchorElement>("a");
    expect(repoAnchor?.getAttribute("href")).toBe("https://github.com/owner/repo");
    expect(repoAnchor?.getAttribute("data-image-link")).toBe("1");

    const textImageLink = paragraphs[2]?.querySelector<HTMLAnchorElement>("a");
    expect(textImageLink?.getAttribute("href")).toBe(
      "https://cdn3.ldstatic.com/original/4X/x.jpeg"
    );
    expect(textImageLink?.textContent?.trim()).toBe("查看原图");
    expect(textImageLink?.hasAttribute("data-image-link")).toBe(false);
  });
});

describe("Internal forum link parser & external URL safety", () => {
  it("parses all linux.do internal URL shapes accurately (no greedy topicId bug)", () => {
    expect(parseInternalTopicLink("https://linux.do/t/2496114/12")).toEqual({
      topicId: 2496114,
      postNumber: 12,
    });
    expect(parseInternalTopicLink("//linux.do/t/some-slug/2496114/3")).toEqual({
      topicId: 2496114,
      postNumber: 3,
    });
    expect(parseInternalForumLink("https://linux.do/p/998877")).toEqual({
      kind: "post",
      postId: 998877,
    });
    expect(parseInternalForumLink("https://linux.do/u/neo/summary")).toEqual({
      kind: "user",
      username: "neo",
    });
    expect(parseInternalForumLink("https://linux.do/c/develop/4")).toEqual({
      kind: "category",
      categoryId: 4,
      slug: "develop",
    });
    expect(parseInternalForumLink("https://linux.do/tag/claude")).toEqual({
      kind: "tag",
      tag: "claude",
    });
  });

  it("blocks typosquatting and javascript links and flags disguised phishing links", () => {
    expect(classifyExternalUrl("https://linux-do.com/login").tier).toBe("BLOCKED");
    expect(classifyExternalUrl("javascript:alert(1)").tier).toBe("BLOCKED");
    expect(classifyExternalUrl("https://evil.example/steal", "https://github.com/org/repo").tier).toBe("RISKY");
    expect(classifyExternalUrl("https://linux.do/t/topic/123").tier).toBe("INTERNAL");
  });
});
