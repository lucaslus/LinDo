import React, { useEffect, useRef, useState } from "react";
import { LinDoIcons } from "./LinDoIcons";
import { AvatarWithFlair } from "./SecondaryScreens";
import type {
  MentionUserCandidate,
  PostRevisionDetail,
  ReactionUserGroup,
  TopicPoster,
} from "../lib/types";
import {
  deleteBookmarkById,
  fetchPostReactionUsers,
  fetchPostRevision,
  inviteUserToPm,
  removeUserFromPm,
  saveBookmarkWithDetails,
  searchUsersForMention,
} from "../lib/api";
import { prepareCookedHtml } from "../lib/cooked";
import { isImeComposingEvent } from "../lib/imeGuard";
import { useImeSearchInput } from "../lib/imeSearch";
import { formatRelativeTime } from "../lib/palette";

const REACTION_EMOJI_MAP: Record<string, string> = {
  heart: "❤️",
  "+1": "👍",
  laughing: "😆",
  open_mouth: "😮",
  clap: "👏",
  confetti_ball: "🎉",
  hugs: "🤗",
  cry: "😢",
  angry: "😠",
  thinking: "🤔",
  fire: "🔥",
  rocket: "🚀",
  eyes: "👀",
  tada: "🎉",
  pray: "🙏",
};

function reactionEmoji(id: string): string {
  return REACTION_EMOJI_MAP[id] || `:${id}:`;
}

