export const forumTools = {
  quote: "引用整个帖子", table: "插入表格", ai: "标记 AI 内容", toc: "插入目录",
  scroll: "插入滚动内容", mermaid: "Mermaid 图表", chart: "构建图表", details: "隐藏详细信息",
  graphviz: "Graphviz 图表", date: "插入日期/时间", math: "插入公式", templates: "插入模板",
  footnote: "添加脚注", spoiler: "模糊剧透", poll: "构建投票", wrap: "应用包裹（wrap）",
} as const;
export type ForumTool = keyof typeof forumTools;
export type MarkupTool = "toc" | "scroll" | "mermaid" | "details" | "graphviz" | "math" | "footnote" | "spoiler" | "aiQuote" | "aiCode" | "aiDetails";

export function forumMarkup(tool: MarkupTool, selection: string): string {
  const body = selection || "内容";
  switch (tool) {
    case "toc": return '<div data-theme-toc="true">' + (selection || " ") + '</div>';
    case "scroll": return '<div data-theme-scrollable="true">\n\n' + body + '\n\n</div>';
    case "mermaid": return "```mermaid\n" + (selection || "graph TD\n    A[开始] --> B[结束]") + "\n```";
    case "details": return '[details="摘要"]\n' + body + '\n[/details]';
    case "graphviz": return "[graphviz]\n" + (selection || "digraph { A -> B }") + "\n[/graphviz]";
    case "math": return "$$\n" + (selection || "x^2") + "\n$$";
    case "footnote": return "^[" + (selection || "脚注内容") + "]";
    case "spoiler": return "[spoiler]" + body + "[/spoiler]";
    case "aiQuote": return "> [!AI]\n" + body.split("\n").map(line => "> " + line).join("\n");
    case "aiDetails": return '[details="摘要" ai]\n' + body + '\n[/details]';
    case "aiCode": {
      const longest = Math.max(2, ...(body.match(/`+/g) || []).map(s => s.length));
      const fence = "`".repeat(longest + 1);
      return fence + " text ai\n" + body + "\n" + fence;
    }
  }
}

export type ChartRow = { label: string; value: string };

export function chartMarkup(title: string, axis: string, rows: ChartRow[]): string | null {
  const attributes = ["type='horizontalBar'"];
  for (const [name, value] of [["title", title.trim()], ["xAxisTitle", axis.trim()]]) {
    if (/[\[\]\r\n]/.test(value) || (value.includes("'") && value.includes('"'))) return null;
    const quote = value.includes("'") ? '"' : "'";
    if (value) attributes.push(name + "=" + quote + value + quote);
  }
  const data = rows.map(row => ({ label: row.label.trim(), value: row.value.trim() })).filter(row => row.label || row.value);
  if (!data.length || data.some(({ label, value }) => !label || /[|\[\]\n\r]/.test(label) || !/^[+-]?(?:\d+(?:\.\d+)?|\.\d+)$/.test(value))) return null;
  return "[chart " + attributes.join(" ") + "]\n" + data.map(({ label, value }) => label + " | " + value).join("\n") + "\n[/chart]";
}

export function wrapMarkup(name: string, attributes: string, content: string): string | null {
  const simpleName = /^[\w-]+$/;
  if (name && !simpleName.test(name)) return null;
  const attrs: string[] = [];
  for (const pair of attributes.split("\n").filter(line => line.trim())) {
    const match = /^([\w-]+)\s*=\s*(.+)$/.exec(pair.trim());
    if (!match || /["\[\]\\]/.test(match[2]) || !match[2].trim()) return null;
    attrs.push(match[1] + '="' + match[2].trim() + '"');
  }
  if (!name && !attrs.length) return null;
  return "[wrap" + (name ? "=" + name : "") + (attrs.length ? " " + attrs.join(" ") : "") + "]\n" + (content || "内容") + "\n[/wrap]";
}
export function dateMarkup(date: string, time: string, zone: string): string | null {
  if (!/^\d{4}-\d\d-\d\d$/.test(date) || (time && !/^([01]\d|2[0-3]):[0-5]\d$/.test(time))) return null;
  const day = new Date(date + "T00:00:00Z");
  if (!Number.isFinite(day.getTime()) || day.toISOString().slice(0, 10) !== date) return null;
  try { new Intl.DateTimeFormat("en", { timeZone: zone }); } catch { return null; }
  return "[date=" + date + (time ? " time=" + time + ":00" : "") + ' timezone="' + zone + '"]';
}
