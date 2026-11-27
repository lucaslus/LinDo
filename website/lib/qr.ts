import { SITE } from "./site";

/**
 * 零依赖轻量 QR Code SVG 路径生成器（ISO/IEC 18004，Byte 模式，纠错等级 L，支持 Version 1~6，最长 134 字节）
 * 用于桌面端生成「手机扫码下载 Android 版」矢量二维码，自动跟随深浅主题色。
 */

// Version 1..6 参数（ECC Level L）：[总码字数, 纠错码字数, 块数]
const VERSION_SPEC: readonly [totalCodewords: number, ecCodewordsPerBlock: number, numBlocks: number][] = [
  [0, 0, 0],
  [26, 7, 1], // v1: 21x21, max 17 bytes
  [44, 10, 1], // v2: 25x25, max 32 bytes
  [70, 15, 1], // v3: 29x29, max 53 bytes
  [100, 20, 1], // v4: 33x33, max 78 bytes
  [134, 26, 1], // v5: 37x37, max 106 bytes
  [172, 18, 2], // v6: 41x41, max 134 bytes (2 blocks of 68 data + 18 ec)
];

const ALIGNMENT_POS: readonly (readonly number[])[] = [
  [],
  [],
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
];

const GF_EXP = new Uint8Array(512);
const GF_LOG = new Uint8Array(256);
(() => {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x;
    GF_LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
})();

function gfMul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  return GF_EXP[GF_LOG[a] + GF_LOG[b]];
}

function rsGeneratorPoly(degree: number): Uint8Array {
  let poly = new Uint8Array([1]);
  for (let i = 0; i < degree; i++) {
    const next = new Uint8Array(poly.length + 1);
    const root = GF_EXP[i];
    for (let j = 0; j < poly.length; j++) {
      next[j] ^= poly[j];
      next[j + 1] ^= gfMul(poly[j], root);
    }
    poly = next;
  }
  return poly;
}

function rsRemainder(data: Uint8Array, ecLen: number): Uint8Array {
  const gen = rsGeneratorPoly(ecLen);
  const rem = new Uint8Array(ecLen);
  for (let i = 0; i < data.length; i++) {
    const factor = data[i] ^ rem[0];
    rem.copyWithin(0, 1);
    rem[ecLen - 1] = 0;
    for (let j = 0; j < ecLen; j++) {
      rem[j] ^= gfMul(gen[j + 1], factor);
    }
  }
  return rem;
}

function formatBits(mask: number): number {
  // ECC Level L = 01
  const data = (0b01 << 3) | mask;
  let rem = data << 10;
  for (let i = 14; i >= 10; i--) {
    if ((rem >> i) & 1) rem ^= 0x537 << (i - 10);
  }
  return ((data << 10) | rem) ^ 0x5412;
}

