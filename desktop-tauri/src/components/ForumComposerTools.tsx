import { useEffect, useRef, useState } from "react";
import { fetchForumTemplates } from "../lib/api";
import type { ForumTemplate } from "../lib/composerTemplates";
import { chartMarkup, dateMarkup, forumMarkup, forumTools, wrapMarkup, type ForumTool, type MarkupTool, type ChartRow } from "../lib/forumComposerTools";

type Panel = "menu" | "templates" | "ai" | "date" | "wrap" | "chart" | "quote";
interface Props {
  initialPanel: "menu" | "templates" | "date";
  owner: string;
  canQuote: boolean;
  canInsertToc: boolean;
  canUseTemplates: boolean;
  readQuote: () => Promise<string>;
  selection: () => string;
  onInsert: (markup: string, inline?: boolean) => void;
  onTemplate: (template: ForumTemplate) => void;
  onExistingTool: (tool: "TABLE" | "POLL") => void;
}

const button = "lindo-interactive rounded-lg px-3 py-2 text-left text-[13px]";
const field = "rounded-lg border border-[var(--hairline)] bg-[var(--card-surface)] px-3 py-1.5 text-[13px] text-[var(--ink-primary)]";

export function ForumComposerTools({ initialPanel, owner, canQuote, canInsertToc, canUseTemplates, readQuote, selection, onInsert, onTemplate, onExistingTool }: Props) {
  const [panel, setPanel] = useState<Panel>(initialPanel);
  const [templates, setTemplates] = useState<ForumTemplate[] | null>(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const [search, setSearch] = useState("");
  const [name, setName] = useState("");
  const [attributes, setAttributes] = useState("");
  const [chartTitle, setChartTitle] = useState("");
  const [axis, setAxis] = useState("");
  const [rows, setRows] = useState<ChartRow[]>([{ label: "", value: "" }]);
  const [date, setDate] = useState(() => {
    const d = new Date();
    return [d.getFullYear(), String(d.getMonth() + 1).padStart(2, "0"), String(d.getDate()).padStart(2, "0")].join("-");
  });
  const [time, setTime] = useState("");
  const [zone, setZone] = useState(() => Intl.DateTimeFormat().resolvedOptions().timeZone);
  const latest = useRef({ readQuote, onInsert });
  latest.current = { readQuote, onInsert };

  useEffect(() => {
    if (panel !== "templates" && panel !== "quote") return;
    let active = true;
    setError("");
    if (panel === "templates") {
      setTemplates(null);
      fetchForumTemplates(owner).then(value => { if (active) setTemplates(value); })
        .catch(e => { if (active) setError(e.message || "模板加载失败"); });
    } else {
      latest.current.readQuote().then(value => { if (active) latest.current.onInsert(value); })
        .catch(e => { if (active) setError(e.message || "读取原帖失败"); });
    }
    return () => { active = false; };
  }, [panel, attempt, owner]);

  function insert(tool: MarkupTool) {
    onInsert(forumMarkup(tool, selection()), tool === "footnote" || tool === "spoiler");
  }

  function choose(tool: ForumTool) {
    if (tool === "table" || tool === "poll") return onExistingTool(tool === "table" ? "TABLE" : "POLL");
    if (tool === "ai" || tool === "templates" || tool === "wrap" || tool === "chart" || tool === "date" || tool === "quote") {
      setError("");
      setPanel(tool);
    } else insert(tool);
  }

  const term = search.trim().toLocaleLowerCase();
  const visible = templates?.filter(t => [t.title, ...t.tags, t.content].some(s => s.toLocaleLowerCase().includes(term)));
  const markup = panel === "chart" ? chartMarkup(chartTitle, axis, rows)
    : panel === "wrap" ? wrapMarkup(name.trim(), attributes, selection())
    : panel === "date" ? dateMarkup(date, time, zone.trim()) : null;
  return <section aria-label="更多创作工具" className="lindo-hairline-b max-h-[35vh] shrink-0 overflow-y-auto px-5 py-2 text-[var(--ink-primary)]" style={{ backgroundColor: "var(--subtle-surface)" }}>
    {panel !== "menu" && <div className="mb-2 flex items-center gap-3">
      <button type="button" className={button} onClick={() => { setPanel("menu"); setError(""); }}>‹ 返回</button>
      <strong className="text-sm">{forumTools[panel]}</strong>
    </div>}
    {panel === "menu" && <div className="grid grid-cols-2 gap-1 sm:grid-cols-4">
      {(Object.keys(forumTools) as ForumTool[]).map(key => <button key={key} type="button" className={button + " disabled:opacity-40"}
        disabled={(key === "quote" && !canQuote) || (key === "toc" && !canInsertToc) || (key === "templates" && !canUseTemplates)}
        onClick={() => choose(key)}>{forumTools[key]}</button>)}
    </div>}
    {panel === "ai" && <div className="flex flex-wrap">{([["aiQuote", "引用"], ["aiDetails", "折叠内容"], ["aiCode", "代码"]] as const).map(([tool, label]) =>
      <button type="button" key={tool} className={button} onClick={() => insert(tool)}>{label}</button>)}</div>}
    {panel === "templates" && <>
      <input autoFocus aria-label="搜索模板" placeholder="搜索模板" className={field + " w-full"} value={search} onChange={e => setSearch(e.target.value)} />
      {visible?.map(t => <button type="button" key={t.id} className={button + " block w-full"} onClick={() => onTemplate(t)}>
        <strong className="block">{t.title}</strong><span className="block truncate text-[var(--ink-secondary)]">{t.content}</span>
      </button>)}
      {visible?.length === 0 && <p role="status" className="py-2 text-sm">{templates?.length ? "没有匹配的模板" : "当前账号没有可用模板"}</p>}
    </>}
    {((panel === "templates" && !templates) || panel === "quote") && <p role="status" className="py-1 text-sm">
      {error || (panel === "quote" ? "正在读取原帖…" : "正在加载模板…")}
      {error && <button type="button" className={button} onClick={() => setAttempt(n => n + 1)}>重试</button>}
    </p>}
    {(panel === "chart" || panel === "wrap" || panel === "date") && <div className="flex flex-wrap items-center gap-2">
      {panel === "chart" && <>
        <input className={field} aria-label="图表标题" placeholder="图表标题（选填）" value={chartTitle} onChange={e => setChartTitle(e.target.value)} />
        <input className={field} aria-label="横轴标题" placeholder="横轴标题（选填）" value={axis} onChange={e => setAxis(e.target.value)} />
        <div className="w-full space-y-2">
          {rows.map((row, i) => <div key={i} className="flex items-center gap-2">
            <input className={field + " min-w-0 flex-1"} aria-label={`第 ${i + 1} 行名称`} placeholder="名称" value={row.label}
              onChange={e => setRows(rows.map((r, index) => index === i ? { ...r, label: e.target.value } : r))} />
            <input className={field + " w-24"} aria-label={`第 ${i + 1} 行数值`} placeholder="数值" inputMode="decimal" value={row.value}
              onChange={e => setRows(rows.map((r, index) => index === i ? { ...r, value: e.target.value } : r))} />
            {rows.length > 1 && <button type="button" className={button} aria-label={`删除第 ${i + 1} 行`} onClick={() => setRows(rows.filter((_, index) => index !== i))}>×</button>}
          </div>)}
          <button type="button" className={button} onClick={() => setRows([...rows, { label: "", value: "" }])}>＋ 添加一行</button>
        </div>
      </>}
      {panel === "wrap" && <>
        <input aria-label="包裹名称" className={field} placeholder="包裹名称" value={name} onChange={e => setName(e.target.value)} />
        <textarea aria-label="包裹属性" className={field} placeholder="属性（选填，每行 key=value）" value={attributes} onChange={e => setAttributes(e.target.value)} />
      </>}
      {panel === "date" && <>
        <input autoFocus aria-label="日期" className={field} type="date" value={date} onChange={e => setDate(e.target.value)} />
        <input aria-label="时间（选填）" className={field} type="time" value={time} onChange={e => setTime(e.target.value)} />
        <input aria-label="时区" className={field} value={zone} onChange={e => setZone(e.target.value)} />
      </>}
      <button type="button" className={button + " font-semibold disabled:opacity-40"} disabled={!markup} onClick={() => markup && onInsert(markup, panel === "date")}>插入</button>
    </div>}
  </section>;
}
