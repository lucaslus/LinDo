/**
 * Strictly lazy-loaded KaTeX (LaTeX math) and Mermaid diagram renderers.
 * Neither bundle is loaded until a post or composer preview actually contains math or Mermaid blocks.
 */

let katexPromise: Promise<typeof import("katex")> | null = null;
let mermaidPromise: Promise<typeof import("mermaid")> | null = null;
let mermaidSeq = 0;

async function loadKatex() {
  if (!katexPromise) {
    katexPromise = Promise.all([
      import("katex"),
      import("katex/dist/katex.min.css"),
    ]).then(([mod]) => mod);
  }
  return katexPromise;
}

async function loadMermaid() {
  if (!mermaidPromise) {
    mermaidPromise = import("mermaid");
  }
  return mermaidPromise;
}

/**
 * Renders a Discourse `.math` element (inline `<span>` or block `<div>`) using KaTeX.
 */
export async function renderMathElement(el: HTMLElement): Promise<void> {
  if (el.dataset.lindoMath === "1") return;
  const rawTex = (el.dataset.lindoRawTex || el.textContent || "").trim();
  if (!rawTex) {
    el.dataset.lindoMath = "1";
    return;
  }
  el.dataset.lindoRawTex = rawTex;

  const isDisplay =
    el.tagName === "DIV" ||
    el.classList.contains("math-block") ||
    el.dataset.mathDisplay === "1";

  // Strip surrounding $ or $$ delimiters if Discourse left them intact
  const cleaned = rawTex
    .replace(/^\$\$([\s\S]*)\$\$$/, "$1")
    .replace(/^\$([\s\S]*)\$$/, "$1")
    .trim();

  try {
    const mod = await loadKatex();
    const katex = mod.default || mod;
    const html = katex.renderToString(cleaned, {
      displayMode: isDisplay,
      throwOnError: false,
      strict: "ignore",
      trust: false,
      maxSize: 25,
      maxExpand: 200,
    });
    el.innerHTML = html;
    el.classList.add("lindo-math-rendered");
  } catch {
    // Graceful fallback: keep raw TeX readable
  }
  el.dataset.lindoMath = "1";
}

/**
 * Renders a Discourse Mermaid `<pre class="mermaid">` or `<pre><code class="lang-mermaid">` block
 * into a theme-aware SVG card (with graceful fallback to source code on syntax error).
 */
export async function renderMermaidBlock(preEl: HTMLElement): Promise<void> {
  if (preEl.dataset.lindoMermaid === "1") return;
  const codeEl = preEl.querySelector("code");
  const source = (preEl.dataset.lindoMermaidSrc || codeEl?.textContent || preEl.textContent || "").trim();
  if (!source || source.length > 30_000) {
    preEl.dataset.lindoMermaid = "1";
    return;
  }
  preEl.dataset.lindoMermaidSrc = source;

  const isDark =
    typeof document === "undefined" ||
    document.documentElement.classList.contains("dark") ||
    getComputedStyle(document.documentElement).colorScheme !== "light";

  try {
    const mod = await loadMermaid();
    const mermaid = mod.default || mod;
    mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: isDark ? "dark" : "default",
      fontFamily: "var(--font-sans)",
      themeVariables: isDark
        ? {
            background: "transparent",
            primaryColor: "#232a3b",
            primaryTextColor: "#dadbdf",
            primaryBorderColor: "#4b5568",
            lineColor: "#8b93a6",
            secondaryColor: "#1b202b",
            tertiaryColor: "#161a23",
          }
        : undefined,
    });

    mermaidSeq += 1;
    const id = `lindo-mermaid-${Date.now()}-${mermaidSeq}`;
    const { svg } = await mermaid.render(id, source);
    const wrapper = document.createElement("div");
    wrapper.className = "lindo-mermaid-card";
    wrapper.dataset.lindoMermaid = "1";
    wrapper.innerHTML = svg;
    // Preserve Mermaid's natural viewBox dimensions so small diagrams stay crisp instead of stretching to 100% width,
    // while large diagrams still scale down responsively to `max-width: 100%`.
    const svgEl = wrapper.querySelector("svg");
    if (svgEl) {
      const vb = svgEl.getAttribute("viewBox")?.trim().split(/[\s,]+/).map(Number);
      const vbW = vb && vb.length === 4 && Number.isFinite(vb[2]) && vb[2] > 0 ? vb[2] : null;
      svgEl.removeAttribute("height");
      if (vbW) {
        svgEl.style.width = `${Math.round(vbW)}px`;
      } else {
        svgEl.style.width = "auto";
      }
      svgEl.style.maxWidth = "100%";
      svgEl.style.height = "auto";
      svgEl.style.display = "inline-block";
    }
    preEl.replaceWith(wrapper);
  } catch {
    preEl.dataset.lindoMermaid = "1";
  }
}
