import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  decodeQrFromRgba,
  decodeQrMatrix,
  encodeQrLoginPayload,
  encodeQrMatrix,
  encodeQrSvg,
  isQrLoginUri,
  parseQrLoginPayload,
} from "../desktop-tauri/src/lib/qrLogin.ts";
import {
  resolveDiscourseTopicUnread,
  resolveEffectiveViewedFloor,
} from "../desktop-tauri/src/lib/topicReadState.ts";
import {
  MAX_TRACKED_PENDING_TOPICS,
  STALE_PENDING_SNAPSHOT_MS,
  countEffectivePendingTopics,
  createEmptyPendingFeedSnapshot,
  evaluatePendingFeedPoll,
  formatPendingNewCount,
  resolvePendingFeedApply,
} from "../desktop-tauri/src/lib/pendingFeedState.ts";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SRC_ROOT = path.resolve(__dirname, "../desktop-tauri/src");

test("QR login URI parser & encoder match Android QrLoginManager v2 and FluxDO v2", () => {
  const now = 1_795_436_436_560;
  const uri = encodeQrLoginPayload("key_64_hex", "otp_64_hex", "中文昵称_lucas", 0);
  assert.equal(isQrLoginUri(uri), true);

  const parsed = parseQrLoginPayload(uri, now);
  assert.equal(parsed.valid, true);
  assert.deepEqual(parsed.payload, {
    version: 2,
    apiKey: "key_64_hex",
    otp: "otp_64_hex",
    username: "中文昵称_lucas",
    expiresAtEpochMs: 0,
  });

  const flux = parseQrLoginPayload(
    "fluxdo://qr-login?v=2&k=flux_k&o=flux_o&u=alice&exp=1895436436560",
    now
  );
  assert.equal(flux.valid, true);

  assert.deepEqual(
    parseQrLoginPayload("lindo://qr-login?v=2&k=k1&o=o1&u=bob&exp=1700000000000", now),
    { valid: false, message: "登录二维码已过期，请重新生成" }
  );
  assert.deepEqual(parseQrLoginPayload("lindo://qr-login?v=9&k=k1&o=o1", now), {
    valid: false,
    message: "二维码版本不受支持，请升级客户端",
  });
  assert.deepEqual(parseQrLoginPayload("https://lindo.app/#download", now), {
    valid: false,
    message: "不是 Lindo 登录二维码",
  });
});

test("ISO/IEC 18004 QR encoder & RS error-correcting decoder round-trip v7+ multi-block URIs", () => {
  const longUri = encodeQrLoginPayload(
    "a1b2c3d4".repeat(8),
    "f0e1d2c3".repeat(8),
    "测试长用户名_跨端扫码_2026",
    1_895_436_436_560
  );
  assert.ok(longUri.length > 150);
  const svg = encodeQrSvg(longUri, 2, "M");
  assert.ok(svg.size > 45);
  assert.ok(svg.path.startsWith("M"));

  for (const ecc of ["L", "M", "Q", "H"]) {
    const mat = encodeQrMatrix(longUri, ecc);
    assert.equal(decodeQrMatrix(mat), longUri);

    const damaged = mat.map((row) => Uint8Array.from(row));
    for (let t = 0; t < 5; t++) {
      const rr = 10 + ((t * 7) % (damaged.length - 20));
      const cc = 10 + ((t * 11) % (damaged.length - 20));
      damaged[rr][cc] ^= 1;
    }
    assert.equal(decodeQrMatrix(damaged), longUri);
  }
});

test("decodeQrFromRgba decodes full-screen dark/light mobile screenshots and inverted QRs", () => {
  const uri = encodeQrLoginPayload("k_32_abcdef012345", "o_32_987654fedcba", "lucas", 0);
  const mat = encodeQrMatrix(uri, "M");
  const dim = mat.length;
  const W = 1080;
  const H = 2400;
  const rgba = new Uint8ClampedArray(W * H * 4);
  for (let i = 0; i < W * H; i++) {
    rgba[i * 4] = 18;
    rgba[i * 4 + 1] = 20;
    rgba[i * 4 + 2] = 26;
    rgba[i * 4 + 3] = 255;
  }

  const card = 600;
  const cx0 = Math.floor((W - card) / 2);
  const cy0 = 660;
  for (let y = cy0; y < cy0 + card; y++) {
    for (let x = cx0; x < cx0 + card; x++) {
      const idx = (y * W + x) * 4;
      rgba[idx] = 255;
      rgba[idx + 1] = 255;
      rgba[idx + 2] = 255;
    }
  }

  const scale = 9;
  const qx0 = Math.floor((W - dim * scale) / 2);
  const qy0 = cy0 + Math.floor((card - dim * scale) / 2);
  for (let r = 0; r < dim; r++) {
    for (let c = 0; c < dim; c++) {
      if (mat[r][c] === 1) {
        for (let dy = 0; dy < scale; dy++) {
          for (let dx = 0; dx < scale; dx++) {
            const idx = ((qy0 + r * scale + dy) * W + (qx0 + c * scale + dx)) * 4;
            rgba[idx] = 17;
            rgba[idx + 1] = 24;
            rgba[idx + 2] = 39;
          }
        }
      }
    }
  }
  assert.equal(decodeQrFromRgba(rgba, W, H), uri);
});

