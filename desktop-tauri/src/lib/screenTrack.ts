/**
 * Real dwell-time Discourse ScreenTrack engine for Desktop (aligned 1:1 with Android `ScreenTrackEngine`
 * and official Discourse `screen-track.js`):
 * - Accumulates real millisecond viewport dwell time per visible floor (1s tick)
 * - Pauses automatically on 3 min inactivity or hidden document/window (`document.hidden`)
 * - Caps per-post tracking at 6 min (`360_000ms`)
 * - Rushes newly viewed unread posts after >= 1s dwell, otherwise batches every ~60s & on topic leave
 * - Maintains a bounded retry queue with exponential backoff (`5s -> 10s -> 20s -> 40s`) on transient failures
 * - Preserves official Discourse telemetry headers (`X-SILENCE-LOGGER`, `Discourse-Present`, `Discourse-Logged-In`)
 */

export const PAUSE_UNLESS_SCROLLED_MS = 3 * 60 * 1000;
export const MAX_TRACKING_TIME_PER_POST_MS = 6 * 60 * 1000;
export const FLUSH_INTERVAL_MS = 60_000;
export const UNREAD_RUSH_MIN_MS = 1_000;
export const MAX_TOPIC_RETRIES = 3;
export const MAX_RETRY_QUEUE_SIZE = 24;
export const MAX_POSTS_PER_BATCH = 25;
export const BACKOFF_DELAYS_MS = [5_000, 10_000, 20_000, 40_000] as const;

export interface TimingBatchPayload {
  topicId: number;
  topicTimeMs: number;
  timings: Record<number, number>;
  isPresent: boolean;
}

export type TimingSendOutcome = "ok" | "retryable" | "drop";

export interface ScreenTrackDeps {
  now?: () => number;
  sendBatch: (payload: TimingBatchPayload) => Promise<TimingSendOutcome>;
  onConfirmed?: (topicId: number, maxPostNumber: number) => void;
}

interface QueuedTimingBatch {
  topicId: number;
  topicTimeMs: number;
  timings: Map<number, number>;
  retries: number;
}

export class ScreenTrackController {
  private readonly nowFn: () => number;
  private readonly sendBatch: (payload: TimingBatchPayload) => Promise<TimingSendOutcome>;
  private readonly onConfirmed?: (topicId: number, maxPostNumber: number) => void;

  private currentTopicId: number | null = null;
  private lastTickMs = 0;
  private lastActivityMs = 0;
  private accumulatedSinceFlushMs = 0;
  private currentTopicTimeMs = 0;
  private documentVisible = true;
  private pausedForVerify = false;

  private onscreenPosts = new Set<number>();
  private currentTimingsMs = new Map<number, number>();
  private totalTimingsMs = new Map<number, number>();
  private reportedReadPosts = new Set<string>();

  private queue: QueuedTimingBatch[] = [];
  private inFlight = false;
  private consecutiveFailures = 0;
  private blockSendingUntilMs = 0;

  constructor(deps: ScreenTrackDeps) {
    this.nowFn = deps.now ?? (() => Date.now());
    this.sendBatch = deps.sendBatch;
    this.onConfirmed = deps.onConfirmed;
  }

  getCurrentTopicId(): number | null {
    return this.currentTopicId;
  }

  getPendingQueueLength(): number {
    return this.queue.length;
  }

  getBlockSendingUntilMs(): number {
    return this.blockSendingUntilMs;
  }

  getCurrentTimingsSnapshot(): Record<number, number> {
    const out: Record<number, number> = {};
    for (const [postNum, ms] of this.currentTimingsMs.entries()) {
      out[postNum] = ms;
    }
    return out;
  }

  getTotalTimingsSnapshot(): Record<number, number> {
    const out: Record<number, number> = {};
    for (const [postNum, ms] of this.totalTimingsMs.entries()) {
      out[postNum] = ms;
    }
    return out;
  }

