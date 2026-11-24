import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import {
  decodeQrFromRgba,
  decodeQrMatrix,
  encodeQrLoginPayload,
  encodeQrMatrix,
  encodeQrSvg,
  isQrLoginUri,
  parseQrLoginPayload,
} from "../lib/qrLogin";

describe("Cross-device QR login protocol & ISO/IEC 18004 codec", () => {
  it("parses and encodes lindo:// and fluxdo:// v2 payloads with exact mobile parity", () => {
    const now = 1_795_436_436_560;
    const uri = encodeQrLoginPayload("key_64_hex", "otp_64_hex", "中文昵称_lucas", 0);
    expect(isQrLoginUri(uri)).toBe(true);

    const parsed = parseQrLoginPayload(uri, now);
    expect(parsed.valid).toBe(true);
    if (parsed.valid) {
      expect(parsed.payload).toEqual({
        version: 2,
        apiKey: "key_64_hex",
        otp: "otp_64_hex",
        username: "中文昵称_lucas",
        expiresAtEpochMs: 0,
      });
    }

    const flux = parseQrLoginPayload(
      "fluxdo://qr-login?v=2&k=flux_k&o=flux_o&u=alice&exp=1895436436560",
      now
    );
    expect(flux.valid).toBe(true);

    const expired = parseQrLoginPayload(
      "lindo://qr-login?v=2&k=k1&o=o1&u=bob&exp=1700000000000",
      now
    );
    expect(expired).toEqual({ valid: false, message: "登录二维码已过期，请重新生成" });

    const unsupported = parseQrLoginPayload("lindo://qr-login?v=9&k=k1&o=o1", now);
    expect(unsupported).toEqual({
      valid: false,
      message: "二维码版本不受支持，请升级客户端",
    });

    const otherUrl = parseQrLoginPayload("https://lindo.app/#download", now);
    expect(otherUrl).toEqual({ valid: false, message: "不是 Lindo 登录二维码" });
  });

  it("round-trips long multi-block QR payloads across L/M/Q/H with Reed-Solomon error recovery", () => {
    const longUri = encodeQrLoginPayload(
      "a1b2c3d4".repeat(8),
      "f0e1d2c3".repeat(8),
      "测试长用户名_跨端扫码_2026",
      1_895_436_436_560
    );
    expect(longUri.length).toBeGreaterThan(150);

    const svg = encodeQrSvg(longUri, 2, "M");
    expect(svg.size).toBeGreaterThan(45);
    expect(svg.path.startsWith("M")).toBe(true);

    for (const ecc of ["L", "M", "Q", "H"] as const) {
      const mat = encodeQrMatrix(longUri, ecc);
      expect(decodeQrMatrix(mat)).toBe(longUri);

      const damaged = mat.map((row) => Uint8Array.from(row));
      for (let t = 0; t < 5; t++) {
        const rr = 10 + ((t * 7) % (damaged.length - 20));
        const cc = 10 + ((t * 11) % (damaged.length - 20));
        damaged[rr][cc] ^= 1;
      }
      expect(decodeQrMatrix(damaged)).toBe(longUri);
    }
  });

  it("decodes QR code from simulated full-screen mobile screenshot RGBA buffer", () => {
    const uri = encodeQrLoginPayload("k_32_abcdef012345", "o_32_987654fedcba", "lucas", 0);
    const mat = encodeQrMatrix(uri, "M");
    const dim = mat.length;
    const W = 720;
    const H = 1560;
    const rgba = new Uint8ClampedArray(W * H * 4);
    for (let i = 0; i < W * H; i++) {
      rgba[i * 4] = 20;
      rgba[i * 4 + 1] = 22;
      rgba[i * 4 + 2] = 28;
      rgba[i * 4 + 3] = 255;
    }

    const card = 420;
    const cx0 = Math.floor((W - card) / 2);
    const cy0 = 420;
    for (let y = cy0; y < cy0 + card; y++) {
      for (let x = cx0; x < cx0 + card; x++) {
        const idx = (y * W + x) * 4;
        rgba[idx] = 255;
        rgba[idx + 1] = 255;
        rgba[idx + 2] = 255;
      }
    }

    const scale = 6;
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

    expect(decodeQrFromRgba(rgba, W, H)).toBe(uri);
  });

  it("places standalone 登录二维码 entry directly below 账号管理 in PC settings home", () => {
    const src = fs.readFileSync(
      path.resolve(__dirname, "../components/SecondaryScreens.tsx"),
      "utf8"
    );
    const acctIdx = src.indexOf('title="账号管理"');
    const qrIdx = src.indexOf('title="登录二维码"');
    const appearanceIdx = src.indexOf('title="外观"');
    expect(acctIdx).toBeGreaterThan(0);
    expect(qrIdx).toBeGreaterThan(acctIdx);
    expect(qrIdx).toBeLessThan(appearanceIdx);
    expect(src).toContain('title="问题与建议反馈"');
    expect(src).not.toContain("<AuthorFeedbackModal");
  });
});