test("Settings home order & strict 2-state cursor policy guard hold across desktop-tauri/src", () => {
  const secPath = path.join(SRC_ROOT, "components/SecondaryScreens.tsx");
  const secSrc = fs.readFileSync(secPath, "utf8");
  const acctIdx = secSrc.indexOf('title="账号管理"');
  const qrIdx = secSrc.indexOf('title="登录二维码"');
  const appearanceIdx = secSrc.indexOf('title="外观"');
  assert.ok(acctIdx > 0);
  assert.ok(qrIdx > acctIdx, "登录二维码 should be placed directly below 账号管理");
  assert.ok(qrIdx < appearanceIdx);
  assert.ok(secSrc.includes('title="问题与建议反馈"'));
  assert.ok(!secSrc.includes("<AuthorFeedbackModal"));

  const paletteSrc = fs.readFileSync(path.join(SRC_ROOT, "lib/palette.ts"), "utf8");
  const firstThemeMatch = /BRAND_THEMES:\s*BrandThemeSpec\[\]\s*=\s*\[\s*\{\s*key:\s*"([^"]+)"/.exec(
    paletteSrc
  );
  assert.equal(firstThemeMatch?.[1], "WARM_ORANGE");

  const appSrc = fs.readFileSync(path.join(SRC_ROOT, "App.tsx"), "utf8");
  assert.ok(appSrc.includes('themeOption: "WARM_ORANGE"'));
  assert.ok(appSrc.includes("compactFeed: true"));

  const forbidden =
    /\bcursor-(?:ns-resize|ew-resize|col-resize|row-resize|nesw-resize|nwse-resize|zoom-in|zoom-out|grab|grabbing|text)\b|cursor:\s*(?:ns-resize|ew-resize|col-resize|row-resize|zoom-in|zoom-out|grab|grabbing|text)\b/i;

  function walk(dir) {
    const out = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      if (entry.name === "__tests__" || entry.name === "node_modules") continue;
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) out.push(...walk(full));
      else if (/\.(ts|tsx|css)$/.test(entry.name)) out.push(full);
    }
    return out;
  }

  for (const f of walk(SRC_ROOT)) {
    const lines = fs.readFileSync(f, "utf8").split("\n");
    lines.forEach((line, i) => {
      const t = line.trim();
      if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) return;
      assert.equal(
        forbidden.test(line),
        false,
        `Forbidden cursor style at ${path.relative(SRC_ROOT, f)}:${i + 1}: ${t}`
      );
    });
  }
});