export function encodeQrSvg(text: string): { size: number; path: string } {
  const bytes = new TextEncoder().encode(text);
  let version = 1;
  while (version <= 6) {
    const [total, ecPerBlock, blocks] = VERSION_SPEC[version];
    const dataCapacity = total - ecPerBlock * blocks - 2; // 4-bit mode + 8-bit len + 4-bit term ≈ 2 bytes
    if (bytes.length <= dataCapacity) break;
    version++;
  }
  if (version > 6) {
    return encodeQrSvg(`${SITE.url}/#download`);
  }

  const [totalCw, ecPerBlock, numBlocks] = VERSION_SPEC[version];
  const totalDataCw = totalCw - ecPerBlock * numBlocks;

  // Build bit buffer: Mode 0100 (Byte) + 8-bit length + bytes + terminator + pad bytes
  const bits: number[] = [];
  const pushBits = (val: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >> i) & 1);
  };
  pushBits(0b0100, 4);
  pushBits(bytes.length, 8);
  for (const b of bytes) pushBits(b, 8);
  const maxBits = totalDataCw * 8;
  const term = Math.min(4, maxBits - bits.length);
  pushBits(0, term);
  while (bits.length % 8 !== 0) bits.push(0);

  const dataCw = new Uint8Array(totalDataCw);
  for (let i = 0; i < bits.length / 8; i++) {
    let byte = 0;
    for (let b = 0; b < 8; b++) byte = (byte << 1) | bits[i * 8 + b];
    dataCw[i] = byte;
  }
  const padBytes = [0xec, 0x11];
  for (let i = bits.length / 8, p = 0; i < totalDataCw; i++, p ^= 1) {
    dataCw[i] = padBytes[p];
  }

  // Split into blocks & interleave
  const blockLen = totalDataCw / numBlocks;
  const dataBlocks: Uint8Array[] = [];
  const ecBlocks: Uint8Array[] = [];
  for (let b = 0; b < numBlocks; b++) {
    const slice = dataCw.subarray(b * blockLen, (b + 1) * blockLen);
    dataBlocks.push(slice);
    ecBlocks.push(rsRemainder(slice, ecPerBlock));
  }

  const finalCw: number[] = [];
  for (let i = 0; i < blockLen; i++) {
    for (let b = 0; b < numBlocks; b++) finalCw.push(dataBlocks[b][i]);
  }
  for (let i = 0; i < ecPerBlock; i++) {
    for (let b = 0; b < numBlocks; b++) finalCw.push(ecBlocks[b][i]);
  }

  const dim = version * 4 + 17;
  const baseGrid = Array.from({ length: dim }, () => new Uint8Array(dim));
  const isFunc = Array.from({ length: dim }, () => new Uint8Array(dim));

  const setFunc = (r: number, c: number, dark: boolean) => {
    if (r < 0 || r >= dim || c < 0 || c >= dim) return;
    baseGrid[r][c] = dark ? 1 : 0;
    isFunc[r][c] = 1;
  };

  const drawFinder = (top: number, left: number) => {
    for (let dr = -1; dr <= 7; dr++) {
      for (let dc = -1; dc <= 7; dc++) {
        const inOuter = dr >= 0 && dr <= 6 && dc >= 0 && dc <= 6;
        const onBorder = dr === 0 || dr === 6 || dc === 0 || dc === 6;
        const inCenter = dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4;
        setFunc(top + dr, left + dc, inOuter && (onBorder || inCenter));
      }
    }
  };
  drawFinder(0, 0);
  drawFinder(0, dim - 7);
  drawFinder(dim - 7, 0);

  // Timing patterns
  for (let i = 8; i < dim - 8; i++) {
    setFunc(6, i, i % 2 === 0);
    setFunc(i, 6, i % 2 === 0);
  }

  // Alignment patterns
  const align = ALIGNMENT_POS[version];
  for (const r of align) {
    for (const c of align) {
      if ((r === 6 && c === 6) || (r === 6 && c === dim - 7) || (r === dim - 7 && c === 6)) continue;
      for (let dr = -2; dr <= 2; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          setFunc(r + dr, c + dc, Math.max(Math.abs(dr), Math.abs(dc)) !== 1);
        }
      }
    }
  }

  // Dark module & reserve format areas
  setFunc(dim - 8, 8, true);
  for (let i = 0; i < 9; i++) {
    if (i !== 6) {
      if (!isFunc[8][i]) isFunc[8][i] = 1;
      if (!isFunc[i][8]) isFunc[i][8] = 1;
    }
  }
  for (let i = 0; i < 8; i++) {
    isFunc[8][dim - 1 - i] = 1;
    isFunc[dim - 1 - i][8] = 1;
  }

  // Zigzag placement
  let bitIdx = 0;
  const totalBits = finalCw.length * 8;
  let upward = true;
  for (let right = dim - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < dim; vert++) {
      const r = upward ? dim - 1 - vert : vert;
      for (let j = 0; j < 2; j++) {
        const c = right - j;
        if (!isFunc[r][c]) {
          const dark = bitIdx < totalBits ? ((finalCw[bitIdx >> 3] >> (7 - (bitIdx & 7))) & 1) === 1 : false;
          baseGrid[r][c] = dark ? 1 : 0;
          bitIdx++;
        }
      }
    }
    upward = !upward;
  }

  const applyMaskAndFormat = (mask: number) => {
    const g = baseGrid.map((row) => Uint8Array.from(row));
    for (let r = 0; r < dim; r++) {
      for (let c = 0; c < dim; c++) {
        if (isFunc[r][c]) continue;
        const invert =
          mask === 0
            ? (r + c) % 2 === 0
            : mask === 1
              ? r % 2 === 0
              : mask === 2
                ? c % 3 === 0
                : (r + c) % 3 === 0;
        if (invert) g[r][c] ^= 1;
      }
    }
    const fmt = formatBits(mask);
    for (let i = 0; i <= 5; i++) g[8][i] = (fmt >> (14 - i)) & 1;
    g[8][7] = (fmt >> 8) & 1;
    g[8][8] = (fmt >> 7) & 1;
    g[7][8] = (fmt >> 6) & 1;
    for (let i = 9; i < 15; i++) g[14 - i][8] = (fmt >> (14 - i)) & 1;

    for (let i = 0; i < 8; i++) g[8][dim - 1 - i] = (fmt >> i) & 1;
    for (let i = 8; i < 15; i++) g[dim - 15 + i][8] = (fmt >> i) & 1;
    g[dim - 8][8] = 1;
    return g;
  };

  // Pick lowest-penalty mask among 0..3
  let bestGrid = applyMaskAndFormat(0);
  let bestScore = Infinity;
  for (let m = 0; m < 4; m++) {
    const g = applyMaskAndFormat(m);
    let score = 0;
    for (let r = 0; r < dim; r++) {
      let run = 1;
      for (let c = 1; c < dim; c++) {
        if (g[r][c] === g[r][c - 1]) {
          run++;
          if (run === 5) score += 3;
          else if (run > 5) score += 1;
        } else run = 1;
      }
    }
    for (let r = 0; r < dim - 1; r++) {
      for (let c = 0; c < dim - 1; c++) {
        const v = g[r][c];
        if (v === g[r][c + 1] && v === g[r + 1][c] && v === g[r + 1][c + 1]) score += 3;
      }
    }
    if (score < bestScore) {
      bestScore = score;
      bestGrid = g;
    }
  }

  // Convert dark modules into compact SVG horizontal run paths (with 2-module quiet zone)
  const pad = 2;
  const parts: string[] = [];
  for (let r = 0; r < dim; r++) {
    let c = 0;
    while (c < dim) {
      if (bestGrid[r][c] === 1) {
        let end = c + 1;
        while (end < dim && bestGrid[r][end] === 1) end++;
        parts.push(`M${c + pad} ${r + pad}h${end - c}v1h-${end - c}z`);
        c = end;
      } else {
        c++;
      }
    }
  }

  return { size: dim + pad * 2, path: parts.join("") };
}
