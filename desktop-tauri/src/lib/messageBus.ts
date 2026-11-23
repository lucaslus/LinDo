/**
 * Disciplined Discourse MessageBus live-sync client for real-time notifications,
 * active topic new-reply alerts, and Home feed new-topic indicators.
 *
 * Design guarantees:
 * - Routes through `siteRequest` (shares auth cookies & respects Rust `net_policy` rate limits)
 * - Immediately pauses when window is hidden (`document.hidden`)
 * - Exponential backoff on transient errors / Cloudflare 429 — never hammers linux.do!
 */
import { siteRequest } from "./api";

export interface MessageBusEvent {
  channel: string;
  messageId: number;
  data: any;
}

type ChannelHandler = (data: any, messageId: number) => void;

class DiscourseMessageBusClient {
  private readonly clientId: string;
  private readonly handlers = new Map<string, Set<ChannelHandler>>();
  private readonly cursors = new Map<string, number>();
  private running = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private inFlight = false;
  private consecutiveFailures = 0;
  private visibilityListenerBound = false;

  constructor() {
    const rand = Math.random().toString(36).slice(2, 10);
    this.clientId = `lindo_${Date.now().toString(36)}_${rand}`;
  }

  private bindVisibility(): void {
    if (this.visibilityListenerBound || typeof document === "undefined") return;
    this.visibilityListenerBound = true;
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden && this.running && !this.inFlight) {
        this.scheduleNext(1500);
      }
    });
  }

  public subscribe(channel: string, handler: ChannelHandler, initialCursor = -1): () => void {
    let set = this.handlers.get(channel);
    if (!set) {
      set = new Set();
      this.handlers.set(channel, set);
      this.cursors.set(channel, initialCursor);
    }
    set.add(handler);
    this.ensureRunning();
    return () => {
      const cur = this.handlers.get(channel);
      if (!cur) return;
      cur.delete(handler);
      if (cur.size === 0) {
        this.handlers.delete(channel);
        this.cursors.delete(channel);
      }
      if (this.handlers.size === 0) {
        this.stop();
      }
    };
  }

  private ensureRunning(): void {
    this.bindVisibility();
    if (this.running) return;
    this.running = true;
    this.consecutiveFailures = 0;
    this.scheduleNext(4500);
  }

  public stop(): void {
    this.running = false;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  private scheduleNext(delayMs: number): void {
    if (!this.running) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.pollOnce();
    }, delayMs);
  }

  private async pollOnce(): Promise<void> {
    if (!this.running || this.inFlight || this.handlers.size === 0) return;
    if (typeof document !== "undefined" && document.hidden) {
      this.scheduleNext(18_000);
      return;
    }

    this.inFlight = true;
    try {
      const form: Record<string, string> = {};
      for (const [ch, cursor] of this.cursors.entries()) {
        form[ch] = String(cursor);
      }
      const resp = await siteRequest(
        `https://linux.do/message-bus/${encodeURIComponent(this.clientId)}/poll?dlp=t`,
        "POST",
        form,
        {
          "X-Silence-Logger": "true",
          "Discourse-Background": "true",
        },
        { silentDiag: true }
      );

      if (resp.status === 200 && resp.body) {
        let parsed: any = null;
        try {
          parsed = JSON.parse(resp.body);
        } catch {
          parsed = null;
        }
        if (Array.isArray(parsed)) {
          this.consecutiveFailures = 0;
          for (const msg of parsed) {
            const ch = String(msg?.channel || "");
            const mid = Number(msg?.message_id ?? -1);
            if (!ch) continue;
            if (ch === "/__status" && msg?.data && typeof msg.data === "object") {
              for (const [statusCh, statusId] of Object.entries(msg.data)) {
                const n = Number(statusId);
                if (this.cursors.has(statusCh) && Number.isFinite(n)) {
                  this.cursors.set(statusCh, n);
                }
              }
              continue;
            }
            if (Number.isFinite(mid) && mid >= 0 && this.cursors.has(ch)) {
              this.cursors.set(ch, mid);
            }
            const subs = this.handlers.get(ch);
            if (subs) {
              for (const fn of subs) {
                try {
                  fn(msg.data, mid);
                } catch {
                  // isolate subscriber exceptions
                }
              }
            }
          }
          // Calm 12s cadence keeps updates near-real-time without tripping Cloudflare WAF burst limits
          this.scheduleNext(12_000);
          return;
        }
      }

      this.consecutiveFailures += 1;
      const backoff =
        resp.status === 429 || resp.status === 403
          ? Math.min(180_000, 45_000 * this.consecutiveFailures)
          : Math.min(90_000, 18_000 * this.consecutiveFailures);
      this.scheduleNext(backoff);
    } catch {
      this.consecutiveFailures += 1;
      this.scheduleNext(Math.min(120_000, 22_000 * this.consecutiveFailures));
    } finally {
      this.inFlight = false;
    }
  }
}

export const discourseMessageBus = new DiscourseMessageBusClient();