test("All Desktop and Mobile release version manifests stay unified at >= 1.1.0", () => {
  const repoRoot = path.resolve(__dirname, "..");
  const pkgJson = JSON.parse(
    fs.readFileSync(path.join(repoRoot, "desktop-tauri/package.json"), "utf8")
  );
  const tauriConf = JSON.parse(
    fs.readFileSync(path.join(repoRoot, "desktop-tauri/src-tauri/tauri.conf.json"), "utf8")
  );
  const cargoToml = fs.readFileSync(
    path.join(repoRoot, "desktop-tauri/src-tauri/Cargo.toml"),
    "utf8"
  );
  const diagTs = fs.readFileSync(
    path.join(repoRoot, "desktop-tauri/src/lib/diagnostics.ts"),
    "utf8"
  );
  const gradleKts = fs.readFileSync(
    path.join(repoRoot, "composeApp/build.gradle.kts"),
    "utf8"
  );
  const ktDiag = fs.readFileSync(
    path.join(
      repoRoot,
      "composeApp/src/commonMain/kotlin/do_/linux/lindo/core/diagnostics/LinDoDiagnosticLogger.kt"
    ),
    "utf8"
  );

  const cargoVer = /^version\s*=\s*"([^"]+)"/m.exec(cargoToml)?.[1];
  const diagVer = /DESKTOP_APP_VERSION\s*=\s*"([^"]+)"/.exec(diagTs)?.[1];
  const gradleVer = /versionName\s*=\s*"([^"]+)"/.exec(gradleKts)?.[1];
  const gradleCode = Number(/versionCode\s*=\s*(\d+)/.exec(gradleKts)?.[1]);
  const ktVer = /DEFAULT_APP_VERSION_NAME:\s*String\s*=\s*"([^"]+)"/.exec(ktDiag)?.[1];
  const ktCode = Number(/DEFAULT_APP_BUILD:\s*Int\s*=\s*(\d+)/.exec(ktDiag)?.[1]);

  assert.match(pkgJson.version, /^\d+\.\d+\.\d+$/);
  const [maj, min] = pkgJson.version.split(".").map(Number);
  assert.ok(maj > 1 || (maj === 1 && min >= 1), `Expected >= 1.1.0, got ${pkgJson.version}`);
  assert.equal(tauriConf.version, pkgJson.version);
  assert.equal(cargoVer, pkgJson.version);
  assert.equal(diagVer, pkgJson.version);
  assert.equal(gradleVer, pkgJson.version);
  assert.equal(ktVer, pkgJson.version);
  assert.ok(gradleCode >= 100);
  assert.equal(ktCode, gradleCode);
});

test("Discourse topic unread & trailing deleted floor resolution match Mobile 1:1", () => {
  // 1. Unseen 50-floor new topic clears unread dot immediately once visited (localReadFloor >= 1)
  assert.deepEqual(
    resolveDiscourseTopicUnread({
      unseen: true,
      unreadPosts: 0,
      highestPostNumber: 50,
      postsCount: 50,
      localReadFloor: 0,
    }),
    {
      effectiveUnseen: true,
      effectiveUnreadPosts: 0,
      isUnread: true,
      isReadDimmed: false,
    }
  );
  assert.deepEqual(
    resolveDiscourseTopicUnread({
      unseen: true,
      unreadPosts: 0,
      highestPostNumber: 50,
      postsCount: 50,
      localReadFloor: 1,
    }),
    {
      effectiveUnseen: false,
      effectiveUnreadPosts: 0,
      isUnread: false,
      isReadDimmed: true,
    }
  );

  // 2. Tracked topic with unreadPosts=3 stays unread until final visible floor is reached
  const partial = resolveDiscourseTopicUnread({
    unseen: false,
    unreadPosts: 3,
    highestPostNumber: 15,
    postsCount: 14,
    lastReadPostNumber: 12,
    localReadFloor: 13,
  });
  assert.equal(partial.isUnread, true);
  assert.equal(partial.effectiveUnreadPosts, 2);

  // Final visible post is #14 (id=914 == streamIds.at(-1)) while #15 was deleted -> advances to 15
  const alignedFloor = resolveEffectiveViewedFloor({
    rawMaxFloor: 14,
    posts: [
      { id: 901, postNumber: 1 },
      { id: 914, postNumber: 14 },
    ],
    streamIds: [901, 914],
    topicHighestPostNumber: 15,
    detailHighestPostNumber: 15,
    detailPostsCount: 14,
  });
  assert.equal(alignedFloor, 15);

  const cleared = resolveDiscourseTopicUnread({
    unseen: false,
    unreadPosts: 3,
    highestPostNumber: 15,
    postsCount: 14,
    lastReadPostNumber: 12,
    localReadFloor: alignedFloor,
  });
  assert.equal(cleared.isUnread, false);
  assert.equal(cleared.isReadDimmed, true);
});

function makeMockTopic(id, opts = {}) {
  const postsCount = opts.postsCount ?? 1;
  const highestPostNumber = opts.highestPostNumber ?? postsCount;
  return {
    id,
    pinned: Boolean(opts.pinned),
    postsCount,
    highestPostNumber,
    replyCount: Math.max(0, postsCount - 1),
    views: opts.views ?? 10,
    likeCount: opts.likeCount ?? 0,
    unseen: Boolean(opts.unseen),
    unreadPosts: opts.unreadPosts ?? 0,
    ...(opts.lastReadPostNumber !== undefined
      ? { lastReadPostNumber: opts.lastReadPostNumber }
      : {}),
  };
}

function makeRangeTopics(startDesc, endDesc, opts = {}) {
  const list = [];
  for (let id = startDesc; id >= endDesc; id -= 1) {
    list.push(makeMockTopic(id, opts));
  }
  return list;
}