  onTopicEntered(
    topicId: number,
    initialVisiblePosts: Iterable<number> = [1],
    alreadyReadUpToFloor = 0,
    nowMs = this.nowFn()
  ): void {
    if (topicId <= 0) return;
    if (this.currentTopicId !== null && this.currentTopicId !== topicId) {
      this.consolidateCurrentTopicLocked();
    }
    this.currentTopicId = topicId;
    this.lastTickMs = nowMs;
    this.lastActivityMs = nowMs;
    this.accumulatedSinceFlushMs = 0;
    this.currentTopicTimeMs = 0;
    this.currentTimingsMs.clear();
    this.totalTimingsMs.clear();
    this.onscreenPosts.clear();
    for (const postNum of initialVisiblePosts) {
      if (postNum > 0) this.onscreenPosts.add(postNum);
    }
    if (alreadyReadUpToFloor > 0) {
      for (const postNum of this.onscreenPosts) {
        if (postNum <= alreadyReadUpToFloor) {
          this.reportedReadPosts.add(`${topicId}:${postNum}`);
        }
      }
    }
    if (this.queue.length > 0) {
      void this.drainQueue(nowMs);
    }
  }

  updateVisiblePosts(visiblePostNumbers: Iterable<number>, alreadyReadUpToFloor = 0): void {
    this.onscreenPosts.clear();
    for (const postNum of visiblePostNumbers) {
      if (postNum > 0) {
        this.onscreenPosts.add(postNum);
        if (this.currentTopicId && alreadyReadUpToFloor > 0 && postNum <= alreadyReadUpToFloor) {
          this.reportedReadPosts.add(`${this.currentTopicId}:${postNum}`);
        }
      }
    }
  }

  markUserActivity(nowMs = this.nowFn()): void {
    this.lastActivityMs = nowMs;
  }

  setDocumentVisible(visible: boolean, nowMs = this.nowFn()): void {
    if (this.documentVisible === visible) return;
    this.documentVisible = visible;
    if (visible) {
      this.lastTickMs = nowMs;
      this.lastActivityMs = nowMs;
      if (this.queue.length > 0) {
        void this.drainQueue(nowMs);
      }
    } else {
      // Flush accumulated real dwell time immediately before backgrounding/hiding
      this.consolidateCurrentTopicLocked();
      void this.drainQueue(nowMs);
    }
  }

  /**
   * Manual / legacy fallback enqueue (e.g. direct call from tests or immediate jump-read hints).
   */
  recordManualTimings(
    topicId: number,
    postNumbers: number[],
    dwellMsPerPost = UNREAD_RUSH_MIN_MS,
    nowMs = this.nowFn()
  ): void {
    if (this.pausedForVerify || topicId <= 0 || postNumbers.length === 0) return;
    const timings = new Map<number, number>();
    const safeDwell = Math.max(250, Math.min(MAX_TRACKING_TIME_PER_POST_MS, Math.round(dwellMsPerPost)));
    for (const n of postNumbers) {
      if (n > 0) timings.set(n, safeDwell);
    }
    if (timings.size === 0) return;
    this.enqueueBatch(topicId, Math.max(safeDwell, timings.size * 500), timings);
    void this.drainQueue(nowMs);
  }

