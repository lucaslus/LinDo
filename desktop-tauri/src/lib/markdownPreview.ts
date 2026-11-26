/**
 * Full Discourse-Flavored Markdown (GFM + Discourse BBCode) live preview renderer.
 * Produces semantic HTML styled cleanly by `.lindo-cooked` (zero external dependencies, O(N) block parser):
 * - Fenced code blocks, headings (#..######), horizontal rules
 * - GFM tables (with alignment, works even directly after lists/paragraphs)
 * - Unordered / ordered / nested lists & GFM task checklists (- [ ] / - [x])
 * - Multi-line blockquotes & callouts (> [!NOTE] / [!TIP] / [!WARNING])
 * - Discourse `[poll]...[/poll]` visual preview cards
 * - Discourse `[details="..."]...[/details]` interactive collapsible blocks
 * - Discourse `[quote="user, post:N, topic:T"]...[/quote]` quote cards
 * - Discourse `[spoiler]...[/spoiler]` (inline & block, clickable to reveal)
 * - Discourse images (`![alt|WxH, 50%](upload://...)`), local blob previews, uploading pills
 * - Inline code, bold, italic, strikethrough, links, bare URLs, `@mentions`, site shortcode emojis, hard breaks
 */

const SHORTCODE_TO_UNICODE: Record<string, string> = {
  distorted_face: "🫠",
  tada: "🎉",
  rocket: "🚀",
  fire: "🔥",
  eyes: "👀",
  thinking: "🤔",
  saluting_face: "🫡",
  partying_face: "🥳",
  "100": "💯",
  heart: "❤️",
  pray: "🙏",
  "+1": "👍",
  thumbsup: "👍",
  "-1": "👎",
  thumbsdown: "👎",
  smile: "😄",
  grinning: "😀",
  joy: "😂",
  rofl: "🤣",
  laughing: "😆",
  blush: "😊",
  heart_eyes: "😍",
  sunglasses: "😎",
  clap: "👏",
  confetti_ball: "🎉",
  hugs: "🤗",
  sob: "😭",
  sweat_smile: "😅",
  handshake: "🤝",
  sparkles: "✨",
  bulb: "💡",
  warning: "⚠️",
  white_check_mark: "✅",
  x: "❌",
  raised_hands: "🙌",
  muscle: "💪",
  dart: "🎯",
  brain: "🧠",
  zap: "⚡",
  whale: "🐳",
  penguin: "🐧",
  open_mouth: "😮",
};

const CUSTOM_SITE_EMOJI_IMG: Record<string, string> = {
  tieba_087: "https://linux.do/uploads/default/original/3X/2/e/2e092708f7b0a5e8f8a4b2520e3f452d62a3ef59.png",
};

