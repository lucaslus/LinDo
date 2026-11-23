export interface SearchableHistoryItem {
  topicId: number;
  title: string;
  categoryName?: string;
  categoryColorHex?: string;
  authorUsername: string;
  authorName?: string;
  authorAvatar: string;
  replyCount: number;
  timeIso: string;
  resumeFloor?: number;
  tags?: string[];
  fromCloudSearch?: boolean;
}

/**
 * 构造 Discourse 官方「仅搜已阅历史帖」查询串（`in:seen`），空查询返回 null。
 */
export function buildSeenHistorySearchQuery(rawQuery: string): string | null {
  const cleaned = rawQuery.trim().replace(/\s+/g, " ");
  if (!cleaned) return null;
  if (/(?:^|\s)in:seen(?:\s|$)/i.test(cleaned)) {
    return cleaned;
  }
  return `${cleaned} in:seen`;
}

function matchesHistoryTokens(item: SearchableHistoryItem, tokens: string[]): boolean {
  if (tokens.length === 0) return true;
  const haystack = [
    item.title,
    item.authorName || "",
    item.authorUsername,
    item.categoryName || "",
    ...(item.tags || []),
    String(item.topicId),
    `#${item.topicId}`,
  ]
    .join(" ")
    .toLowerCase();
  return tokens.every((tok) => {
    const cleanTok = tok.startsWith("#") && /^#\d+$/.test(tok) ? tok.slice(1) : tok;
    return haystack.includes(tok) || haystack.includes(cleanTok);
  });
}

/**
 * 合并本地/已加载浏览记录筛选结果与云端 `in:seen` 深度检索结果（优先保留本地匹配项并按 topicId 去重）。
 */
export function filterAndMergeHistoryItems(
  localAndLoaded: readonly SearchableHistoryItem[],
  cloudSeenHits: readonly SearchableHistoryItem[],
  rawQuery: string
): SearchableHistoryItem[] {
  const trimmed = rawQuery.trim();
  if (!trimmed) {
    return [...localAndLoaded];
  }
  const tokens = trimmed
    .toLowerCase()
    .split(/\s+/)
    .filter((t) => t.length > 0 && t !== "in:seen");

  const out: SearchableHistoryItem[] = [];
  const seen = new Set<number>();

  for (const item of localAndLoaded) {
    if (seen.has(item.topicId)) continue;
    if (matchesHistoryTokens(item, tokens)) {
      seen.add(item.topicId);
      out.push(item);
    }
  }

  for (const hit of cloudSeenHits) {
    if (seen.has(hit.topicId)) continue;
    seen.add(hit.topicId);
    out.push({ ...hit, fromCloudSearch: true });
  }

  return out;
}