  tick(nowMs = this.nowFn()): void {
    if (this.currentTopicId === null) {
      if (this.queue.length > 0 && !this.pausedForVerify && nowMs >= this.blockSendingUntilMs) {
        void this.drainQueue(nowMs);
      }
      return;
    }

    if (this.lastTickMs <= 0) {
      this.lastTickMs = nowMs;
      return;
    }

    const rawDelta = nowMs - this.lastTickMs;
    this.lastTickMs = nowMs;
    if (rawDelta <= 0) return;

    // Pause dwell accumulation while document is hidden, user idle > 3m, or CF verifying
    const isIdle = nowMs - this.lastActivityMs > PAUSE_UNLESS_SCROLLED_MS;
    if (!this.documentVisible || isIdle || this.pausedForVerify) {
      return;
    }

    const diffMs = Math.min(rawDelta, 5_000);
    if (this.onscreenPosts.size === 0) {
      return;
    }

    let accumulatedAny = false;
    let hasUnreadRush = false;

    for (const postNum of this.onscreenPosts) {
      const prevTotal = this.totalTimingsMs.get(postNum) ?? 0;
      if (prevTotal >= MAX_TRACKING_TIME_PER_POST_MS) continue;
      const allowed = Math.min(diffMs, MAX_TRACKING_TIME_PER_POST_MS - prevTotal);
      if (allowed <= 0) continue;

      const nextCur = (this.currentTimingsMs.get(postNum) ?? 0) + allowed;
      this.currentTimingsMs.set(postNum, nextCur);
      this.totalTimingsMs.set(postNum, prevTotal + allowed);
      accumulatedAny = true;

      const readKey = `${this.currentTopicId}:${postNum}`;
      if (!this.reportedReadPosts.has(readKey) && nextCur >= UNREAD_RUSH_MIN_MS) {
        hasUnreadRush = true;
      }
    }

    if (accumulatedAny) {
      this.currentTopicTimeMs = Math.min(
        MAX_TRACKING_TIME_PER_POST_MS,
        this.currentTopicTimeMs + diffMs
      );
      this.accumulatedSinceFlushMs += diffMs;
    }

    const shouldFlush =
      (hasUnreadRush && this.currentTimingsMs.size > 0) ||
      (this.accumulatedSinceFlushMs >= FLUSH_INTERVAL_MS && this.currentTimingsMs.size > 0);

    if (shouldFlush) {
      this.consolidateCurrentTopicLocked();
      void this.drainQueue(nowMs);
    } else if (this.queue.length > 0 && nowMs >= this.blockSendingUntilMs) {
      void this.drainQueue(nowMs);
    }
  }

  onTopicExited(topicId?: number | null, nowMs = this.nowFn()): Promise<void> {
    if (topicId != null && this.currentTopicId !== null && this.currentTopicId !== topicId) {
      return Promise.resolve();
    }
    this.consolidateCurrentTopicLocked();
    this.currentTopicId = null;
    this.onscreenPosts.clear();
    this.accumulatedSinceFlushMs = 0;
    this.currentTopicTimeMs = 0;
    return this.drainQueue(nowMs);
  }

  flushNow(nowMs = this.nowFn()): Promise<void> {
    this.consolidateCurrentTopicLocked();
    return this.drainQueue(nowMs);
  }

  pauseAndDropPending(): void {
    this.pausedForVerify = true;
    this.currentTimingsMs.clear();
    this.currentTopicTimeMs = 0;
    this.accumulatedSinceFlushMs = 0;
    this.queue = [];
    this.blockSendingUntilMs = 0;
  }

  resumeAfterVerify(nowMs = this.nowFn()): void {
    this.pausedForVerify = false;
    this.lastTickMs = nowMs;
    this.lastActivityMs = nowMs;
  }

  resetAll(): void {
    this.currentTopicId = null;
    this.lastTickMs = 0;
    this.lastActivityMs = 0;
    this.accumulatedSinceFlushMs = 0;
    this.currentTopicTimeMs = 0;
    this.onscreenPosts.clear();
    this.currentTimingsMs.clear();
    this.totalTimingsMs.clear();
    this.reportedReadPosts.clear();
    this.queue = [];
    this.consecutiveFailures = 0;
    this.blockSendingUntilMs = 0;
    this.pausedForVerify = false;
  }

  private consolidateCurrentTopicLocked(): void {
    if (this.currentTopicId === null || this.currentTimingsMs.size === 0) {
      this.accumulatedSinceFlushMs = 0;
      this.currentTopicTimeMs = 0;
      return;
    }
    const topicId = this.currentTopicId;
    const copied = new Map<number, number>();
    let maxPostDwell = 0;
    for (const [postNum, ms] of this.currentTimingsMs.entries()) {
      const clamped = Math.max(100, Math.min(MAX_TRACKING_TIME_PER_POST_MS, Math.round(ms)));
      copied.set(postNum, clamped);
      if (clamped > maxPostDwell) maxPostDwell = clamped;
    }
    const topicTimeMs = Math.max(
      maxPostDwell,
      Math.min(MAX_TRACKING_TIME_PER_POST_MS, Math.round(this.currentTopicTimeMs))
    );
    this.currentTimingsMs.clear();
    this.currentTopicTimeMs = 0;
    this.accumulatedSinceFlushMs = 0;
    this.enqueueBatch(topicId, topicTimeMs, copied);
  }