test("Pending feed banner state machine matches Mobile PendingFeedStateMachine.kt 1:1", () => {
  // 1. Exact count formatting (eliminates 24 -> 20+ inversion and caps cleanly at 99+)
  assert.equal(formatPendingNewCount(0), "");
  assert.equal(formatPendingNewCount(-2), "");
  assert.equal(formatPendingNewCount(1), "1");
  assert.equal(formatPendingNewCount(24), "24");
  assert.equal(formatPendingNewCount(25), "25");
  assert.equal(formatPendingNewCount(52), "52");
  assert.equal(formatPendingNewCount(99), "99");
  assert.equal(formatPendingNewCount(100), "99+");

  // 2. Multi-poll cumulative count across 75s intervals (15 -> 35 -> ... -> 99+)
  const baseTopics = makeRangeTopics(100, 71, { postsCount: 3 });
  let snapshot = createEmptyPendingFeedSnapshot();

  const poll1 = [
    ...makeRangeTopics(115, 101, { postsCount: 1 }),
    ...makeRangeTopics(100, 86, { postsCount: 3 }),
  ];
  snapshot = evaluatePendingFeedPoll(snapshot, baseTopics, poll1, 75_000);
  assert.equal(countEffectivePendingTopics(snapshot, baseTopics), 15);

  const poll2 = makeRangeTopics(135, 106, { postsCount: 1 });
  snapshot = evaluatePendingFeedPoll(snapshot, baseTopics, poll2, 150_000);
  assert.equal(countEffectivePendingTopics(snapshot, baseTopics), 35);

  for (let round = 0; round < 5; round += 1) {
    const topId = 165 + round * 30;
    const page0 = makeRangeTopics(topId, topId - 29, { postsCount: 1 });
    snapshot = evaluatePendingFeedPoll(snapshot, baseTopics, page0, 225_000 + round * 75_000);
  }
  const capped = countEffectivePendingTopics(snapshot, baseTopics);
  assert.equal(capped, MAX_TRACKED_PENDING_TOPICS);
  assert.equal(formatPendingNewCount(capped), "99+");

  // 3. Deduplicates repeated bumps of the same topic & ignores pinned topics
  const pinned = makeMockTopic(9999, { postsCount: 10, pinned: true });
  const regular = makeRangeTopics(100, 72, { postsCount: 2 });
  const currentWithPinned = [pinned, ...regular];
  let bumpSnap = evaluatePendingFeedPoll(
    createEmptyPendingFeedSnapshot(),
    currentWithPinned,
    [makeMockTopic(9999, { postsCount: 30, pinned: true }), ...regular],
    75_000
  );
  assert.equal(countEffectivePendingTopics(bumpSnap, currentWithPinned), 0);

  for (let step = 1; step <= 4; step += 1) {
    const bumped88 = makeMockTopic(88, { postsCount: 2 + step * 3 });
    const fresh = [pinned, bumped88, ...regular.filter((t) => t.id !== 88)];
    bumpSnap = evaluatePendingFeedPoll(bumpSnap, currentWithPinned, fresh, 75_000 * step);
    assert.equal(countEffectivePendingTopics(bumpSnap, currentWithPinned), 1);
  }

  // 4. Direct topic open in split-view deducts count in real time and vanishes when 0
  const syncedTopics = currentWithPinned.map((t) =>
    t.id === 88 ? { ...t, postsCount: 14, highestPostNumber: 14 } : t
  );
  assert.equal(countEffectivePendingTopics(bumpSnap, syncedTopics), 0);

  // 5. Fresh (< 90s) overlapping snapshot merges seamlessly & never regresses visited read state
  const currentTwoPages = makeRangeTopics(100, 41, { postsCount: 3 }).map((t) =>
    t.id === 98
      ? makeMockTopic(98, {
          postsCount: 10,
          highestPostNumber: 10,
          unseen: false,
          unreadPosts: 0,
          lastReadPostNumber: 10,
        })
      : t
  );
  const freshOverlapping = [
    makeMockTopic(102, { postsCount: 1, unseen: true }),
    makeMockTopic(101, { postsCount: 1, unseen: true }),
    makeMockTopic(100, { postsCount: 3 }),
    makeMockTopic(99, { postsCount: 3 }),
    makeMockTopic(98, {
      postsCount: 10,
      highestPostNumber: 10,
      unseen: true,
      unreadPosts: 2,
      lastReadPostNumber: 8,
    }),
    ...makeRangeTopics(97, 73, { postsCount: 3 }),
  ];
  const overlapSnap = evaluatePendingFeedPoll(
    createEmptyPendingFeedSnapshot(),
    makeRangeTopics(100, 41, { postsCount: 3 }).map((t) =>
      t.id === 98 ? makeMockTopic(98, { postsCount: 10 }) : t
    ),
    freshOverlapping,
    100_000
  );
  const overlapDecision = resolvePendingFeedApply(overlapSnap, currentTwoPages, 120_000);
  assert.equal(overlapDecision.kind, "apply_instant");
  assert.equal(overlapDecision.resetPageToZero, false);
  assert.equal(overlapDecision.nextTopics.length, 62);
  const merged98 = overlapDecision.nextTopics.find((t) => t.id === 98);
  assert.equal(merged98.unseen, false);
  assert.equal(merged98.unreadPosts, 0);
  assert.equal(merged98.lastReadPostNumber, 10);

  // 6. Fresh (< 90s) disjoint page turnover replaces cleanly & sets resetPageToZero=true (no middle hole)
  const freshDisjointWithBumpedOld = [
    ...makeRangeTopics(145, 117, { postsCount: 1 }),
    makeMockTopic(55, { postsCount: 9 }),
  ];
  const disjointSnap = evaluatePendingFeedPoll(
    createEmptyPendingFeedSnapshot(),
    currentTwoPages,
    freshDisjointWithBumpedOld,
    200_000
  );
  const disjointDecision = resolvePendingFeedApply(disjointSnap, currentTwoPages, 215_000);
  assert.equal(disjointDecision.kind, "apply_instant");
  assert.equal(disjointDecision.resetPageToZero, true);
  assert.equal(disjointDecision.nextTopics.length, 30);
  assert.equal(disjointDecision.nextTopics[0].id, 145);
  assert.equal(disjointDecision.nextTopics[29].id, 55);

  // 7. Stale snapshot (>= 90s, e.g. waiting 1 hour) forces live network fetch instead of applying stale list
  assert.equal(
    resolvePendingFeedApply(overlapSnap, currentTwoPages, 100_000 + STALE_PENDING_SNAPSHOT_MS - 1)
      .kind,
    "apply_instant"
  );
  assert.equal(
    resolvePendingFeedApply(overlapSnap, currentTwoPages, 100_000 + STALE_PENDING_SNAPSHOT_MS)
      .kind,
    "fetch_live"
  );
  assert.equal(
    resolvePendingFeedApply(overlapSnap, currentTwoPages, 100_000 + 3_600_000).kind,
    "fetch_live"
  );
});