export function registerCustomSiteEmojis(entries: Array<{ name: string; url: string }>): void {
  for (const item of entries) {
    const key = item.name.trim().toLowerCase().replace(/^:|:$/g, "");
    const url = item.url.trim();
    if (key && url) {
      CUSTOM_SITE_EMOJI_IMG[key] = url;
    }
  }
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function escapeAttr(text: string): string {
  return escapeHtml(text).replace(/'/g, "&#39;");
}

function sanitizeHref(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (/^(javascript|vbscript|data|file):/i.test(trimmed)) return null;
  if (trimmed.startsWith("//")) return `https:${trimmed}`;
  if (trimmed.startsWith("/")) return `https://linux.do${trimmed}`;
  if (/^(https?:|mailto:)/i.test(trimmed)) return trimmed;
  return null;
}

function resolvePreviewImageUrl(
  rawUrl: string,
  previewMap: Record<string, string>
): string {
  const trimmed = rawUrl.trim();
  if (previewMap[trimmed]) return previewMap[trimmed];
  if (trimmed.startsWith("upload://")) {
    return `https://linux.do/uploads/short-url/${trimmed.slice("upload://".length)}`;
  }
  if (trimmed.startsWith("//")) return `https:${trimmed}`;
  if (trimmed.startsWith("/")) return `https://linux.do${trimmed}`;
  return trimmed;
}

function renderInlineMarkdown(
  raw: string,
  previewMap: Record<string, string>
): string {
  if (!raw) return "";
  const tokens: string[] = [];
  const stash = (html: string) => {
    const id = tokens.length;
    tokens.push(html);
    return `\uE000T${id}\uE001`;
  };

  let s = raw;

  // 1. Inline code spans (protect contents from all further markdown parsing)
  s = s.replace(/``([^`\n]+?)``|`([^`\n]+?)`/g, (_m, dbl?: string, sgl?: string) => {
    const code = dbl !== undefined ? dbl.trim() : sgl ?? "";
    return stash(`<code>${escapeHtml(code)}</code>`);
  });

  // 1.5 Inline LaTeX math (`$E=mc^2$`) — avoids matching currency like `$100` or `$5 and $6`
  s = s.replace(/(^|[^\\$])\$(?!\s)([^$\n]+?)(?<!\s)\$(?!\d)/g, (_m, lead: string, tex: string) => {
    if (/^\d[\d,.\s]*$/.test(tex.trim())) return `${lead}$${tex}$`;
    return `${lead}${stash(`<span class="math">${escapeHtml(tex)}</span>`)}`;
  });

  // 2. Uploading inline placeholders
  s = s.replace(
    /!?\[([^\]]+)\]\((uploading-\d+)\)/g,
    (_m, label: string) =>
      stash(
        `<span style="display:inline-flex;align-items:center;padding:2px 8px;border-radius:6px;background:var(--subtle-surface);color:var(--active-chip-text);font-size:12px;">${escapeHtml(label)}</span>`
      )
  );

  // 3. Discourse images `![alt|WxH, 50%](src)`
  s = s.replace(/!\[([^\]]*)\]\(([^)\n]+)\)/g, (_m, rawAlt: string, rawSrc: string) => {
    const resolvedSrc = resolvePreviewImageUrl(rawSrc, previewMap);
    const parts = rawAlt.split("|");
    const altText = parts[0] || "";
    const meta = parts.slice(1).join("|");
    const pctMatch = meta.match(/(\d{2,3})%/);
    const dimMatch = meta.match(/(\d+)x(\d+)/);
    let style = "max-width:100%;height:auto;border-radius:8px;margin:6px 0;";
    if (pctMatch) {
      const pct = Math.max(15, Math.min(100, Number(pctMatch[1])));
      if (dimMatch) {
        const scaledW = Math.round((Number(dimMatch[1]) * pct) / 100);
        style = `width:${scaledW}px;max-width:${pct}%;height:auto;border-radius:8px;margin:6px 0;`;
      } else {
        style = `max-width:${pct}%;height:auto;border-radius:8px;margin:6px 0;`;
      }
    }
    return stash(
      `<img alt="${escapeAttr(altText)}" src="${escapeAttr(resolvedSrc)}" style="${style}" />`
    );
  });

  // 4. Markdown links `[label](url)` or `[label](<url>)`
  s = s.replace(/\[([^\]]+)\]\(<?([^)\s>]+)>?(?:\s+"([^"]*)")?\)/g, (_m, label: string, url: string, title?: string) => {
    const safe = sanitizeHref(url);
    if (!safe) return escapeHtml(label);
    const titleAttr = title ? ` title="${escapeAttr(title)}"` : "";
    const innerLabel = renderInlineMarkdown(label, previewMap);
    return stash(`<a href="${escapeAttr(safe)}"${titleAttr}>${innerLabel}</a>`);
  });

  // 4.5 Bare URLs (`https://...`) before escaping/formatting so `&`, `__`, and `:port:` stay intact
  s = s.replace(/(^|[\s(（【])((?:https?:\/\/)[^\s<>)\]}]+)/gi, (_m, lead: string, url: string) => {
    const cleaned = url.replace(/[.,;:!?。，；：！？]+$/, "");
    const trailing = url.slice(cleaned.length);
    const safe = sanitizeHref(cleaned);
    if (!safe) return `${lead}${url}`;
    return `${lead}${stash(`<a href="${escapeAttr(safe)}">${escapeHtml(cleaned)}</a>`)}${trailing}`;
  });

  // 5. Inline `<kbd>...</kbd>` tags before HTML escaping
  s = s.replace(/<kbd>([^<\n]{1,32})<\/kbd>/gi, (_m, key: string) =>
    stash(`<kbd>${escapeHtml(key)}</kbd>`)
  );

  // 6. Escape remaining raw HTML safely
  s = escapeHtml(s);

  // 7. Inline `[spoiler]...[/spoiler]` & `[color=...]...[/color]`
  s = s.replace(
    /\[spoiler\]([\s\S]*?)\[\/spoiler\]/gi,
    (_m, inner: string) => `<span class="spoiled" title="点击显示剧透">${inner}</span>`
  );
  s = s.replace(
    /\[color=([#a-zA-Z0-9(),.\s%-]{2,36})\]([\s\S]*?)\[\/color\]/gi,
    (_m, rawColor: string, inner: string) => {
      const c = rawColor.trim();
      if (/(url|expression|;|\\)/i.test(c)) return inner;
      return `<span data-bbcode-color="${escapeAttr(c)}" data-has-custom-color="1" style="color:${escapeAttr(c)};">${inner}</span>`;
    }
  );
  s = s.replace(
    /\[bgcolor=([#a-zA-Z0-9(),.\s%-]{2,36})\]([\s\S]*?)\[\/bgcolor\]/gi,
    (_m, rawColor: string, inner: string) => {
      const c = rawColor.trim();
      if (/(url|expression|;|\\)/i.test(c)) return inner;
      return `<span data-bbcode-bgcolor="${escapeAttr(c)}" data-has-custom-bgcolor="1" style="background-color:${escapeAttr(c)};padding:0.08em 0.32em;border-radius:4px;">${inner}</span>`;
    }
  );

  // 8. Bold + Italic, Bold, Italic, Strikethrough
  s = s
    .replace(/\*\*\*([^*\n]+?)\*\*\*/g, "<strong><em>$1</em></strong>")
    .replace(/\*\*([^*\n]+?)\*\*/g, "<strong>$1</strong>")
    .replace(/__([^_\n]+?)__/g, "<strong>$1</strong>")
    .replace(/~~([^~\n]+?)~~/g, "<del>$1</del>")
    .replace(/(^|[^\w*])\*([^*\n]+?)\*(?!\*)/g, "$1<em>$2</em>");

  // 9. Discourse shortcode emojis (`:rocket:`, `:tieba_087:`, etc.)
  s = s.replace(/:([a-z0-9_+-]+):/gi, (full, rawCode: string) => {
    const code = rawCode.toLowerCase();
    if (SHORTCODE_TO_UNICODE[code]) {
      return SHORTCODE_TO_UNICODE[code];
    }
    if (CUSTOM_SITE_EMOJI_IMG[code]) {
      return stash(
        `<img class="emoji" alt=":${escapeAttr(code)}:" title=":${escapeAttr(code)}:" src="${escapeAttr(CUSTOM_SITE_EMOJI_IMG[code])}" />`
      );
    }
    return full;
  });

  // 10. Discourse `@mentions`
  s = s.replace(/(^|[\s(（【,，])@([A-Za-z0-9_.-]{2,32})/g, (_m, lead: string, uname: string) => {
    return `${lead}<a class="mention" href="https://linux.do/u/${encodeURIComponent(uname)}">@${uname}</a>`;
  });

  // 11. CommonMark trailing backslash hard line break (`\`) -> strip literal backslash at end of line
  s = s.replace(/\\\s*$/, "");

  // Restore stashed HTML tokens (iterate twice in case nested inside link label)
  for (let pass = 0; pass < 2; pass++) {
    s = s.replace(/\uE000T(\d+)\uE001/g, (_m, idx: string) => tokens[Number(idx)] ?? "");
  }
  return s;
}

function splitTableRow(line: string): string[] {
  let trimmed = line.trim();
  if (trimmed.startsWith("|")) trimmed = trimmed.slice(1);
  if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.slice(0, -1);
  const cells: string[] = [];
  let cur = "";
  for (let i = 0; i < trimmed.length; i++) {
    if (trimmed[i] === "\\" && i + 1 < trimmed.length && trimmed[i + 1] === "|") {
      cur += "|";
      i++;
    } else if (trimmed[i] === "|") {
      cells.push(cur.trim());
      cur = "";
    } else {
      cur += trimmed[i];
    }
  }
  cells.push(cur.trim());
  return cells;
}

function parseTableAlignments(sepLine: string): Array<"left" | "center" | "right" | null> | null {
  const trimmed = sepLine.trim();
  if (!trimmed.includes("|") || !trimmed.includes("-")) return null;
  const cells = splitTableRow(trimmed);
  if (cells.length === 0) return null;
  const aligns: Array<"left" | "center" | "right" | null> = [];
  for (const c of cells) {
    if (!/^:?-{1,}:?$/.test(c)) return null;
    if (c.startsWith(":") && c.endsWith(":")) aligns.push("center");
    else if (c.endsWith(":")) aligns.push("right");
    else if (c.startsWith(":")) aligns.push("left");
    else aligns.push(null);
  }
  return aligns;
}

function isTableStart(lines: string[], idx: number): boolean {
  if (idx + 1 >= lines.length) return false;
  const header = lines[idx].trim();
  if (!header.includes("|")) return false;
  const aligns = parseTableAlignments(lines[idx + 1]);
  if (!aligns) return false;
  const headerCells = splitTableRow(header);
  return headerCells.length >= 1 && Math.abs(headerCells.length - aligns.length) <= 1;
}

function isBlockBoundary(lines: string[], idx: number): boolean {
  const line = lines[idx];
  const trimmed = line.trim();
  if (!trimmed) return true;
  if (/^(`{3,}|~{3,})/.test(trimmed)) return true;
  if (/^#{1,6}\s+/.test(trimmed)) return true;
  if (/^(?:-{3,}|\*{3,}|_{3,})\s*$/.test(trimmed)) return true;
  if (/^>\s?/.test(trimmed)) return true;
  if (/^(\s*)([-*+])\s+/.test(line)) return true;
  if (/^(\s*)(\d+)\.\s+/.test(line)) return true;
  if (/^\[(?:poll|details|quote|spoiler)\b/i.test(trimmed)) return true;
  if (isTableStart(lines, idx)) return true;
  return false;
}

function parsePollPreviewBlock(
  headerLine: string,
  bodyLines: string[],
  previewMap: Record<string, string>
): string {
  const isMultiple = /\btype\s*=\s*["']?multiple/i.test(headerLine);
  const titleMatch = headerLine.match(/\btitle\s*=\s*["']([^"']+)["']/i);
  const pollTitle = titleMatch?.[1]?.trim() || (isMultiple ? "多选投票" : "单选投票");
  const options = bodyLines
    .map((l) => l.trim())
    .filter((l) => /^[-*+]\s+/.test(l))
    .map((l) => l.replace(/^[-*+]\s+/, "").trim())
    .filter(Boolean);

  const itemsHtml =
    options.length > 0
      ? options
          .map(
            (opt) =>
              `<div class="lindo-preview-poll-opt"><span class="lindo-preview-poll-mark ${isMultiple ? "is-multi" : ""}"></span><span>${renderInlineMarkdown(opt, previewMap)}</span></div>`
          )
          .join("")
      : `<div class="lindo-preview-poll-empty">请在投票块内添加 <code>* 选项</code></div>`;

  return `<div class="lindo-preview-poll"><div class="lindo-preview-poll-head"><span>${escapeHtml(pollTitle)}</span><span class="lindo-preview-poll-badge">${isMultiple ? "多选" : "单选"} · 预览</span></div><div class="lindo-preview-poll-list">${itemsHtml}</div></div>`;
}

function parseQuoteHeader(openLine: string): { username: string; postNum?: number } | null {
  const m = openLine.match(/^\[quote(?:=(?:&quot;|"|')?([^\]"']+)(?:&quot;|"|')?)?\]/i);
  if (!m || !m[1]) return null;
  const rawMeta = m[1].trim();
  const parts = rawMeta.split(",").map((p) => p.trim());
  const username = parts[0] || "";
  let postNum: number | undefined;
  for (const p of parts.slice(1)) {
    const pm = p.match(/^post\s*:\s*(\d+)/i);
    if (pm) postNum = Number(pm[1]);
  }
  return username ? { username, postNum } : null;
}

function parseMarkdownBlocks(
  lines: string[],
  previewMap: Record<string, string>,
  depth = 0
): string {
  if (depth > 8) {
    return `<p>${lines.map((l) => renderInlineMarkdown(l, previewMap)).join("<br />")}</p>`;
  }

  const out: string[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];
    const trimmed = line.trim();

    if (!trimmed) {
      i++;
      continue;
    }

    // 1. Fenced code block (``` or ~~~)
    const fenceMatch = trimmed.match(/^(`{3,}|~{3,})\s*([a-zA-Z0-9_+-]*)/);
    if (fenceMatch) {
      const fenceChar = fenceMatch[1][0];
      const fenceLen = fenceMatch[1].length;
      const lang = fenceMatch[2] || "";
      i++;
      const codeLines: string[] = [];
      const closeRe = new RegExp(`^${fenceChar}{${fenceLen},}\\s*$`);
      while (i < lines.length && !closeRe.test(lines[i].trim())) {
        codeLines.push(lines[i]);
        i++;
      }
      if (i < lines.length) i++;
      const cls = lang ? ` class="lang-${escapeAttr(lang)}"` : "";
      out.push(`<pre><code${cls}>${escapeHtml(codeLines.join("\n"))}</code></pre>`);
      continue;
    }

    // 1.5 Block LaTeX math (`$$ ... $$`)
    if (/^\$\$/.test(trimmed)) {
      if (trimmed.length > 4 && trimmed.endsWith("$$")) {
        const inner = trimmed.slice(2, -2).trim();
        out.push(`<div class="math" data-math-display="1">${escapeHtml(inner)}</div>`);
        i++;
        continue;
      }
      const mathLines: string[] = [];
      const firstAfter = trimmed.slice(2).trim();
      if (firstAfter) mathLines.push(firstAfter);
      i++;
      while (i < lines.length && !lines[i].trim().endsWith("$$")) {
        mathLines.push(lines[i]);
        i++;
      }
      if (i < lines.length) {
        const tail = lines[i].trim().slice(0, -2).trim();
        if (tail) mathLines.push(tail);
        i++;
      }
      out.push(`<div class="math" data-math-display="1">${escapeHtml(mathLines.join("\n"))}</div>`);
      continue;
    }

    // 2. Discourse `[poll ...]...[/poll]`
    if (/^\[poll\b[^\]]*\]/i.test(trimmed)) {
      const openLine = trimmed;
      const bodyLines: string[] = [];
      // Single-line `[poll]...[/poll]`
      if (/\[\/poll\]/i.test(openLine)) {
        const inner = openLine.replace(/^\[poll\b[^\]]*\]/i, "").replace(/\[\/poll\].*$/i, "");
        if (inner.trim()) bodyLines.push(inner.trim());
        i++;
      } else {
        i++;
        while (i < lines.length && !/^\[\/poll\]\s*$/i.test(lines[i].trim())) {
          bodyLines.push(lines[i]);
          i++;
        }
        if (i < lines.length) i++;
      }
      out.push(parsePollPreviewBlock(openLine, bodyLines, previewMap));
      continue;
    }

    // 3. Discourse `[details="..."]...[/details]`
    const detailsOpen = trimmed.match(/^\[details(?:=(?:"([^"]*)"|'([^']*)'|([^\]]*)))?\]/i);
    if (detailsOpen) {
      const summaryText = (detailsOpen[1] ?? detailsOpen[2] ?? detailsOpen[3] ?? "摘要").trim() || "摘要";
      const bodyLines: string[] = [];
      const trailingOnOpen = trimmed.slice(detailsOpen[0].length);
      if (/\[\/details\]/i.test(trailingOnOpen)) {
        bodyLines.push(trailingOnOpen.replace(/\[\/details\][\s\S]*$/i, ""));
        i++;
      } else {
        if (trailingOnOpen.trim()) bodyLines.push(trailingOnOpen);
        i++;
        while (i < lines.length && !/^\[\/details\]\s*$/i.test(lines[i].trim())) {
          bodyLines.push(lines[i]);
          i++;
        }
        if (i < lines.length) i++;
      }
      const innerHtml = parseMarkdownBlocks(bodyLines, previewMap, depth + 1);
      out.push(`<details open><summary>${escapeHtml(summaryText)}</summary><div>${innerHtml}</div></details>`);
      continue;
    }

    // 4. Discourse `[quote=...]...[/quote]`
    const quoteOpen = trimmed.match(/^\[quote(?:\s*=[^\]]*)?\]/i);
    if (quoteOpen) {
      const meta = parseQuoteHeader(trimmed);
      const bodyLines: string[] = [];
      const trailingOnOpen = trimmed.slice(quoteOpen[0].length);
      if (/\[\/quote\]/i.test(trailingOnOpen)) {
        bodyLines.push(trailingOnOpen.replace(/\[\/quote\][\s\S]*$/i, ""));
        i++;
      } else {
        if (trailingOnOpen.trim()) bodyLines.push(trailingOnOpen);
        i++;
        while (i < lines.length && !/^\[\/quote\]\s*$/i.test(lines[i].trim())) {
          bodyLines.push(lines[i]);
          i++;
        }
        if (i < lines.length) i++;
      }
      const innerHtml = parseMarkdownBlocks(bodyLines, previewMap, depth + 1);
      if (meta) {
        const floorBadge = meta.postNum ? `<span style="margin-left:auto;color:var(--ink-tertiary);font-size:11.5px;font-weight:500;">#${meta.postNum} 楼</span>` : "";
        out.push(
          `<aside class="quote"><div class="title"><span>${escapeHtml(meta.username)}</span>${floorBadge}</div><blockquote>${innerHtml}</blockquote></aside>`
        );
      } else {
        out.push(`<blockquote>${innerHtml}</blockquote>`);
      }
      continue;
    }

    // 5. Block `[spoiler]...[/spoiler]`
    if (/^\[spoiler\]\s*$/i.test(trimmed)) {
      i++;
      const bodyLines: string[] = [];
      while (i < lines.length && !/^\[\/spoiler\]\s*$/i.test(lines[i].trim())) {
        bodyLines.push(lines[i]);
        i++;
      }
      if (i < lines.length) i++;
      const innerHtml = parseMarkdownBlocks(bodyLines, previewMap, depth + 1);
      out.push(`<div class="spoiled" title="点击显示剧透">${innerHtml}</div>`);
      continue;
    }

    // 6. ATX Headings (`#`..`######`)
    const headingMatch = trimmed.match(/^(#{1,6})\s+(.+?)\s*#*$/);
    if (headingMatch) {
      const lvl = headingMatch[1].length;
      out.push(`<h${lvl}>${renderInlineMarkdown(headingMatch[2], previewMap)}</h${lvl}>`);
      i++;
      continue;
    }

    // 7. Horizontal rule (`---`, `***`, `___`)
    if (/^(?:-{3,}|\*{3,}|_{3,})\s*$/.test(trimmed)) {
      out.push("<hr />");
      i++;
      continue;
    }

    // 8. GFM Table (`| col1 | col2 |\n| --- | --- |`)
    if (isTableStart(lines, i)) {
      const headers = splitTableRow(lines[i]);
      const aligns = parseTableAlignments(lines[i + 1]) || [];
      i += 2;
      const bodyRows: string[][] = [];
      while (i < lines.length) {
        const rowTrim = lines[i].trim();
        if (!rowTrim || !rowTrim.includes("|")) break;
        if (/^(`{3,}|~{3,}|#{1,6}\s|\[(?:poll|details|quote|spoiler)\b)/i.test(rowTrim)) break;
        bodyRows.push(splitTableRow(lines[i]));
        i++;
      }
      const colCount = Math.max(headers.length, aligns.length);
      const thHtml = Array.from({ length: colCount }, (_, colIdx) => {
        const align = aligns[colIdx];
        const alignAttr = align ? ` style="text-align:${align};"` : "";
        return `<th${alignAttr}>${renderInlineMarkdown(headers[colIdx] ?? "", previewMap)}</th>`;
      }).join("");
      const trsHtml = bodyRows
        .map((row) => {
          const tds = Array.from({ length: colCount }, (_, colIdx) => {
            const align = aligns[colIdx];
            const alignAttr = align ? ` style="text-align:${align};"` : "";
            return `<td${alignAttr}>${renderInlineMarkdown(row[colIdx] ?? "", previewMap)}</td>`;
          }).join("");
          return `<tr>${tds}</tr>`;
        })
        .join("");
      out.push(`<table><thead><tr>${thHtml}</tr></thead>${trsHtml ? `<tbody>${trsHtml}</tbody>` : ""}</table>`);
      continue;
    }

    // 9. Blockquotes (`> ...`, merging consecutive lines & supporting Obsidian `[!Note]+ title` callouts)
    if (/^>\s?/.test(trimmed)) {
      const quoteLines: string[] = [];
      while (i < lines.length && /^>\s?/.test(lines[i].trim())) {
        quoteLines.push(lines[i].trim().replace(/^>\s?/, ""));
        i++;
      }
      const alertMatch = quoteLines[0]?.match(/^\[!([a-zA-Z0-9_-]+)\]([+-])?(?:[ \t]+(.*))?$/);
      if (alertMatch) {
        const rawKind = alertMatch[1].toLowerCase();
        const fold = alertMatch[2] || "";
        const customTitle = (alertMatch[3] || "").trim();
        const toneMap: Record<string, { tone: string; label: string }> = {
          note: { tone: "note", label: "说明" },
          info: { tone: "note", label: "信息" },
          todo: { tone: "note", label: "待办" },
          tip: { tone: "tip", label: "提示" },
          hint: { tone: "tip", label: "提示" },
          important: { tone: "tip", label: "重要" },
          abstract: { tone: "tip", label: "摘要" },
          summary: { tone: "tip", label: "摘要" },
          success: { tone: "success", label: "成功" },
          check: { tone: "success", label: "通过" },
          done: { tone: "success", label: "完成" },
          warning: { tone: "warning", label: "注意" },
          caution: { tone: "warning", label: "谨慎" },
          question: { tone: "warning", label: "疑问" },
          danger: { tone: "danger", label: "危险" },
          error: { tone: "danger", label: "错误" },
          bug: { tone: "danger", label: "Bug" },
          example: { tone: "example", label: "示例" },
          quote: { tone: "example", label: "引用" },
        };
        const info = toneMap[rawKind] || { tone: "note", label: alertMatch[1] };
        const titleHtml = customTitle ? renderInlineMarkdown(customTitle, previewMap) : escapeHtml(info.label);
        const innerHtml = parseMarkdownBlocks(quoteLines.slice(1), previewMap, depth + 1);
        const isCollapsible = fold === "+" || fold === "-";
        const chevron = isCollapsible
          ? '<svg class="lindo-callout-chevron" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="m6 9 6 6 6-6"/></svg>'
          : "";
        if (isCollapsible) {
          const openAttr = fold === "+" ? " open" : "";
          out.push(
            `<details${openAttr} class="lindo-callout lindo-callout-${info.tone}" data-callout="${escapeAttr(rawKind)}"><summary class="lindo-callout-title"><span class="lindo-callout-title-text">${titleHtml}</span>${chevron}</summary><div class="lindo-callout-body">${innerHtml}</div></details>`
          );
        } else {
          out.push(
            `<div class="lindo-callout lindo-callout-${info.tone}" data-callout="${escapeAttr(rawKind)}"><div class="lindo-callout-title"><span class="lindo-callout-title-text">${titleHtml}</span></div><div class="lindo-callout-body">${innerHtml}</div></div>`
          );
        }
      } else {
        const innerHtml = parseMarkdownBlocks(quoteLines, previewMap, depth + 1);
        out.push(`<blockquote>${innerHtml}</blockquote>`);
      }
      continue;
    }

    // 10. Unordered & Ordered Lists (including GFM task checkboxes `- [ ]` / `- [x]` and 1-level nested items)
    const ulFirst = line.match(/^(\s*)([-*+])\s+(.*)$/);
    const olFirst = line.match(/^(\s*)(\d+)\.\s+(.*)$/);
    if (ulFirst || olFirst) {
      const isOrdered = Boolean(olFirst && !ulFirst);
      const baseIndent = (isOrdered ? olFirst![1] : ulFirst![1]).length;
      const startNum = isOrdered ? Number(olFirst![2]) : 1;
      const items: Array<{ lines: string[]; checked: boolean | null }> = [];

      while (i < lines.length) {
        const curLine = lines[i];
        const curTrim = curLine.trim();
        if (!curTrim) {
          // Blank line: continue list only if next line is another list item at same indent
          if (i + 1 < lines.length) {
            const nextSame = isOrdered
              ? lines[i + 1].match(/^(\s*)(\d+)\.\s+/)
              : lines[i + 1].match(/^(\s*)([-*+])\s+/);
            if (nextSame && nextSame[1].length === baseIndent) {
              i++;
              continue;
            }
          }
          break;
        }
        // Stop list immediately if a table, fence, heading, or Discourse tag begins
        if (isTableStart(lines, i) || /^(`{3,}|~{3,}|#{1,6}\s|\[(?:poll|details|quote|spoiler)\b)/i.test(curTrim)) {
          break;
        }

        const curMatch = isOrdered
          ? curLine.match(/^(\s*)(\d+)\.\s+(.*)$/)
          : curLine.match(/^(\s*)([-*+])\s+(.*)$/);

        if (curMatch && curMatch[1].length === baseIndent) {
          let content = curMatch[3];
          let checked: boolean | null = null;
          if (!isOrdered) {
            const taskMatch = content.match(/^\[([ xX])\]\s+(.*)$/);
            if (taskMatch) {
              checked = taskMatch[1].toLowerCase() === "x";
              content = taskMatch[2];
            }
          }
          items.push({ lines: [content], checked });
          i++;
        } else if (
          items.length > 0 &&
          (curLine.match(/^(\s+)/)?.[1].length ?? 0) > baseIndent
        ) {
          // Nested sub-list or indented continuation line
          const curIndent = curLine.match(/^(\s+)/)?.[1].length ?? 0;
          items[items.length - 1].lines.push(curLine.slice(Math.min(curIndent, baseIndent + 2)));
          i++;
        } else {
          break;
        }
      }

      const tag = isOrdered ? "ol" : "ul";
      const startAttr = isOrdered && startNum !== 1 ? ` start="${startNum}"` : "";
      const hasChecklist = items.some((it) => it.checked !== null);
      const listCls = hasChecklist ? ' class="lindo-task-list"' : "";
      const itemsHtml = items
        .map((it) => {
          const hasSubList = it.lines.length > 1 && it.lines.slice(1).some((l) => /^\s*(?:[-*+]|\d+\.)\s+/.test(l));
          const bodyHtml = hasSubList
            ? `${renderInlineMarkdown(it.lines[0], previewMap)}${parseMarkdownBlocks(it.lines.slice(1), previewMap, depth + 1)}`
            : it.lines.map((l) => renderInlineMarkdown(l, previewMap)).join("<br />");
          if (it.checked !== null) {
            const box = it.checked
              ? '<span class="lindo-task-box is-checked" aria-hidden="true">✓</span>'
              : '<span class="lindo-task-box" aria-hidden="true"></span>';
            return `<li class="lindo-task-item">${box}<span>${bodyHtml}</span></li>`;
          }
          return `<li>${bodyHtml}</li>`;
        })
        .join("");

      out.push(`<${tag}${startAttr}${listCls}>${itemsHtml}</${tag}>`);
      continue;
    }

    // 11. Paragraph (collect consecutive non-boundary lines; Discourse breaks: true renders single \n as <br />)
    const paraLines: string[] = [line];
    i++;
    while (i < lines.length && !isBlockBoundary(lines, i)) {
      paraLines.push(lines[i]);
      i++;
    }
    const paraHtml = paraLines.map((l) => renderInlineMarkdown(l, previewMap)).join("<br />");
    out.push(`<p>${paraHtml}</p>`);
  }

  return out.join("\n");
}

export function renderDiscourseMarkdownPreview(
  md: string,
  previewMap: Record<string, string> = {}
): string {
  if (!md || !md.trim()) return "";
  const normalized = md.replace(/\r\n?/g, "\n");
  return parseMarkdownBlocks(normalized.split("\n"), previewMap, 0);
}
