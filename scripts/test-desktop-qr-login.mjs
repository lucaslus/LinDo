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
