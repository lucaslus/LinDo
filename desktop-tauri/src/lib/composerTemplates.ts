export interface ForumTemplate {
  id: number;
  title: string;
  content: string;
  tags: string[];
  usages: number;
}

const variables = new Set([
  "my_username", "my_name", "context_title", "context_url", "topic_title", "topic_url",
  "original_poster_username", "original_poster_name", "reply_to_username", "reply_to_name",
  "last_poster_username", "reply_to_or_last_poster_username", "chat_channel_name",
  "chat_channel_url", "chat_thread_name", "chat_thread_url",
]);

export function expandForumTemplate(text: string, values: Record<string, string | null | undefined>) {
  return text.replace(/%\{(\w+)(?:,fallback:([^}]*))?}/g, (token, key: string, fallback?: string) =>
    variables.has(key) ? values[key] || fallback || "" : token);
}

export function parseForumTemplates(data: { templates?: unknown } | null): ForumTemplate[] {
  if (!Array.isArray(data?.templates)) throw new Error("模板列表格式有误");
  return data.templates.flatMap((row) => {
    if (!row || !Number.isInteger(row.id) || row.id <= 0 || typeof row.title !== "string" || typeof row.content !== "string") return [];
    return [{
      id: row.id, title: row.title, content: row.content,
      tags: Array.isArray(row.tags) ? row.tags.filter((t: unknown) => typeof t === "string") : [],
      usages: Number.isFinite(row.usages) ? row.usages : 0,
    }];
  }).sort((a, b) => b.usages - a.usages || a.title.localeCompare(b.title));
}