  private enqueueBatch(topicId: number, topicTimeMs: number, timings: Map<number, number>): void {
    const existing = this.queue.find((q) => q.topicId === topicId);
    if (existing) {
      existing.topicTimeMs = Math.min(
        MAX_TRACKING_TIME_PER_POST_MS,
        existing.topicTimeMs + topicTimeMs
      );
      for (const [postNum, ms] of timings.entries()) {
        existing.timings.set(
          postNum,
          Math.min(MAX_TRACKING_TIME_PER_POST_MS, (existing.timings.get(postNum) ?? 0) + ms)
        );
      }
      return;
    }
    if (this.queue.length >= MAX_RETRY_QUEUE_SIZE) {
      this.queue.shift();
    }
    this.queue.push({
      topicId,
      topicTimeMs,
      timings,
      retries: 0,
    });
  }

  private async drainQueue(nowMs = this.nowFn()): Promise<void> {
    if (this.pausedForVerify || this.inFlight || this.queue.length === 0) return;
    if (nowMs < this.blockSendingUntilMs) return;

    this.inFlight = true;
    try {
      while (this.queue.length > 0 && !this.pausedForVerify) {
        const curNow = this.nowFn();
        if (curNow < this.blockSendingUntilMs) break;

        const batch = this.queue.shift()!;
        const entries = Array.from(batch.timings.entries()).sort((a, b) => a[0] - b[0]);
        if (entries.length === 0) continue;

        const headEntries = entries.slice(0, MAX_POSTS_PER_BATCH);
        const overflowEntries = entries.slice(MAX_POSTS_PER_BATCH);
        if (overflowEntries.length > 0) {
          this.queue.unshift({
            topicId: batch.topicId,
            topicTimeMs: Math.max(1000, Math.round(batch.topicTimeMs / 2)),
            timings: new Map(overflowEntries),
            retries: batch.retries,
          });
        }

        const timingsObj: Record<number, number> = {};
        let maxPostNum = 0;
        for (const [postNum, ms] of headEntries) {
          timingsObj[postNum] = Math.max(100, Math.round(ms));
          if (postNum > maxPostNum) maxPostNum = postNum;
        }

        const isPresent =
          this.documentVisible &&
          curNow - this.lastActivityMs <= PAUSE_UNLESS_SCROLLED_MS;

        let outcome: TimingSendOutcome;
        try {
          outcome = await this.sendBatch({
            topicId: batch.topicId,
            topicTimeMs: Math.max(100, Math.round(batch.topicTimeMs)),
            timings: timingsObj,
            isPresent,
          });
        } catch {
          outcome = "retryable";
        }

        if (outcome === "ok") {
          this.consecutiveFailures = 0;
          this.blockSendingUntilMs = 0;
          for (const [postNum] of headEntries) {
            this.reportedReadPosts.add(`${batch.topicId}:${postNum}`);
          }
          if (this.reportedReadPosts.size > 2_000) {
            const firstKey = this.reportedReadPosts.values().next().value;
            if (firstKey) this.reportedReadPosts.delete(firstKey);
          }
          if (maxPostNum > 0) {
            this.onConfirmed?.(batch.topicId, maxPostNum);
          }
        } else if (outcome === "retryable") {
          const nextRetries = batch.retries + 1;
          const delayIdx = Math.min(this.consecutiveFailures, BACKOFF_DELAYS_MS.length - 1);
          this.consecutiveFailures += 1;
          this.blockSendingUntilMs = this.nowFn() + BACKOFF_DELAYS_MS[delayIdx];
          if (nextRetries <= MAX_TOPIC_RETRIES) {
            this.queue.unshift({
              topicId: batch.topicId,
              topicTimeMs: batch.topicTimeMs,
              timings: new Map(headEntries),
              retries: nextRetries,
            });
          }
          break;
        }
        // outcome === "drop" (401/403/404/challenge): do not requeue
      }
    } finally {
      this.inFlight = false;
    }
  }
}
