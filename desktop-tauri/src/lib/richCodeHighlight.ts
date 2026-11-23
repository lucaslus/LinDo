/**
 * On-demand syntax highlighting powered by `highlight.js/lib/core` with a curated
 * subset of high-frequency languages (~30 langs, dynamically code-split out of the main bundle).
 */
import hljs from "highlight.js/lib/core";
import bash from "highlight.js/lib/languages/bash";
import c from "highlight.js/lib/languages/c";
import cpp from "highlight.js/lib/languages/cpp";
import csharp from "highlight.js/lib/languages/csharp";
import css from "highlight.js/lib/languages/css";
import diff from "highlight.js/lib/languages/diff";
import dockerfile from "highlight.js/lib/languages/dockerfile";
import go from "highlight.js/lib/languages/go";
import graphql from "highlight.js/lib/languages/graphql";
import http from "highlight.js/lib/languages/http";
import ini from "highlight.js/lib/languages/ini";
import java from "highlight.js/lib/languages/java";
import javascript from "highlight.js/lib/languages/javascript";
import json from "highlight.js/lib/languages/json";
import kotlin from "highlight.js/lib/languages/kotlin";
import lua from "highlight.js/lib/languages/lua";
import makefile from "highlight.js/lib/languages/makefile";
import markdown from "highlight.js/lib/languages/markdown";
import nginx from "highlight.js/lib/languages/nginx";
import objectivec from "highlight.js/lib/languages/objectivec";
import php from "highlight.js/lib/languages/php";
import plaintext from "highlight.js/lib/languages/plaintext";
import powershell from "highlight.js/lib/languages/powershell";
import python from "highlight.js/lib/languages/python";
import ruby from "highlight.js/lib/languages/ruby";
import rust from "highlight.js/lib/languages/rust";
import scss from "highlight.js/lib/languages/scss";
import shell from "highlight.js/lib/languages/shell";
import sql from "highlight.js/lib/languages/sql";
import swift from "highlight.js/lib/languages/swift";
import typescript from "highlight.js/lib/languages/typescript";
import xml from "highlight.js/lib/languages/xml";
import yaml from "highlight.js/lib/languages/yaml";

let registered = false;

const LANG_ALIAS_MAP: Record<string, string> = {
  js: "javascript",
  jsx: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  ts: "typescript",
  tsx: "typescript",
  mts: "typescript",
  py: "python",
  rb: "ruby",
  rs: "rust",
  sh: "bash",
  zsh: "bash",
  console: "shell",
  yml: "yaml",
  toml: "ini",
  conf: "ini",
  cfg: "ini",
  env: "ini",
  html: "xml",
  htm: "xml",
  svg: "xml",
  vue: "xml",
  md: "markdown",
  cs: "csharp",
  objc: "objectivec",
  "objective-c": "objectivec",
  ps1: "powershell",
  pwsh: "powershell",
  docker: "dockerfile",
  jsonc: "json",
  json5: "json",
  text: "plaintext",
  txt: "plaintext",
  plain: "plaintext",
  "c++": "cpp",
  cc: "cpp",
  cxx: "cpp",
  h: "c",
  hpp: "cpp",
  kt: "kotlin",
  kts: "kotlin",
};

function ensureRegistered(): void {
  if (registered) return;
  registered = true;
  hljs.registerLanguage("bash", bash);
  hljs.registerLanguage("c", c);
  hljs.registerLanguage("cpp", cpp);
  hljs.registerLanguage("csharp", csharp);
  hljs.registerLanguage("css", css);
  hljs.registerLanguage("diff", diff);
  hljs.registerLanguage("dockerfile", dockerfile);
  hljs.registerLanguage("go", go);
  hljs.registerLanguage("graphql", graphql);
  hljs.registerLanguage("http", http);
  hljs.registerLanguage("ini", ini);
  hljs.registerLanguage("java", java);
  hljs.registerLanguage("javascript", javascript);
  hljs.registerLanguage("json", json);
  hljs.registerLanguage("kotlin", kotlin);
  hljs.registerLanguage("lua", lua);
  hljs.registerLanguage("makefile", makefile);
  hljs.registerLanguage("markdown", markdown);
  hljs.registerLanguage("nginx", nginx);
  hljs.registerLanguage("objectivec", objectivec);
  hljs.registerLanguage("php", php);
  hljs.registerLanguage("plaintext", plaintext);
  hljs.registerLanguage("powershell", powershell);
  hljs.registerLanguage("python", python);
  hljs.registerLanguage("ruby", ruby);
  hljs.registerLanguage("rust", rust);
  hljs.registerLanguage("scss", scss);
  hljs.registerLanguage("shell", shell);
  hljs.registerLanguage("sql", sql);
  hljs.registerLanguage("swift", swift);
  hljs.registerLanguage("typescript", typescript);
  hljs.registerLanguage("xml", xml);
  hljs.registerLanguage("yaml", yaml);
}

const AUTO_SUBSET = [
  "json",
  "bash",
  "javascript",
  "typescript",
  "python",
  "yaml",
  "xml",
  "css",
  "sql",
  "go",
  "rust",
  "diff",
  "ini",
  "nginx",
];

function extractDeclaredLanguage(codeEl: HTMLElement): string | null {
  const cls = codeEl.className || "";
  const match = cls.match(/(?:^|\s)(?:lang|language)-([a-zA-Z0-9_+#-]+)/i);
  if (!match) return null;
  const raw = match[1].toLowerCase();
  if (raw === "auto" || raw === "nohighlight" || raw === "mermaid") return raw;
  return LANG_ALIAS_MAP[raw] || raw;
}

/**
 * Highlights a single `<code>` element inside `<pre>`.
 * Safe, idempotent (skips already highlighted blocks), and bounds massive pastes (>80KB) so UI never stalls.
 */
export function highlightCodeElement(codeEl: HTMLElement): void {
  if (codeEl.dataset.lindoHljs === "1") return;
  const rawText = codeEl.textContent || "";
  if (!rawText.trim() || rawText.length > 80_000) {
    codeEl.dataset.lindoHljs = "1";
    return;
  }

  ensureRegistered();
  const lang = extractDeclaredLanguage(codeEl);
  if (lang === "nohighlight" || lang === "mermaid") {
    codeEl.dataset.lindoHljs = "1";
    return;
  }

  try {
    if (lang && lang !== "auto" && hljs.getLanguage(lang)) {
      const res = hljs.highlight(rawText, { language: lang, ignoreIllegals: true });
      codeEl.innerHTML = res.value;
      codeEl.classList.add("hljs");
    } else if (lang === "auto" || !lang) {
      // Fast JSON fast-path (super common in Linux.do config tutorials like settings.json!)
      const trimmed = rawText.trim();
      if (
        (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"))
      ) {
        try {
          JSON.parse(trimmed);
          const res = hljs.highlight(rawText, { language: "json", ignoreIllegals: true });
          codeEl.innerHTML = res.value;
          codeEl.classList.add("hljs");
          codeEl.dataset.lindoHljs = "1";
          return;
        } catch {
          // fall through to auto
        }
      }
      const res = hljs.highlightAuto(rawText, AUTO_SUBSET);
      if (res.relevance >= 3) {
        codeEl.innerHTML = res.value;
        codeEl.classList.add("hljs");
      }
    }
  } catch {
    // Keep original plain text cleanly on unexpected input
  }
  codeEl.dataset.lindoHljs = "1";
}