/* ==================== 1. 帖子编辑历史弹窗 (Revisions Diff) ==================== */
export const PostRevisionsModal: React.FC<{
  postId: number;
  postNumber: number;
  onInspectUser: (username: string) => void;
  onClose: () => void;
}> = ({ postId, postNumber, onInspectUser, onClose }) => {
  const [rev, setRev] = useState<PostRevisionDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadRevision = async (targetRev?: number) => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchPostRevision(postId, targetRev);
      setRev(data);
    } catch (e: any) {
      setError(e?.message || "无法加载编辑历史");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadRevision();
  }, [postId]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (isImeComposingEvent(e)) return;
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/58 p-5"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        className="flex h-[min(600px,82vh)] w-[min(760px,92vw)] flex-col overflow-hidden rounded-2xl shadow-2xl"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <header className="lindo-hairline-b flex h-[48px] shrink-0 items-center justify-between px-5">
          <div className="flex items-center gap-2">
            <LinDoIcons.Edit size={15} style={{ color: "var(--active-chip-text)" }} />
            <span className="text-[14.5px] font-semibold" style={{ color: "var(--ink-primary)" }}>
              #{postNumber} 楼编辑历史
            </span>
            {rev && (
              <span
                className="rounded-md px-2 py-0.5 text-[11.5px] tabular-nums"
                style={{
                  backgroundColor: "var(--subtle-surface)",
                  color: "var(--ink-secondary)",
                }}
              >
                第 {rev.currentRevision} / {rev.versionCount} 版
              </span>
            )}
          </div>
          <div className="flex items-center gap-1.5">
            {rev && (
              <>
                <button
                  type="button"
                  disabled={loading || !rev.previousRevision}
                  onClick={() => rev.previousRevision && void loadRevision(rev.previousRevision)}
                  className="lindo-interactive rounded-lg px-2.5 py-1 text-[12px] disabled:opacity-35"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  上一版
                </button>
                <button
                  type="button"
                  disabled={loading || !rev.nextRevision}
                  onClick={() => rev.nextRevision && void loadRevision(rev.nextRevision)}
                  className="lindo-interactive rounded-lg px-2.5 py-1 text-[12px] disabled:opacity-35"
                  style={{ color: "var(--ink-secondary)" }}
                >
                  下一版
                </button>
              </>
            )}
            <button
              type="button"
              onClick={onClose}
              className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
              style={{ color: "var(--ink-secondary)" }}
            >
              <LinDoIcons.Close size={15} />
            </button>
          </div>
        </header>

        {rev && !loading && (
          <div
            className="lindo-hairline-b flex shrink-0 flex-wrap items-center justify-between gap-2 px-5 py-2.5 text-[12px]"
            style={{ backgroundColor: "var(--subtle-surface)" }}
          >
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => {
                  onClose();
                  onInspectUser(rev.username);
                }}
                className="flex items-center gap-1.5 font-medium hover:underline cursor-pointer"
                style={{ color: "var(--ink-primary)" }}
              >
                <AvatarWithFlair
                  username={rev.username}
                  avatarTemplate={rev.avatarTemplate}
                  sizePx={20}
                />
                <span>{rev.displayName || rev.username}</span>
              </button>
              <span style={{ color: "var(--ink-tertiary)" }}>
                · {formatRelativeTime(rev.createdAt)}
              </span>
            </div>
            {rev.editReason && (
              <span className="truncate" style={{ color: "var(--ink-secondary)" }}>
                说明：{rev.editReason}
              </span>
            )}
          </div>
        )}

        <div className="lindo-scroll flex-1 min-h-0 px-5 py-4">
          {loading ? (
            <div className="py-12 text-center text-[13px]" style={{ color: "var(--ink-tertiary)" }}>
              正在加载版本差异...
            </div>
          ) : error ? (
            <div className="py-12 text-center text-[13px]" style={{ color: "var(--error-fg)" }}>
              {error}
            </div>
          ) : rev ? (
            <div className="space-y-4">
              {rev.titleChangesHtml && (
                <div
                  className="lindo-diff-body rounded-xl p-3 text-[14.5px] font-semibold"
                  style={{
                    backgroundColor: "var(--subtle-surface)",
                    border: "0.5px solid var(--hairline)",
                    color: "var(--ink-primary)",
                  }}
                  dangerouslySetInnerHTML={{ __html: prepareCookedHtml(rev.titleChangesHtml) }}
                />
              )}
              {rev.bodyChangesHtml ? (
                <div
                  className="lindo-cooked lindo-diff-body"
                  dangerouslySetInnerHTML={{ __html: prepareCookedHtml(rev.bodyChangesHtml) }}
                />
              ) : (
                <div className="py-8 text-center text-[12.5px]" style={{ color: "var(--ink-tertiary)" }}>
                  该版本仅变更了标题、分类或标签
                </div>
              )}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
};

/* ==================== 2. 点赞 / 表态成员名单弹窗 ==================== */
export const ReactionUsersModal: React.FC<{
  postId: number;
  initialReactionId?: string | null;
  onInspectUser: (username: string) => void;
  onClose: () => void;
}> = ({ postId, initialReactionId, onInspectUser, onClose }) => {
  const [groups, setGroups] = useState<ReactionUserGroup[]>([]);
  const [activeId, setActiveId] = useState<string>(initialReactionId || "");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fetchPostReactionUsers(postId)
      .then((res) => {
        if (!alive) return;
        setGroups(res);
        if (res.length > 0) {
          const match = initialReactionId && res.find((g) => g.id === initialReactionId);
          setActiveId(match ? match.id : res[0].id);
        }
      })
      .catch((e: any) => {
        if (alive) setError(e?.message || "无法获取表态成员列表");
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [postId, initialReactionId]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (isImeComposingEvent(e)) return;
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const currentGroup = groups.find((g) => g.id === activeId) || groups[0] || null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/56 p-5"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        className="flex h-[min(460px,74vh)] w-[min(440px,90vw)] flex-col overflow-hidden rounded-2xl shadow-2xl"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <header className="lindo-hairline-b flex h-[46px] shrink-0 items-center justify-between px-4">
          <span className="text-[14px] font-semibold" style={{ color: "var(--ink-primary)" }}>
            表态与点赞详情
          </span>
          <button
            type="button"
            onClick={onClose}
            className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
            style={{ color: "var(--ink-secondary)" }}
          >
            <LinDoIcons.Close size={15} />
          </button>
        </header>

        {groups.length > 0 && (
          <div className="lindo-hairline-b no-scrollbar flex shrink-0 items-center gap-1.5 overflow-x-auto px-4 py-2">
            {groups.map((g) => {
              const active = g.id === currentGroup?.id;
              return (
                <button
                  key={g.id}
                  type="button"
                  onClick={() => setActiveId(g.id)}
                  className="inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-1 text-[12px] font-medium cursor-pointer"
                  style={{
                    backgroundColor: active ? "var(--active-chip-bg)" : "var(--subtle-surface)",
                    color: active ? "var(--active-chip-text)" : "var(--ink-secondary)",
                  }}
                >
                  <span>{reactionEmoji(g.id)}</span>
                  <span className="tabular-nums">{g.count}</span>
                </button>
              );
            })}
          </div>
        )}

        <div className="lindo-scroll flex-1 min-h-0 divide-y">
          {loading ? (
            <div className="py-12 text-center text-[12.5px]" style={{ color: "var(--ink-tertiary)" }}>
              加载成员列表中...
            </div>
          ) : error ? (
            <div className="py-12 text-center text-[12.5px]" style={{ color: "var(--error-fg)" }}>
              {error}
            </div>
          ) : !currentGroup || currentGroup.users.length === 0 ? (
            <div className="py-12 text-center text-[12.5px]" style={{ color: "var(--ink-tertiary)" }}>
              暂无公开成员明细
            </div>
          ) : (
            currentGroup.users.map((u) => (
              <button
                key={u.username}
                type="button"
                onClick={() => {
                  onClose();
                  onInspectUser(u.username);
                }}
                className="lindo-interactive flex w-full items-center gap-2.5 px-4 py-2.5 text-left"
              >
                <AvatarWithFlair
                  username={u.username}
                  avatarTemplate={u.avatarTemplate}
                  sizePx={28}
                />
                <div className="min-w-0 flex-1">
                  <div className="truncate text-[13px] font-medium" style={{ color: "var(--ink-primary)" }}>
                    {u.name || u.username}
                  </div>
                  <div className="truncate text-[11.5px]" style={{ color: "var(--ink-tertiary)" }}>
                    @{u.username}
                  </div>
                </div>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
};

/* ==================== 3. 书签备注与定时提醒弹窗 ==================== */
function toLocalInputDateTime(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export const BookmarkReminderModal: React.FC<{
  postId: number;
  postNumber: number;
  existingBookmarkId?: number | null;
  initialName?: string | null;
  initialReminderAt?: string | null;
  onSaved: (res: { id: number | null; name: string | null; reminderAt: string | null }) => void;
  onClose: () => void;
}> = ({
  postId,
  postNumber,
  existingBookmarkId,
  initialName,
  initialReminderAt,
  onSaved,
  onClose,
}) => {
  const [name, setName] = useState(initialName || "");
  const [reminderLocal, setReminderLocal] = useState(() => toLocalInputDateTime(initialReminderAt));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (isImeComposingEvent(e)) return;
      if (e.key === "Escape") {
        e.preventDefault();
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const applyPreset = (kind: "none" | "2h" | "tomorrow" | "3d" | "monday") => {
    if (kind === "none") {
      setReminderLocal("");
      return;
    }
    const now = new Date();
    const target = new Date(now.getTime());
    if (kind === "2h") {
      target.setHours(target.getHours() + 2);
    } else if (kind === "tomorrow") {
      target.setDate(target.getDate() + 1);
      target.setHours(9, 0, 0, 0);
    } else if (kind === "3d") {
      target.setDate(target.getDate() + 3);
      target.setHours(9, 0, 0, 0);
    } else if (kind === "monday") {
      const day = target.getDay();
      const diff = day === 0 ? 1 : 8 - day;
      target.setDate(target.getDate() + diff);
      target.setHours(9, 0, 0, 0);
    }
    setReminderLocal(toLocalInputDateTime(target.toISOString()));
  };

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      const reminderIso = reminderLocal ? new Date(reminderLocal).toISOString() : null;
      const res = await saveBookmarkWithDetails({
        postId,
        existingBookmarkId,
        name,
        reminderAt: reminderIso,
      });
      onSaved({ id: res.id, name: res.name ?? null, reminderAt: res.reminderAt ?? null });
      onClose();
    } catch (e: any) {
      setError(e?.message || "保存书签失败");
    } finally {
      setSaving(false);
    }
  };

  const handleRemove = async () => {
    if (!existingBookmarkId) return;
    setSaving(true);
    setError(null);
    try {
      await deleteBookmarkById(existingBookmarkId);
      onSaved({ id: null, name: null, reminderAt: null });
      onClose();
    } catch (e: any) {
      setError(e?.message || "移除书签失败");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/58 p-5"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !saving) onClose();
      }}
    >
      <div
        className="w-[min(440px,92vw)] overflow-hidden rounded-2xl shadow-2xl"
        style={{
          backgroundColor: "var(--card-surface)",
          border: "0.5px solid var(--hairline)",
        }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <header className="lindo-hairline-b flex h-[46px] items-center justify-between px-5">
          <span className="text-[14px] font-semibold" style={{ color: "var(--ink-primary)" }}>
            {existingBookmarkId ? `编辑 #${postNumber} 楼书签` : `收藏 #${postNumber} 楼并设置提醒`}
          </span>
          <button
            type="button"
            onClick={onClose}
            className="lindo-interactive flex h-7 w-7 items-center justify-center rounded-full"
            style={{ color: "var(--ink-secondary)" }}
          >
            <LinDoIcons.Close size={15} />
          </button>
        </header>

        <div className="space-y-4 px-5 py-4">
          <div className="space-y-1.5">
            <label className="block text-[12px] font-medium" style={{ color: "var(--ink-secondary)" }}>
              书签备注（选填）
            </label>
            <input
              type="text"
              autoFocus
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="例如：周末复现配置、等作者更新..."
              className="w-full rounded-lg px-3 py-2 text-[13px] outline-none"
              style={{
                backgroundColor: "var(--subtle-surface)",
                border: "0.5px solid var(--hairline)",
                color: "var(--ink-primary)",
              }}
            />
          </div>

          <div className="space-y-2">
            <label className="block text-[12px] font-medium" style={{ color: "var(--ink-secondary)" }}>
              提醒时间（选填）
            </label>
            <div className="flex flex-wrap gap-1.5">
              {(
                [
                  { key: "none", label: "不提醒" },
                  { key: "2h", label: "2 小时后" },
                  { key: "tomorrow", label: "明天 09:00" },
                  { key: "3d", label: "3 天后" },
                  { key: "monday", label: "下周一 09:00" },
                ] as const
              ).map((p) => (
                <button
                  key={p.key}
                  type="button"
                  onClick={() => applyPreset(p.key)}
                  className="lindo-interactive rounded-full px-2.5 py-1 text-[11.8px]"
                  style={{
                    backgroundColor: "var(--subtle-surface)",
                    color: "var(--ink-secondary)",
                    border: "0.5px solid var(--hairline)",
                  }}
                >
                  {p.label}
                </button>
              ))}
            </div>
            <input
              type="datetime-local"
              value={reminderLocal}
              onChange={(e) => setReminderLocal(e.target.value)}
              className="w-full rounded-lg px-3 py-1.5 text-[12.8px] outline-none"
              style={{
                backgroundColor: "var(--subtle-surface)",
                border: "0.5px solid var(--hairline)",
                color: "var(--ink-primary)",
              }}
            />
          </div>

          {error && (
            <div className="text-[12px]" style={{ color: "var(--error-fg)" }}>
              {error}
            </div>
          )}

          <div className="flex items-center justify-between pt-1">
            {existingBookmarkId ? (
              <button
                type="button"
                disabled={saving}
                onClick={handleRemove}
                className="lindo-interactive rounded-lg px-2.5 py-1.5 text-[12.5px] font-medium"
                style={{ color: "#EF4444" }}
              >
                删除书签
              </button>
            ) : (
              <span />
            )}
            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={saving}
                onClick={onClose}
                className="lindo-interactive rounded-full px-3.5 py-1.5 text-[12.5px]"
                style={{ color: "var(--ink-secondary)" }}
              >
                取消
              </button>
              <button
                type="button"
                disabled={saving}
                onClick={handleSave}
                className="rounded-full px-4 py-1.5 text-[12.5px] font-semibold cursor-pointer disabled:opacity-50"
                style={{
                  backgroundColor: "var(--publish-btn-bg)",
                  color: "var(--publish-btn-fg)",
                }}
              >
                {saving ? "保存中..." : "保存书签"}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

/* ==================== 4. 私信参与者管理条 ==================== */
export const PmParticipantsBar: React.FC<{
  topicId: number;
  participants: TopicPoster[];
  currentUsername?: string | null;
  canInvite: boolean;
  canRemove: boolean;
  onInspectUser: (username: string) => void;
  onChanged: () => void;
  onNotice: (msg: string) => void;
}> = ({
  topicId,
  participants,
  currentUsername,
  canInvite,
  canRemove,
  onInspectUser,
  onChanged,
  onNotice,
}) => {
  const [adding, setAdding] = useState(false);
  const termInput = useImeSearchInput("");
  const term = termInput.inputValue;
  const committedTerm = termInput.committedValue;
  const [hits, setHits] = useState<MentionUserCandidate[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const clean = committedTerm.trim();
    if (!adding || !clean) {
      setHits([]);
      return;
    }
    let cancelled = false;
    const t = setTimeout(() => {
      searchUsersForMention(clean)
        .then((res) => {
          if (!cancelled) setHits(res.filter((u) => !u.isGroup).slice(0, 6));
        })
        .catch(() => {
          if (!cancelled) setHits([]);
        });
    }, 240);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [adding, committedTerm]);

  const handleInvite = async (uname: string) => {
    setBusy(true);
    try {
      await inviteUserToPm(topicId, uname);
      termInput.setDirect("");
      setAdding(false);
      onNotice(`已邀请 @${uname} 加入私信`);
      onChanged();
    } catch (e: any) {
      onNotice(e?.message || "邀请成员失败");
    } finally {
      setBusy(false);
    }
  };

  const handleRemove = async (uname: string) => {
    setBusy(true);
    try {
      await removeUserFromPm(topicId, uname);
      onNotice(`已将 @${uname} 移出私信`);
      onChanged();
    } catch (e: any) {
      onNotice(e?.message || "移出成员失败");
    } finally {
      setBusy(false);
    }
  };

  if (participants.length === 0 && !canInvite) return null;

  return (
    <div className="mt-2.5 flex flex-wrap items-center gap-1.5 text-[12px]">
      <span className="mr-0.5 text-[11.5px]" style={{ color: "var(--ink-tertiary)" }}>
        参与者
      </span>
      {participants.map((u) => {
        const isSelf =
          Boolean(currentUsername) &&
          u.username.toLowerCase() === currentUsername!.toLowerCase();
        return (
          <span
            key={u.username}
            className="inline-flex items-center gap-1.5 rounded-full py-0.5 pl-1 pr-2"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
            }}
          >
            <button
              type="button"
              onClick={() => onInspectUser(u.username)}
              className="inline-flex items-center gap-1 hover:underline cursor-pointer"
              style={{ color: "var(--ink-primary)" }}
            >
              <AvatarWithFlair
                username={u.username}
                avatarTemplate={u.avatarTemplate}
                sizePx={16}
              />
              <span>{u.username}</span>
            </button>
            {canRemove && !isSelf && (
              <button
                type="button"
                disabled={busy}
                title={`移出 @${u.username}`}
                onClick={() => void handleRemove(u.username)}
                className="opacity-60 hover:opacity-100 cursor-pointer"
                style={{ color: "var(--ink-tertiary)" }}
              >
                <LinDoIcons.Close size={11} />
              </button>
            )}
          </span>
        );
      })}

      {canInvite &&
        (!adding ? (
          <button
            type="button"
            onClick={() => setAdding(true)}
            className="lindo-interactive inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11.5px]"
            style={{
              backgroundColor: "var(--subtle-surface)",
              border: "0.5px solid var(--hairline)",
              color: "var(--active-chip-text)",
            }}
          >
            <LinDoIcons.Plus size={11.5} />
            <span>邀请成员</span>
          </button>
        ) : (
          <div className="relative inline-flex items-center gap-1">
            <input
              type="text"
              autoFocus
              {...termInput.inputProps}
              onKeyDown={(e) => {
                if (isImeComposingEvent(e)) return;
                if (e.key === "Escape") {
                  setAdding(false);
                  termInput.setDirect("");
                } else if (e.key === "Enter" && term.trim()) {
                  e.preventDefault();
                  void handleInvite(term.trim());
                }
              }}
              placeholder="输入用户名..."
              className="h-6 w-32 rounded-full px-2.5 text-[11.8px] outline-none"
              style={{
                backgroundColor: "var(--subtle-surface)",
                border: "0.5px solid var(--hairline)",
                color: "var(--ink-primary)",
              }}
            />
            <button
              type="button"
              onClick={() => {
                setAdding(false);
                termInput.setDirect("");
              }}
              className="lindo-interactive flex h-5 w-5 items-center justify-center rounded-full"
              style={{ color: "var(--ink-tertiary)" }}
            >
              <LinDoIcons.Close size={11} />
            </button>
            {hits.length > 0 && (
              <div
                className="absolute left-0 top-7 z-30 w-52 overflow-hidden rounded-xl shadow-2xl divide-y"
                style={{
                  backgroundColor: "var(--card-surface)",
                  border: "0.5px solid var(--hairline)",
                }}
              >
                {hits.map((u) => (
                  <button
                    key={u.username}
                    type="button"
                    disabled={busy}
                    onClick={() => void handleInvite(u.username)}
                    className="lindo-interactive flex w-full items-center gap-2 px-2.5 py-1.5 text-left text-[12px]"
                  >
                    <AvatarWithFlair
                      username={u.username}
                      avatarTemplate={u.avatarTemplate}
                      sizePx={18}
                    />
                    <span className="truncate font-medium" style={{ color: "var(--ink-primary)" }}>
                      @{u.username}
                    </span>
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
    </div>
  );
};

/* ==================== 5. 帖内查找悬浮条 (⌘F) ==================== */
export function clearFindHighlights(root: HTMLElement | null): void {
  if (!root) return;
  const marks = root.querySelectorAll<HTMLElement>("mark.lindo-find-hit");
  for (const mark of Array.from(marks)) {
    const parent = mark.parentNode;
    if (!parent) continue;
    parent.replaceChild(document.createTextNode(mark.textContent || ""), mark);
    parent.normalize();
  }
}

export function highlightFindMatches(root: HTMLElement | null, query: string): HTMLElement[] {
  clearFindHighlights(root);
  const q = query.trim();
  if (!root || q.length < 1) return [];
  const lowerQ = q.toLowerCase();
  const hits: HTMLElement[] = [];
  const containers = Array.from(root.querySelectorAll<HTMLElement>(".lindo-cooked"));
  const MAX_HITS = 250;

  for (const box of containers) {
    const walker = document.createTreeWalker(box, NodeFilter.SHOW_TEXT);
    const textNodes: Text[] = [];
    let cur = walker.nextNode();
    while (cur) {
      const parentEl = cur.parentElement;
      if (
        parentEl &&
        parentEl.tagName !== "SCRIPT" &&
        parentEl.tagName !== "STYLE" &&
        !parentEl.closest("[data-copy-code], .lindo-mermaid-card, .katex")
      ) {
        if ((cur.textContent || "").toLowerCase().includes(lowerQ)) {
          textNodes.push(cur as Text);
        }
      }
      cur = walker.nextNode();
    }

    for (const node of textNodes) {
      if (hits.length >= MAX_HITS) break;
      const text = node.textContent || "";
      const lower = text.toLowerCase();
      let pos = 0;
      const frag = document.createDocumentFragment();
      while (pos < text.length && hits.length < MAX_HITS) {
        const idx = lower.indexOf(lowerQ, pos);
        if (idx < 0) {
          frag.appendChild(document.createTextNode(text.slice(pos)));
          break;
        }
        if (idx > pos) {
          frag.appendChild(document.createTextNode(text.slice(pos, idx)));
        }
        const mark = document.createElement("mark");
        mark.className = "lindo-find-hit";
        mark.textContent = text.slice(idx, idx + q.length);
        frag.appendChild(mark);
        hits.push(mark);
        pos = idx + q.length;
      }
      if (pos < text.length && hits.length >= MAX_HITS) {
        frag.appendChild(document.createTextNode(text.slice(pos)));
      }
      node.parentNode?.replaceChild(frag, node);
    }
  }
  return hits;
}

export const InTopicFindBar: React.FC<{
  containerRef: React.RefObject<HTMLDivElement | null>;
  onClose: () => void;
}> = ({ containerRef, onClose }) => {
  const queryInput = useImeSearchInput("");
  const query = queryInput.inputValue;
  const committedQuery = queryInput.committedValue;
  const [count, setCount] = useState(0);
  const [activeIdx, setActiveIdx] = useState(0);
  const hitsRef = useRef<HTMLElement[]>([]);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    inputRef.current?.focus();
    inputRef.current?.select();
    return () => {
      clearFindHighlights(containerRef.current);
    };
  }, [containerRef]);

  const focusHit = (index: number) => {
    const list = hitsRef.current;
    if (list.length === 0) return;
    const wrapped = ((index % list.length) + list.length) % list.length;
    list.forEach((m, i) => m.classList.toggle("is-active", i === wrapped));
    setActiveIdx(wrapped);
    list[wrapped]?.scrollIntoView({ behavior: "smooth", block: "center" });
  };

  useEffect(() => {
    const timer = setTimeout(() => {
      const hits = highlightFindMatches(containerRef.current, committedQuery);
      hitsRef.current = hits;
      setCount(hits.length);
      if (hits.length > 0) {
        focusHit(0);
      } else {
        setActiveIdx(0);
      }
    }, 140);
    return () => clearTimeout(timer);
  }, [committedQuery, containerRef]);

  return (
    <div
      className="absolute right-5 top-[52px] z-30 flex items-center gap-1.5 rounded-xl px-3 py-1.5 shadow-2xl"
      style={{
        backgroundColor: "var(--card-surface)",
        border: "0.5px solid var(--hairline)",
      }}
    >
      <LinDoIcons.Search size={14} style={{ color: "var(--ink-tertiary)" }} />
      <input
        ref={inputRef}
        type="text"
        {...queryInput.inputProps}
        onKeyDown={(e) => {
          if (isImeComposingEvent(e)) return;
          if (e.key === "Escape") {
            e.preventDefault();
            onClose();
          } else if (e.key === "Enter") {
            e.preventDefault();
            focusHit(e.shiftKey ? activeIdx - 1 : activeIdx + 1);
          }
        }}
        placeholder="在当前话题内查找..."
        className="w-44 bg-transparent text-[12.5px] outline-none"
        style={{ color: "var(--ink-primary)" }}
      />
      <span className="min-w-[42px] text-right text-[11px] tabular-nums" style={{ color: "var(--ink-tertiary)" }}>
        {query.trim() ? (count > 0 ? `${activeIdx + 1}/${count}` : "0/0") : ""}
      </span>
      <button
        type="button"
        disabled={count === 0}
        onClick={() => focusHit(activeIdx - 1)}
        title="上一个 (Shift+Enter)"
        className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-md disabled:opacity-35"
        style={{ color: "var(--ink-secondary)" }}
      >
        <LinDoIcons.ChevronUp size={13} />
      </button>
      <button
        type="button"
        disabled={count === 0}
        onClick={() => focusHit(activeIdx + 1)}
        title="下一个 (Enter)"
        className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-md disabled:opacity-35"
        style={{ color: "var(--ink-secondary)" }}
      >
        <LinDoIcons.ChevronDown size={13} />
      </button>
      <button
        type="button"
        onClick={onClose}
        title="关闭 (Esc)"
        className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-md"
        style={{ color: "var(--ink-tertiary)" }}
      >
        <LinDoIcons.Close size={13} />
      </button>
    </div>
  );
};

/* ==================== 6. 长文目录悬浮面板 (TOC) ==================== */
export interface TopicHeadingItem {
  id: string;
  level: number;
  text: string;
  index: number;
}

export function extractPostHeadings(rawHtml: string): TopicHeadingItem[] {
  if (!rawHtml || !/<h[1-3]\b/i.test(rawHtml)) return [];
  const parser = new DOMParser();
  const doc = parser.parseFromString(rawHtml, "text/html");
  const out: TopicHeadingItem[] = [];
  let idx = 0;
  doc.querySelectorAll("h1, h2, h3").forEach((h) => {
    if (h.closest("aside.quote, aside.onebox")) return;
    const text = (h.textContent || "").replace(/\s+/g, " ").trim();
    if (!text) return;
    const level = Number(h.tagName.slice(1)) || 2;
    out.push({
      id: h.getAttribute("id") || "",
      level,
      text: text.slice(0, 72),
      index: idx,
    });
    idx += 1;
  });
  return out;
}

export const TopicTocPopover: React.FC<{
  headings: TopicHeadingItem[];
  onSelect: (item: TopicHeadingItem) => void;
  onClose: () => void;
}> = ({ headings, onSelect, onClose }) => {
  return (
    <div
      className="absolute right-5 top-[52px] z-30 flex max-h-[60vh] w-[268px] flex-col overflow-hidden rounded-xl shadow-2xl"
      style={{
        backgroundColor: "var(--card-surface)",
        border: "0.5px solid var(--hairline)",
      }}
    >
      <div className="lindo-hairline-b flex items-center justify-between px-3.5 py-2">
        <span className="text-[12px] font-semibold" style={{ color: "var(--ink-primary)" }}>
          正文目录（{headings.length}）
        </span>
        <button
          type="button"
          onClick={onClose}
          className="lindo-interactive flex h-6 w-6 items-center justify-center rounded-full"
          style={{ color: "var(--ink-tertiary)" }}
        >
          <LinDoIcons.Close size={13} />
        </button>
      </div>
      <div className="lindo-scroll flex-1 py-1.5 px-1.5 space-y-0.5">
        {headings.map((h) => (
          <button
            key={`${h.index}_${h.text}`}
            type="button"
            onClick={() => {
              onSelect(h);
              onClose();
            }}
            className="lindo-interactive block w-full truncate rounded-lg py-1.5 pr-2.5 text-left text-[12.2px]"
            style={{
              paddingLeft: `${10 + Math.max(0, h.level - 1) * 12}px`,
              color: h.level === 1 ? "var(--ink-primary)" : "var(--ink-secondary)",
              fontWeight: h.level === 1 ? 600 : 400,
            }}
          >
            {h.text}
          </button>
        ))}
      </div>
    </div>
  );
};
