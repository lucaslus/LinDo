import { beforeEach, describe, expect, it, vi } from "vitest";
import type { NotificationItem } from "../lib/types";

const invokeMock = vi.fn();

vi.mock("@tauri-apps/api/core", () => ({
  invoke: (...args: any[]) => invokeMock(...args),
}));

vi.mock("@tauri-apps/api/window", () => ({
  getCurrentWindow: () => ({}),
}));

import {
  drainPendingMarkReads,
  fetchNotifications,
  getShortCachedUnreadCount,
  markSingleNotificationRead,
  recordConfirmedTopicFloorForNotifications,
  resetNotificationReadState,
  resolveAuthoritativeUnreadCount,
} from "../lib/api";

function makeNotif(id: number, overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    id,
    notificationType: 2,
    read: false,
    createdAt: "2026-11-21T10:00:00Z",
    topicId: 100,
    postNumber: 2,
    title: `Topic ${id}`,
    actorUsername: "tester",
    ...overrides,
  };
}

function makeRawNotif(id: number, read = false, topicId = 100, postNumber = 2) {
  return {
    id,
    notification_type: 2,
    read,
    created_at: "2026-11-21T10:00:00Z",
    topic_id: topicId,
    post_number: postNumber,
    data: {
      topic_title: `Topic ${id}`,
      original_username: "tester",
    },
  };
}

describe("Notification read-state & 60-page sync refactor", () => {
  beforeEach(() => {
    invokeMock.mockReset();
    resetNotificationReadState();
  });

  it("resolves authoritative unread count across <60, mixed 60, and all-unread 60 pages", () => {
    const small = [makeNotif(1, { read: false }), makeNotif(2, { read: true })];
    expect(resolveAuthoritativeUnreadCount(small, 19, 60)).toBe(1);

    const fullMixed = Array.from({ length: 60 }, (_, idx) =>
      makeNotif(idx + 1, { read: idx >= 4 })
    );
    expect(resolveAuthoritativeUnreadCount(fullMixed, 50, 60)).toBe(4);

    const fullUnread = Array.from({ length: 60 }, (_, idx) =>
      makeNotif(idx + 1, { read: false })
    );
    expect(resolveAuthoritativeUnreadCount(fullUnread, 82, 60)).toBe(82);
  });

  it("optimistically marks single read in shortNotifCache, survives 403, and drains once recovered", async () => {
    let allowPut = false;
    let putCalls = 0;
    const serverNotifs = [makeRawNotif(11, false), makeRawNotif(12, false)];

    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd === "set_unread_badge") return null;
      if (cmd === "site_request" && args.url.includes("/notifications.json")) {
        return {
          status: 200,
          isChallenge: false,
          isUnauthenticated: false,
          body: JSON.stringify({ notifications: serverNotifs }),
        };
      }
      if (cmd === "site_request" && args.url.includes("/notifications/mark-read.json")) {
        putCalls += 1;
        if (!allowPut) {
          return {
            status: 403,
            isChallenge: true,
            isUnauthenticated: false,
            body: "<html>cf</html>",
          };
        }
        serverNotifs[0] = { ...serverNotifs[0], read: true };
        return {
          status: 200,
          isChallenge: false,
          isUnauthenticated: false,
          body: '{"success":"OK"}',
        };
      }
      return {};
    });

    const initial = await fetchNotifications(0, true);
    expect(initial.map((x) => x.read)).toEqual([false, false]);

    await markSingleNotificationRead(11);
    expect(putCalls).toBe(1);
    expect(getShortCachedUnreadCount()).toBe(1);

    // Even if server hasn't persisted yet, forceRefresh still overlays locallyReadNotifIds
    // and drainPendingMarkReads keeps pending id until server 200
    const reloadedWhileBlocked = await fetchNotifications(0, true);
    expect(reloadedWhileBlocked.find((x) => x.id === 11)?.read).toBe(true);
    expect(reloadedWhileBlocked.find((x) => x.id === 12)?.read).toBe(false);

    allowPut = true;
    await drainPendingMarkReads();
    expect(putCalls).toBeGreaterThanOrEqual(2);
    expect(serverNotifs[0].read).toBe(true);
  });

  it("maps confirmed topic read floor to matching cached notifications and preserves across reload", async () => {
    const serverNotifs: any[] = [
      makeRawNotif(21, false, 500, 6),
      makeRawNotif(22, false, 500, 12),
      {
        ...makeRawNotif(23, false, 500, 2),
        notification_type: 5,
      },
      {
        ...makeRawNotif(24, false, 500, 3),
        notification_type: 2,
        data: {
          topic_title: "Topic 24",
          display_username: "3 条回复",
          consolidated_count: 3,
        },
      },
    ];

    invokeMock.mockImplementation(async (cmd: string, args: any) => {
      if (cmd === "set_unread_badge") return null;
      if (cmd === "site_request" && args.url.includes("/notifications.json")) {
        return {
          status: 200,
          isChallenge: false,
          isUnauthenticated: false,
          body: JSON.stringify({ notifications: serverNotifs }),
        };
      }
      if (cmd === "site_request" && args.url.includes("/notifications/mark-read.json")) {
        return {
          status: 200,
          isChallenge: false,
          isUnauthenticated: false,
          body: '{"success":"OK"}',
        };
      }
      return {};
    });

    await fetchNotifications(0, true);
    const cleared = recordConfirmedTopicFloorForNotifications(500, 8);
    expect(cleared).toBe(1);
    expect(getShortCachedUnreadCount()).toBe(3);

    const refreshed = await fetchNotifications(0, true);
    expect(refreshed.find((x) => x.id === 21)?.read).toBe(true);
    expect(refreshed.find((x) => x.id === 22)?.read).toBe(false);
    expect(refreshed.find((x) => x.id === 23)?.read).toBe(false);
    expect(refreshed.find((x) => x.id === 24)?.read).toBe(false);
  });

  it("clears locally tracked read notifications and topic floors on resetNotificationReadState", async () => {
    invokeMock.mockResolvedValue({
      status: 200,
      isChallenge: false,
      isUnauthenticated: false,
      body: JSON.stringify({ notifications: [makeRawNotif(31, false, 700, 3)] }),
    });

    await fetchNotifications(0, true);
    await markSingleNotificationRead(31);
    recordConfirmedTopicFloorForNotifications(700, 5);
    expect((await fetchNotifications(0, false))[0].read).toBe(true);

    resetNotificationReadState();
    const afterReset = await fetchNotifications(0, true);
    expect(afterReset[0].read).toBe(false);
  });
});