test("Desktop ECH 4-char labels, macOS challenge tunnel, and CF dismiss/typing UX contracts", () => {
  const repoRoot = path.resolve(__dirname, "..");
  const secondarySrc = fs.readFileSync(
    path.join(repoRoot, "desktop-tauri/src/components/SecondaryScreens.tsx"),
    "utf8"
  );
  assert.ok(secondarySrc.includes('{ key: "smart_auto" as const, label: "智能推荐" }'));
  assert.ok(secondarySrc.includes('{ key: "ech_priority" as const, label: "直连优先" }'));
  assert.ok(secondarySrc.includes('{ key: "system_default" as const, label: "系统默认" }'));
  assert.equal(secondarySrc.includes("智能模式（推荐）"), false);
  assert.ok(secondarySrc.includes("切换后立即生效，无需重启应用。"));

  const macWebkitSrc = fs.readFileSync(
    path.join(repoRoot, "desktop-tauri/src-tauri/native/macos_webkit_bridge.m"),
    "utf8"
  );
  assert.ok(
    macWebkitSrc.includes('nw_proxy_config_add_match_domain(cfg, "challenges.cloudflare.com")'),
    "macOS WebKit proxy must route challenges.cloudflare.com through local ECH transparent TCP tunnel"
  );
  assert.equal(
    macWebkitSrc.includes('nw_proxy_config_add_excluded_domain(cfg, "challenges.cloudflare.com")'),
    false,
    "macOS WebKit proxy must not exclude challenges.cloudflare.com onto split exit IP"
  );
  assert.ok(macWebkitSrc.includes("isCloudflareDomainCookieName(cookie.name)"));

  const appSrc = fs.readFileSync(path.join(repoRoot, "desktop-tauri/src/App.tsx"), "utf8");
  assert.ok(appSrc.includes('emit("lindo://ui-activity"'));
  assert.ok(appSrc.includes("连接需安全确认 · 去验证"));
});
