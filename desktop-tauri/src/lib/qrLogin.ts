/**
 * 跨端扫码登录协议编解码、ISO/IEC 18004 二维码矢量生成（v1~v20）与本地图片二维码识别器
 * 对齐移动端 `QrLoginManager.kt` 与 FluxDO / CFluxDo v2 协议：
 * `lindo://qr-login?v=2&k=<api_key>&o=<otp>&u=<username>&exp=<unix_ms|0>`
 */

export const QR_LOGIN_CURRENT_VERSION = 2;

export interface QrLoginPayload {
  version: number;
  apiKey: string;
  otp: string;
  username: string;
  expiresAtEpochMs: number;
}

export type QrParseResult =
  | { valid: true; payload: QrLoginPayload }
  | { valid: false; message: string };

function extractSchemeAndAction(raw: string): { scheme: string; action: string; query: string } | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  const schemeMatch = /^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/(.*)$/.exec(trimmed);
  if (!schemeMatch) return null;
  const scheme = schemeMatch[1].toLowerCase();
  const rest = schemeMatch[2];
  const qIdx = rest.indexOf("?");
  const authorityAndPath = (qIdx >= 0 ? rest.slice(0, qIdx) : rest).replace(/^\/+/, "");
  const query = qIdx >= 0 ? rest.slice(qIdx + 1).split("#")[0] : "";
  const action = (authorityAndPath.split("/")[0] || "").toLowerCase();
  return { scheme, action, query };
}

function decodeFormComponent(raw: string): string | null {
  try {
    return decodeURIComponent(raw.replace(/\+/g, "%20"));
  } catch {
    return null;
  }
}

export function isQrLoginUri(raw: string | null | undefined): boolean {
  if (!raw) return false;
  const parsed = extractSchemeAndAction(raw);
  if (!parsed) return false;
  return (parsed.scheme === "lindo" || parsed.scheme === "fluxdo") && parsed.action === "qr-login";
}

export function encodeQrLoginPayload(
  apiKey: string,
  otp: string,
  username: string,
  expiresAtEpochMs = 0
): string {
  const exp = Math.max(0, Math.trunc(expiresAtEpochMs));
  return (
    `lindo://qr-login?v=${QR_LOGIN_CURRENT_VERSION}` +
    `&k=${encodeURIComponent(apiKey)}` +
    `&o=${encodeURIComponent(otp)}` +
    `&u=${encodeURIComponent(username)}` +
    `&exp=${exp}`
  );
}

export function parseQrLoginPayload(raw: string, nowMs: number = Date.now()): QrParseResult {
  const trimmed = (raw || "").trim();
  if (!trimmed) {
    return { valid: false, message: "无效的登录二维码" };
  }
  const parts = extractSchemeAndAction(trimmed);
  if (!parts) {
    return { valid: false, message: "无效的登录二维码" };
  }
  if ((parts.scheme !== "lindo" && parts.scheme !== "fluxdo") || parts.action !== "qr-login") {
    return { valid: false, message: "不是 Lindo 登录二维码" };
  }
  const params = new Map<string, string>();
  if (parts.query) {
    for (const seg of parts.query.split("&")) {
      const eq = seg.indexOf("=");
      if (eq <= 0) continue;
      const k = decodeFormComponent(seg.slice(0, eq));
      const v = decodeFormComponent(seg.slice(eq + 1));
      if (k !== null && v !== null) {
        params.set(k, v);
      }
    }
  }
  const vRaw = (params.get("v") || "").trim();
  const version = /^\d+$/.test(vRaw) ? Number.parseInt(vRaw, 10) : NaN;
  if (!Number.isFinite(version)) {
    return { valid: false, message: "无效的登录二维码" };
  }
  if (version !== QR_LOGIN_CURRENT_VERSION) {
    return { valid: false, message: "二维码版本不受支持，请升级客户端" };
  }
  const apiKey = (params.get("k") || "").trim();
  const otp = (params.get("o") || "").trim();
  const username = (params.get("u") || "").trim();
  const expRaw = (params.get("exp") || "").trim() || "0";
  const expMs = /^\d+$/.test(expRaw) ? Number.parseInt(expRaw, 10) : -1;
  if (!apiKey || !otp || !Number.isFinite(expMs) || expMs < 0) {
    return { valid: false, message: "无效的登录二维码" };
  }
  if (expMs > 0 && nowMs > expMs) {
    return { valid: false, message: "登录二维码已过期，请重新生成" };
  }
  return {
    valid: true,
    payload: {
      version,
      apiKey,
      otp,
      username,
      expiresAtEpochMs: expMs,
    },
  };
}

/* ==================== ISO/IEC 18004 GF(256) 与表参数（Version 1~20，ECC L/M/Q/H） ==================== */

export type QrEccLevel = "L" | "M" | "Q" | "H";

const ECC_FORMAT_BITS: Record<QrEccLevel, number> = {
  L: 0b01,
  M: 0b00,
  Q: 0b11,
  H: 0b10,
};

const FORMAT_BITS_TO_ECC: readonly QrEccLevel[] = ["M", "L", "H", "Q"];

const ECC_CODEWORDS_PER_BLOCK: Record<QrEccLevel, readonly number[]> = {
  L: [0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28],
  M: [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26],
  Q: [0, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30],
  H: [0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28],
};

const NUM_ERROR_CORRECTION_BLOCKS: Record<QrEccLevel, readonly number[]> = {
  L: [0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8],
  M: [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16],
  Q: [0, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20],
  H: [0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25],
};

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

function gfInv(a: number): number {
  if (a === 0) throw new Error("GF(256) division by zero");
  return GF_EXP[255 - GF_LOG[a]];
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

function getAlignmentPositions(version: number): number[] {
  if (version <= 1) return [];
  const dim = version * 4 + 17;
  const numAlign = Math.floor(version / 7) + 2;
  const step = version === 32 ? 26 : Math.ceil((dim - 13) / (numAlign * 2 - 2)) * 2;
  const result = [6];
  for (let pos = dim - 7; result.length < numAlign; pos -= step) {
    result.splice(1, 0, pos);
  }
  return result;
}

function buildFunctionTemplate(version: number): {
  dim: number;
  baseGrid: Uint8Array[];
  isFunc: Uint8Array[];
  rawModuleBits: number;
} {
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

  for (let i = 8; i < dim - 8; i++) {
    setFunc(6, i, i % 2 === 0);
    setFunc(i, 6, i % 2 === 0);
  }

  const align = getAlignmentPositions(version);
  for (let i = 0; i < align.length; i++) {
    for (let j = 0; j < align.length; j++) {
      if (
        (i === 0 && j === 0) ||
        (i === 0 && j === align.length - 1) ||
        (i === align.length - 1 && j === 0)
      ) {
        continue;
      }
      const r = align[i];
      const c = align[j];
      for (let dr = -2; dr <= 2; dr++) {
        for (let dc = -2; dc <= 2; dc++) {
          setFunc(r + dr, c + dc, Math.max(Math.abs(dr), Math.abs(dc)) !== 1);
        }
      }
    }
  }

  // Dark module + Format Info reserved modules
  setFunc(dim - 8, 8, true);
  for (let i = 0; i < 9; i++) {
    if (i !== 6) {
      isFunc[8][i] = 1;
      isFunc[i][8] = 1;
    }
  }
  for (let i = 0; i < 8; i++) {
    isFunc[8][dim - 1 - i] = 1;
    isFunc[dim - 1 - i][8] = 1;
  }

  // Version Info (v7+)
  if (version >= 7) {
    let rem = version;
    for (let i = 0; i < 12; i++) {
      rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    }
    const vBits = (version << 12) | rem;
    for (let i = 0; i < 18; i++) {
      const dark = ((vBits >>> i) & 1) === 1;
      const a = Math.floor(i / 3);
      const b = dim - 11 + (i % 3);
      setFunc(a, b, dark);
      setFunc(b, a, dark);
    }
  }

  let rawModuleBits = 0;
  for (let r = 0; r < dim; r++) {
    for (let c = 0; c < dim; c++) {
      if (!isFunc[r][c]) rawModuleBits++;
    }
  }

  return { dim, baseGrid, isFunc, rawModuleBits };
}

function computeFormatBits(ecc: QrEccLevel, mask: number): number {
  const data = (ECC_FORMAT_BITS[ecc] << 3) | mask;
  let rem = data << 10;
  for (let i = 14; i >= 10; i--) {
    if ((rem >>> i) & 1) rem ^= 0x537 << (i - 10);
  }
  return ((data << 10) | rem) ^ 0x5412;
}

function shouldInvertMask(mask: number, r: number, c: number): boolean {
  switch (mask) {
    case 0:
      return (r + c) % 2 === 0;
    case 1:
      return r % 2 === 0;
    case 2:
      return c % 3 === 0;
    case 3:
      return (r + c) % 3 === 0;
    case 4:
      return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
    case 5:
      return ((r * c) % 2) + ((r * c) % 3) === 0;
    case 6:
      return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0;
    case 7:
      return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0;
    default:
      return false;
  }
}

function computePenaltyScore(grid: Uint8Array[], dim: number): number {
  let score = 0;
  // Rule 1: horizontal & vertical 5+ consecutive modules
  for (let r = 0; r < dim; r++) {
    let run = 1;
    for (let c = 1; c < dim; c++) {
      if (grid[r][c] === grid[r][c - 1]) {
        run++;
        if (run === 5) score += 3;
        else if (run > 5) score += 1;
      } else {
        run = 1;
      }
    }
  }
  for (let c = 0; c < dim; c++) {
    let run = 1;
    for (let r = 1; r < dim; r++) {
      if (grid[r][c] === grid[r - 1][c]) {
        run++;
        if (run === 5) score += 3;
        else if (run > 5) score += 1;
      } else {
        run = 1;
      }
    }
  }
  // Rule 2: 2x2 blocks
  for (let r = 0; r < dim - 1; r++) {
    for (let c = 0; c < dim - 1; c++) {
      const v = grid[r][c];
      if (v === grid[r][c + 1] && v === grid[r + 1][c] && v === grid[r + 1][c + 1]) {
        score += 3;
      }
    }
  }
  return score;
}

export function encodeQrMatrix(text: string, ecc: QrEccLevel = "M"): Uint8Array[] {
  const bytes = new TextEncoder().encode(text);
  let chosenVersion = 0;
  let template: ReturnType<typeof buildFunctionTemplate> | null = null;

  for (let v = 1; v <= 20; v++) {
    const tpl = buildFunctionTemplate(v);
    const totalCw = Math.floor(tpl.rawModuleBits / 8);
    const ecPerBlock = ECC_CODEWORDS_PER_BLOCK[ecc][v];
    const numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc][v];
    const totalDataCw = totalCw - ecPerBlock * numBlocks;
    const lenFieldBits = v <= 9 ? 8 : 16;
    const neededBits = 4 + lenFieldBits + bytes.length * 8;
    if (neededBits <= totalDataCw * 8) {
      chosenVersion = v;
      template = tpl;
      break;
    }
  }

  if (!template || chosenVersion === 0) {
    if (ecc !== "L") {
      return encodeQrMatrix(text, "L");
    }
    throw new Error("二维码内容过长，超出编码容量");
  }

  const { dim, baseGrid, isFunc, rawModuleBits } = template;
  const totalCw = Math.floor(rawModuleBits / 8);
  const ecPerBlock = ECC_CODEWORDS_PER_BLOCK[ecc][chosenVersion];
  const numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc][chosenVersion];
  const totalDataCw = totalCw - ecPerBlock * numBlocks;

  const bits: number[] = [];
  const pushBits = (val: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1);
  };
  pushBits(0b0100, 4);
  pushBits(bytes.length, chosenVersion <= 9 ? 8 : 16);
  for (const b of bytes) pushBits(b, 8);
  const maxBits = totalDataCw * 8;
  pushBits(0, Math.min(4, maxBits - bits.length));
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

  const numShortBlocks = numBlocks - (totalDataCw % numBlocks);
  const shortBlockLen = Math.floor(totalDataCw / numBlocks);
  const dataBlocks: Uint8Array[] = [];
  const ecBlocks: Uint8Array[] = [];
  let offset = 0;
  for (let b = 0; b < numBlocks; b++) {
    const datLen = shortBlockLen + (b < numShortBlocks ? 0 : 1);
    const slice = dataCw.subarray(offset, offset + datLen);
    offset += datLen;
    dataBlocks.push(slice);
    ecBlocks.push(rsRemainder(slice, ecPerBlock));
  }

  const finalCw: number[] = [];
  const maxDataLen = shortBlockLen + (numShortBlocks < numBlocks ? 1 : 0);
  for (let i = 0; i < maxDataLen; i++) {
    for (let b = 0; b < numBlocks; b++) {
      if (i < dataBlocks[b].length) finalCw.push(dataBlocks[b][i]);
    }
  }
  for (let i = 0; i < ecPerBlock; i++) {
    for (let b = 0; b < numBlocks; b++) {
      finalCw.push(ecBlocks[b][i]);
    }
  }

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
          const dark =
            bitIdx < totalBits ? ((finalCw[bitIdx >> 3] >>> (7 - (bitIdx & 7))) & 1) === 1 : false;
          baseGrid[r][c] = dark ? 1 : 0;
          bitIdx++;
        }
      }
    }
    upward = !upward;
  }

  const applyMaskAndFormat = (mask: number): Uint8Array[] => {
    const g = baseGrid.map((row) => Uint8Array.from(row));
    for (let r = 0; r < dim; r++) {
      for (let c = 0; c < dim; c++) {
        if (!isFunc[r][c] && shouldInvertMask(mask, r, c)) {
          g[r][c] ^= 1;
        }
      }
    }
    const fmt = computeFormatBits(ecc, mask);
    for (let i = 0; i <= 5; i++) g[8][i] = (fmt >>> (14 - i)) & 1;
    g[8][7] = (fmt >>> 8) & 1;
    g[8][8] = (fmt >>> 7) & 1;
    g[7][8] = (fmt >>> 6) & 1;
    for (let i = 9; i < 15; i++) g[14 - i][8] = (fmt >>> (14 - i)) & 1;

    for (let i = 0; i < 8; i++) g[8][dim - 1 - i] = (fmt >>> i) & 1;
    for (let i = 8; i < 15; i++) g[dim - 15 + i][8] = (fmt >>> i) & 1;
    g[dim - 8][8] = 1;
    return g;
  };

  let bestGrid = applyMaskAndFormat(0);
  let bestScore = Infinity;
  for (let m = 0; m < 8; m++) {
    const candidate = applyMaskAndFormat(m);
    const score = computePenaltyScore(candidate, dim);
    if (score < bestScore) {
      bestScore = score;
      bestGrid = candidate;
    }
  }
  return bestGrid;
}

export function encodeQrSvg(
  text: string,
  quietZone = 2,
  ecc: QrEccLevel = "M"
): { size: number; path: string } {
  const grid = encodeQrMatrix(text, ecc);
  const dim = grid.length;
  const pad = Math.max(0, quietZone);
  const parts: string[] = [];
  for (let r = 0; r < dim; r++) {
    let c = 0;
    while (c < dim) {
      if (grid[r][c] === 1) {
        let end = c + 1;
        while (end < dim && grid[r][end] === 1) end++;
        parts.push(`M${c + pad} ${r + pad}h${end - c}v1h-${end - c}z`);
        c = end;
      } else {
        c++;
      }
    }
  }
  return { size: dim + pad * 2, path: parts.join("") };
}

/* ==================== 本地二维码图片识别器（支持全屏手机截图、裁剪图、反色图与 RS 纠错） ==================== */

function popcount15(x: number): number {
  let v = x & 0x7fff;
  let c = 0;
  while (v) {
    v &= v - 1;
    c++;
  }
  return c;
}

function decodeFormatBits(raw1: number, raw2: number): { ecc: QrEccLevel; mask: number } | null {
  let bestDist = 4;
  let bestEcc: QrEccLevel = "M";
  let bestMask = 0;
  for (const ecc of ["L", "M", "Q", "H"] as const) {
    for (let mask = 0; mask < 8; mask++) {
      const target = computeFormatBits(ecc, mask);
      const d1 = popcount15(raw1 ^ target);
      const d2 = popcount15(raw2 ^ target);
      const d = Math.min(d1, d2);
      if (d < bestDist) {
        bestDist = d;
        bestEcc = ecc;
        bestMask = mask;
      }
    }
  }
  return bestDist <= 3 ? { ecc: bestEcc, mask: bestMask } : null;
}

function polyEval(poly: Uint8Array, x: number): number {
  if (x === 0) return poly[0];
  let acc = 0;
  for (let i = poly.length - 1; i >= 0; i--) {
    acc = gfMul(acc, x) ^ poly[i];
  }
  return acc;
}

function rsCorrectBlock(codewords: Uint8Array, ecLen: number): boolean {
  const n = codewords.length;
  const syndromes = new Uint8Array(ecLen);
  let hasError = false;
  for (let i = 0; i < ecLen; i++) {
    const root = GF_EXP[i];
    let s = 0;
    for (let j = 0; j < n; j++) {
      s = gfMul(s, root) ^ codewords[j];
    }
    syndromes[i] = s;
    if (s !== 0) hasError = true;
  }
  if (!hasError) return true;

  // Berlekamp-Massey error locator polynomial C(x) = 1 + c_1 x + ... + c_L x^L
  let C = new Uint8Array(ecLen + 1);
  let B = new Uint8Array(ecLen + 1);
  C[0] = 1;
  B[0] = 1;
  let L = 0;
  let m = 1;
  let b = 1;

  for (let nIdx = 0; nIdx < ecLen; nIdx++) {
    let d = syndromes[nIdx];
    for (let i = 1; i <= L; i++) {
      d ^= gfMul(C[i], syndromes[nIdx - i]);
    }
    if (d === 0) {
      m++;
    } else if (2 * L <= nIdx) {
      const T = Uint8Array.from(C);
      const scale = gfMul(d, gfInv(b));
      for (let i = 0; i + m <= ecLen; i++) {
        C[i + m] ^= gfMul(scale, B[i]);
      }
      L = nIdx + 1 - L;
      B = T;
      b = d;
      m = 1;
    } else {
      const scale = gfMul(d, gfInv(b));
      for (let i = 0; i + m <= ecLen; i++) {
        C[i + m] ^= gfMul(scale, B[i]);
      }
      m++;
    }
  }

  if (L * 2 > ecLen) return false;

  // Chien search: find roots X_k^{-1} of C(x)
  const errPositions: number[] = [];
  for (let i = 0; i < n; i++) {
    const xInv = GF_EXP[(255 - i) % 255];
    if (polyEval(C.subarray(0, L + 1), xInv) === 0) {
      const bytePos = n - 1 - i;
      if (bytePos < 0 || bytePos >= n) return false;
      errPositions.push(bytePos);
    }
  }
  if (errPositions.length !== L) return false;

  // Error evaluator Omega(x) = S(x) * C(x) mod x^{ecLen}
  const omega = new Uint8Array(ecLen);
  for (let i = 0; i < ecLen; i++) {
    let sum = 0;
    for (let j = 0; j <= i && j <= L; j++) {
      sum ^= gfMul(syndromes[i - j], C[j]);
    }
    omega[i] = sum;
  }

  // Formal derivative C'(x) over GF(2^8): odd terms c_1 + c_3 x^2 + ...
  for (const bytePos of errPositions) {
    const power = (n - 1 - bytePos) % 255;
    const xk = GF_EXP[power];
    const xInv = GF_EXP[(255 - power) % 255];
    const num = polyEval(omega, xInv);
    let den = 0;
    for (let j = 1; j <= L; j += 2) {
      let xPow = 1;
      for (let p = 0; p < j - 1; p++) xPow = gfMul(xPow, xInv);
      den ^= gfMul(C[j], xPow);
    }
    if (den === 0) return false;
    // Generator uses FCR = 0 (roots alpha^0 .. alpha^{ecLen-1}): e_k = X_k * Omega(X_k^{-1}) / C'(X_k^{-1})
    codewords[bytePos] ^= gfMul(xk, gfMul(num, gfInv(den)));
  }

  // Re-verify syndromes
  for (let i = 0; i < ecLen; i++) {
    const root = GF_EXP[i];
    let s = 0;
    for (let j = 0; j < n; j++) s = gfMul(s, root) ^ codewords[j];
    if (s !== 0) return false;
  }
  return true;
}

const ALPHANUM_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:";

function decodeDataBitStream(dataCw: Uint8Array, version: number): string | null {
  let bitPos = 0;
  const totalBits = dataCw.length * 8;
  const readBits = (count: number): number => {
    let val = 0;
    for (let i = 0; i < count; i++) {
      if (bitPos >= totalBits) return -1;
      const b = (dataCw[bitPos >> 3] >>> (7 - (bitPos & 7))) & 1;
      val = (val << 1) | b;
      bitPos++;
    }
    return val;
  };

  const utf8Chunks: number[] = [];
  let textParts = "";

  const flushUtf8 = () => {
    if (utf8Chunks.length > 0) {
      textParts += new TextDecoder("utf-8", { fatal: false }).decode(new Uint8Array(utf8Chunks));
      utf8Chunks.length = 0;
    }
  };

  while (totalBits - bitPos >= 4) {
    const mode = readBits(4);
    if (mode === 0b0000) break; // Terminator
    if (mode === 0b0111) {
      // ECI designator (skip assignment value)
      const b1 = readBits(8);
      if (b1 < 0) return null;
      if ((b1 & 0x80) !== 0) {
        if ((b1 & 0xc0) === 0x80) {
          if (readBits(8) < 0) return null;
        } else if ((b1 & 0xe0) === 0xc0) {
          if (readBits(16) < 0) return null;
        } else {
          return null;
        }
      }
      continue;
    }
    if (mode === 0b0100) {
      // Byte mode
      const count = readBits(version <= 9 ? 8 : 16);
      if (count < 0 || bitPos + count * 8 > totalBits) return null;
      for (let i = 0; i < count; i++) {
        utf8Chunks.push(readBits(8));
      }
    } else if (mode === 0b0010) {
      // Alphanumeric mode
      flushUtf8();
      const count = readBits(version <= 9 ? 9 : version <= 26 ? 11 : 13);
      if (count < 0) return null;
      let rem = count;
      while (rem >= 2) {
        const v = readBits(11);
        if (v < 0 || v >= 45 * 45) return null;
        textParts += ALPHANUM_CHARS[Math.floor(v / 45)] + ALPHANUM_CHARS[v % 45];
        rem -= 2;
      }
      if (rem === 1) {
        const v = readBits(6);
        if (v < 0 || v >= 45) return null;
        textParts += ALPHANUM_CHARS[v];
      }
    } else if (mode === 0b0001) {
      // Numeric mode
      flushUtf8();
      const count = readBits(version <= 9 ? 10 : version <= 26 ? 12 : 14);
      if (count < 0) return null;
      let rem = count;
      while (rem >= 3) {
        const v = readBits(10);
        if (v < 0 || v >= 1000) return null;
        textParts += String(v).padStart(3, "0");
        rem -= 3;
      }
      if (rem === 2) {
        const v = readBits(7);
        if (v < 0 || v >= 100) return null;
        textParts += String(v).padStart(2, "0");
      } else if (rem === 1) {
        const v = readBits(4);
        if (v < 0 || v >= 10) return null;
        textParts += String(v);
      }
    } else {
      return null;
    }
  }

  flushUtf8();
  return textParts.length > 0 ? textParts : null;
}

export function decodeQrMatrix(grid: Uint8Array[]): string | null {
  const dim = grid.length;
  if (dim < 21 || (dim - 17) % 4 !== 0) return null;
  const version = (dim - 17) / 4;
  if (version < 1 || version > 20) return null;

  const tryDecodeGrid = (srcGrid: Uint8Array[]): string | null => {
    let fmt1 = 0;
    for (let i = 0; i <= 5; i++) fmt1 = (fmt1 << 1) | (srcGrid[8][i] & 1);
    fmt1 = (fmt1 << 1) | (srcGrid[8][7] & 1);
    fmt1 = (fmt1 << 1) | (srcGrid[8][8] & 1);
    fmt1 = (fmt1 << 1) | (srcGrid[7][8] & 1);
    for (let i = 9; i < 15; i++) fmt1 = (fmt1 << 1) | (srcGrid[14 - i][8] & 1);

    let fmt2 = 0;
    for (let i = 14; i >= 8; i--) fmt2 = (fmt2 << 1) | (srcGrid[dim - 15 + i][8] & 1);
    for (let i = 7; i >= 0; i--) fmt2 = (fmt2 << 1) | (srcGrid[8][dim - 1 - i] & 1);

    const fmtInfo = decodeFormatBits(fmt1, fmt2);
    if (!fmtInfo) return null;

    const { isFunc, rawModuleBits } = buildFunctionTemplate(version);
    const totalCw = Math.floor(rawModuleBits / 8);
    const ecPerBlock = ECC_CODEWORDS_PER_BLOCK[fmtInfo.ecc][version];
    const numBlocks = NUM_ERROR_CORRECTION_BLOCKS[fmtInfo.ecc][version];
    const totalDataCw = totalCw - ecPerBlock * numBlocks;

    const rawCw = new Uint8Array(totalCw);
    let bitIdx = 0;
    const maxBits = totalCw * 8;
    let upward = true;
    for (let right = dim - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;
      for (let vert = 0; vert < dim; vert++) {
        const r = upward ? dim - 1 - vert : vert;
        for (let j = 0; j < 2; j++) {
          const c = right - j;
          if (!isFunc[r][c] && bitIdx < maxBits) {
            let bit = srcGrid[r][c] & 1;
            if (shouldInvertMask(fmtInfo.mask, r, c)) bit ^= 1;
            rawCw[bitIdx >> 3] = (rawCw[bitIdx >> 3] << 1) | bit;
            bitIdx++;
          }
        }
      }
      upward = !upward;
    }

    const numShortBlocks = numBlocks - (totalDataCw % numBlocks);
    const shortBlockLen = Math.floor(totalDataCw / numBlocks);
    const maxDataLen = shortBlockLen + (numShortBlocks < numBlocks ? 1 : 0);

    const blocks: Uint8Array[] = Array.from(
      { length: numBlocks },
      (_, b) => new Uint8Array(shortBlockLen + (b < numShortBlocks ? 0 : 1) + ecPerBlock)
    );

    let cwCursor = 0;
    for (let i = 0; i < maxDataLen; i++) {
      for (let b = 0; b < numBlocks; b++) {
        const datLen = shortBlockLen + (b < numShortBlocks ? 0 : 1);
        if (i < datLen) {
          blocks[b][i] = rawCw[cwCursor++];
        }
      }
    }
    for (let i = 0; i < ecPerBlock; i++) {
      for (let b = 0; b < numBlocks; b++) {
        const datLen = shortBlockLen + (b < numShortBlocks ? 0 : 1);
        blocks[b][datLen + i] = rawCw[cwCursor++];
      }
    }

    const allData = new Uint8Array(totalDataCw);
    let outPos = 0;
    for (let b = 0; b < numBlocks; b++) {
      if (!rsCorrectBlock(blocks[b], ecPerBlock)) return null;
      const datLen = shortBlockLen + (b < numShortBlocks ? 0 : 1);
      allData.set(blocks[b].subarray(0, datLen), outPos);
      outPos += datLen;
    }

    return decodeDataBitStream(allData, version);
  };

  const direct = tryDecodeGrid(grid);
  if (direct !== null) return direct;

  // Also try transposed (mirror) grid
  const mirrored = Array.from({ length: dim }, (_, r) => {
    const row = new Uint8Array(dim);
    for (let c = 0; c < dim; c++) row[c] = grid[c][r];
    return row;
  });
  return tryDecodeGrid(mirrored);
}

interface FinderCenter {
  x: number;
  y: number;
  moduleSize: number;
  count: number;
}

function matchesFinderRatio(counts: readonly number[]): boolean {
  let total = 0;
  for (let i = 0; i < 5; i++) {
    if (counts[i] === 0) return false;
    total += counts[i];
  }
  if (total < 7) return false;
  const mod = total / 7;
  const maxVar = mod * 0.58;
  return (
    Math.abs(mod - counts[0]) < maxVar &&
    Math.abs(mod - counts[1]) < maxVar &&
    Math.abs(3 * mod - counts[2]) < 3 * maxVar &&
    Math.abs(mod - counts[3]) < maxVar &&
    Math.abs(mod - counts[4]) < maxVar
  );
}

function crossCheckVertical(
  bin: Uint8Array,
  w: number,
  h: number,
  startY: number,
  centerX: number,
  maxCount: number,
  origTotal: number
): { y: number; modSize: number } | null {
  const counts = [0, 0, 0, 0, 0];
  let y = startY;
  while (y >= 0 && bin[y * w + centerX] === 1) {
    counts[2]++;
    y--;
  }
  if (y < 0) return null;
  while (y >= 0 && bin[y * w + centerX] === 0 && counts[1] <= maxCount) {
    counts[1]++;
    y--;
  }
  if (y < 0 || counts[1] > maxCount) return null;
  while (y >= 0 && bin[y * w + centerX] === 1 && counts[0] <= maxCount) {
    counts[0]++;
    y--;
  }
  if (counts[0] > maxCount) return null;

  y = startY + 1;
  while (y < h && bin[y * w + centerX] === 1) {
    counts[2]++;
    y++;
  }
  if (y >= h) return null;
  while (y < h && bin[y * w + centerX] === 0 && counts[3] <= maxCount) {
    counts[3]++;
    y++;
  }
  if (y >= h || counts[3] > maxCount) return null;
  while (y < h && bin[y * w + centerX] === 1 && counts[4] <= maxCount) {
    counts[4]++;
    y++;
  }
  if (counts[4] > maxCount) return null;

  const total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
  if (5 * Math.abs(total - origTotal) >= 2 * origTotal) return null;
  if (!matchesFinderRatio(counts)) return null;
  return {
    y: y - counts[4] - counts[3] - counts[2] / 2,
    modSize: total / 7,
  };
}

function crossCheckHorizontal(
  bin: Uint8Array,
  w: number,
  centerY: number,
  startX: number,
  maxCount: number,
  origTotal: number
): number | null {
  const counts = [0, 0, 0, 0, 0];
  const rowOff = centerY * w;
  let x = startX;
  while (x >= 0 && bin[rowOff + x] === 1) {
    counts[2]++;
    x--;
  }
  if (x < 0) return null;
  while (x >= 0 && bin[rowOff + x] === 0 && counts[1] <= maxCount) {
    counts[1]++;
    x--;
  }
  if (x < 0 || counts[1] > maxCount) return null;
  while (x >= 0 && bin[rowOff + x] === 1 && counts[0] <= maxCount) {
    counts[0]++;
    x--;
  }
  if (counts[0] > maxCount) return null;

  x = startX + 1;
  while (x < w && bin[rowOff + x] === 1) {
    counts[2]++;
    x++;
  }
  if (x >= w) return null;
  while (x < w && bin[rowOff + x] === 0 && counts[3] <= maxCount) {
    counts[3]++;
    x++;
  }
  if (x >= w || counts[3] > maxCount) return null;
  while (x < w && bin[rowOff + x] === 1 && counts[4] <= maxCount) {
    counts[4]++;
    x++;
  }
  if (counts[4] > maxCount) return null;

  const total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
  if (5 * Math.abs(total - origTotal) >= origTotal) return null;
  if (!matchesFinderRatio(counts)) return null;
  return x - counts[4] - counts[3] - counts[2] / 2;
}

function crossCheckDiagonal(
  bin: Uint8Array,
  w: number,
  h: number,
  centerY: number,
  centerX: number
): boolean {
  const counts = [0, 0, 0, 0, 0];
  let i = 0;
  while (centerY >= i && centerX >= i && bin[(centerY - i) * w + (centerX - i)] === 1) {
    counts[2]++;
    i++;
  }
  if (centerY < i || centerX < i) return false;
  while (centerY >= i && centerX >= i && bin[(centerY - i) * w + (centerX - i)] === 0) {
    counts[1]++;
    i++;
  }
  if (centerY < i || centerX < i || counts[1] === 0) return false;
  while (centerY >= i && centerX >= i && bin[(centerY - i) * w + (centerX - i)] === 1) {
    counts[0]++;
    i++;
  }

  i = 1;
  while (centerY + i < h && centerX + i < w && bin[(centerY + i) * w + (centerX + i)] === 1) {
    counts[2]++;
    i++;
  }
  if (centerY + i >= h || centerX + i >= w) return false;
  while (centerY + i < h && centerX + i < w && bin[(centerY + i) * w + (centerX + i)] === 0) {
    counts[3]++;
    i++;
  }
  if (centerY + i >= h || centerX + i >= w || counts[3] === 0) return false;
  while (centerY + i < h && centerX + i < w && bin[(centerY + i) * w + (centerX + i)] === 1) {
    counts[4]++;
    i++;
  }

  const total = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
  if (total < 7) return false;
  const mod = total / 7;
  const maxVar = mod * 0.78;
  return (
    Math.abs(mod - counts[0]) < maxVar &&
    Math.abs(mod - counts[1]) < maxVar &&
    Math.abs(3 * mod - counts[2]) < 3 * maxVar &&
    Math.abs(mod - counts[3]) < maxVar &&
    Math.abs(mod - counts[4]) < maxVar
  );
}

function findFinderCenters(bin: Uint8Array, w: number, h: number): FinderCenter[] {
  const centers: FinderCenter[] = [];
  const rowStep = Math.max(1, Math.floor(h / 420));

  const absorbCandidate = (counts: number[], row: number, endX: number) => {
    const origTotal = counts[0] + counts[1] + counts[2] + counts[3] + counts[4];
    const approxX = endX - counts[4] - counts[3] - counts[2] / 2;
    const vCheck = crossCheckVertical(
      bin,
      w,
      h,
      row,
      Math.floor(approxX),
      counts[2] * 2,
      origTotal
    );
    if (!vCheck) return;
    const hCheckX = crossCheckHorizontal(
      bin,
      w,
      Math.floor(vCheck.y),
      Math.floor(approxX),
      counts[2] * 2,
      origTotal
    );
    if (hCheckX === null) return;
    if (!crossCheckDiagonal(bin, w, h, Math.floor(vCheck.y), Math.floor(hCheckX))) return;

    const modSize = (origTotal / 7 + vCheck.modSize) / 2;
    for (const c of centers) {
      const dist = Math.hypot(c.x - hCheckX, c.y - vCheck.y);
      if (dist <= Math.max(modSize, c.moduleSize) * 1.6) {
        const nextCount = c.count + 1;
        c.x = (c.x * c.count + hCheckX) / nextCount;
        c.y = (c.y * c.count + vCheck.y) / nextCount;
        c.moduleSize = (c.moduleSize * c.count + modSize) / nextCount;
        c.count = nextCount;
        return;
      }
    }
    centers.push({ x: hCheckX, y: vCheck.y, moduleSize: modSize, count: 1 });
  };

  for (let r = rowStep - 1; r < h; r += rowStep) {
    const counts = [0, 0, 0, 0, 0];
    let state = 0;
    const rowOff = r * w;
    for (let x = 0; x < w; x++) {
      if (bin[rowOff + x] === 1) {
        if ((state & 1) === 1) state++;
        counts[state]++;
      } else {
        if ((state & 1) === 0) {
          if (state === 4) {
            if (matchesFinderRatio(counts)) {
              absorbCandidate(counts, r, x);
              counts[0] = counts[2];
              counts[1] = counts[3];
              counts[2] = counts[4];
              counts[3] = 1;
              counts[4] = 0;
              state = 3;
            } else {
              counts[0] = counts[2];
              counts[1] = counts[3];
              counts[2] = counts[4];
              counts[3] = 1;
              counts[4] = 0;
              state = 3;
            }
          } else {
            state++;
            counts[state]++;
          }
        } else {
          counts[state]++;
        }
      }
    }
    if (matchesFinderRatio(counts)) {
      absorbCandidate(counts, r, w);
    }
  }

  return centers
    .filter((c) => c.count >= 2 || centers.length <= 4)
    .sort((a, b) => b.count - a.count)
    .slice(0, 12);
}

function findBottomRightAlignment(
  bin: Uint8Array,
  w: number,
  h: number,
  estX: number,
  estY: number,
  modSize: number
): { x: number; y: number } | null {
  const radius = Math.max(6, Math.round(modSize * 4.5));
  const minX = Math.max(2, Math.floor(estX - radius));
  const maxX = Math.min(w - 3, Math.ceil(estX + radius));
  const minY = Math.max(2, Math.floor(estY - radius));
  const maxY = Math.min(h - 3, Math.ceil(estY + radius));

  const isAlignRatio = (c0: number, c1: number, c2: number): boolean => {
    if (c0 === 0 || c1 === 0 || c2 === 0) return false;
    const maxVar = modSize * 0.72;
    return (
      Math.abs(c0 - modSize) < maxVar &&
      Math.abs(c1 - modSize) < maxVar &&
      Math.abs(c2 - modSize) < maxVar
    );
  };

  let best: { x: number; y: number; dist: number } | null = null;
  for (let y = minY; y <= maxY; y++) {
    const counts = [0, 0, 0];
    let state = 0;
    const rowOff = y * w;
    for (let x = minX; x <= maxX; x++) {
      const dark = bin[rowOff + x] === 1;
      if (dark) {
        if (state === 1) {
          counts[1]++;
        } else {
          if (state === 2) {
            if (isAlignRatio(counts[0], counts[1], counts[2])) {
              const cx = x - counts[2] - counts[1] / 2;
              const ix = Math.floor(cx);
              // Vertical 1:1:1 check (white - black - white)
              let v1 = 0;
              let yy = y;
              while (yy >= minY - 4 && yy >= 0 && bin[yy * w + ix] === 1) {
                v1++;
                yy--;
              }
              let v0 = 0;
              while (yy >= minY - 4 && yy >= 0 && bin[yy * w + ix] === 0 && v0 <= modSize * 2.2) {
                v0++;
                yy--;
              }
              yy = y + 1;
              while (yy <= maxY + 4 && yy < h && bin[yy * w + ix] === 1) {
                v1++;
                yy++;
              }
              let v2 = 0;
              while (yy <= maxY + 4 && yy < h && bin[yy * w + ix] === 0 && v2 <= modSize * 2.2) {
                v2++;
                yy++;
              }
              if (isAlignRatio(v0, v1, v2)) {
                const cy = yy - v2 - v1 / 2;
                const d = Math.hypot(cx - estX, cy - estY);
                if (!best || d < best.dist) {
                  best = { x: cx, y: cy, dist: d };
                }
              }
            }
            counts[0] = counts[2];
            counts[1] = 1;
            counts[2] = 0;
            state = 1;
          } else {
            counts[0] = 0;
            counts[1] = 1;
            counts[2] = 0;
            state = 1;
          }
        }
      } else {
        if (state === 1) state = 2;
        counts[state]++;
      }
    }
  }
  return best ? { x: best.x, y: best.y } : null;
}

/**
 * Computes 3x3 homography mapping (u, v) in QR module space -> (x, y) in image pixel space
 * from 4 point correspondences.
 */
function computeHomography(
  src: readonly [number, number][],
  dst: readonly [number, number][]
): Float64Array | null {
  const A = Array.from({ length: 8 }, () => new Float64Array(9));
  for (let i = 0; i < 4; i++) {
    const [u, v] = src[i];
    const [x, y] = dst[i];
    const r0 = i * 2;
    const r1 = r0 + 1;
    A[r0][0] = u;
    A[r0][1] = v;
    A[r0][2] = 1;
    A[r0][6] = -u * x;
    A[r0][7] = -v * x;
    A[r0][8] = x;

    A[r1][3] = u;
    A[r1][4] = v;
    A[r1][5] = 1;
    A[r1][6] = -u * y;
    A[r1][7] = -v * y;
    A[r1][8] = y;
  }

  for (let col = 0; col < 8; col++) {
    let pivot = col;
    for (let r = col + 1; r < 8; r++) {
      if (Math.abs(A[r][col]) > Math.abs(A[pivot][col])) pivot = r;
    }
    if (Math.abs(A[pivot][col]) < 1e-11) return null;
    if (pivot !== col) {
      const tmp = A[col];
      A[col] = A[pivot];
      A[pivot] = tmp;
    }
    const div = A[col][col];
    for (let j = col; j <= 8; j++) A[col][j] /= div;
    for (let r = 0; r < 8; r++) {
      if (r === col) continue;
      const factor = A[r][col];
      if (factor === 0) continue;
      for (let j = col; j <= 8; j++) A[r][j] -= factor * A[col][j];
    }
  }

  const H = new Float64Array(9);
  for (let i = 0; i < 8; i++) H[i] = A[i][8];
  H[8] = 1;
  return H;
}

function sampleGridWithHomography(
  bin: Uint8Array,
  w: number,
  h: number,
  dim: number,
  H: Float64Array
): Uint8Array[] {
  const grid = Array.from({ length: dim }, () => new Uint8Array(dim));
  const offsets: readonly [number, number][] = [
    [0, 0],
    [-0.18, 0],
    [0.18, 0],
    [0, -0.18],
    [0, 0.18],
  ];
  for (let r = 0; r < dim; r++) {
    for (let c = 0; c < dim; c++) {
      let votes = 0;
      for (const [du, dv] of offsets) {
        const u = c + 0.5 + du;
        const v = r + 0.5 + dv;
        const denom = H[6] * u + H[7] * v + H[8];
        if (denom === 0) continue;
        const px = Math.round((H[0] * u + H[1] * v + H[2]) / denom);
        const py = Math.round((H[3] * u + H[4] * v + H[5]) / denom);
        if (px >= 0 && px < w && py >= 0 && py < h) {
          votes += bin[py * w + px];
        }
      }
      grid[r][c] = votes >= 3 ? 1 : 0;
    }
  }
  return grid;
}

function tryDecodeBinarized(bin: Uint8Array, w: number, h: number): string | null {
  const centers = findFinderCenters(bin, w, h);
  if (centers.length < 3) return null;

  for (let i = 0; i < centers.length - 2; i++) {
    for (let j = i + 1; j < centers.length - 1; j++) {
      for (let k = j + 1; k < centers.length; k++) {
        const tri = [centers[i], centers[j], centers[k]];
        const minMod = Math.min(tri[0].moduleSize, tri[1].moduleSize, tri[2].moduleSize);
        const maxMod = Math.max(tri[0].moduleSize, tri[1].moduleSize, tri[2].moduleSize);
        if (maxMod > minMod * 1.85) continue;

        const d01 = Math.hypot(tri[0].x - tri[1].x, tri[0].y - tri[1].y);
        const d12 = Math.hypot(tri[1].x - tri[2].x, tri[1].y - tri[2].y);
        const d02 = Math.hypot(tri[0].x - tri[2].x, tri[0].y - tri[2].y);

        let tl: FinderCenter;
        let pA: FinderCenter;
        let pB: FinderCenter;
        if (d12 >= d01 && d12 >= d02) {
          tl = tri[0];
          pA = tri[1];
          pB = tri[2];
        } else if (d02 >= d01 && d02 >= d12) {
          tl = tri[1];
          pA = tri[0];
          pB = tri[2];
        } else {
          tl = tri[2];
          pA = tri[0];
          pB = tri[1];
        }

        const sideA = Math.hypot(pA.x - tl.x, pA.y - tl.y);
        const sideB = Math.hypot(pB.x - tl.x, pB.y - tl.y);
        if (sideA < 10 || sideB < 10) continue;
        if (Math.abs(sideA - sideB) / Math.max(sideA, sideB) > 0.34) continue;

        const cosAngle =
          ((pA.x - tl.x) * (pB.x - tl.x) + (pA.y - tl.y) * (pB.y - tl.y)) / (sideA * sideB);
        if (Math.abs(cosAngle) > 0.35) continue;

        const cross = (pA.x - tl.x) * (pB.y - tl.y) - (pA.y - tl.y) * (pB.x - tl.x);
        const tr = cross > 0 ? pA : pB;
        const bl = cross > 0 ? pB : pA;

        const avgMod = (tl.moduleSize + tr.moduleSize + bl.moduleSize) / 3;
        const rawDim = Math.round((sideA + sideB) / (2 * avgMod)) + 7;
        const approxVer = Math.max(1, Math.min(20, Math.round((rawDim - 17) / 4)));

        for (const dv of [0, -1, 1, -2, 2]) {
          const ver = approxVer + dv;
          if (ver < 1 || ver > 20) continue;
          const dim = ver * 4 + 17;
          const span = dim - 7;

          // 1. Perspective homography refined by bottom-right Alignment Pattern (v2+)
          if (ver >= 2) {
            const scale = (span - 3) / span;
            const estAx = tl.x + (tr.x + bl.x - 2 * tl.x) * scale;
            const estAy = tl.y + (tr.y + bl.y - 2 * tl.y) * scale;
            const alignPt = findBottomRightAlignment(bin, w, h, estAx, estAy, avgMod);
            if (alignPt) {
              const H = computeHomography(
                [
                  [3.5, 3.5],
                  [dim - 3.5, 3.5],
                  [3.5, dim - 3.5],
                  [dim - 6.5, dim - 6.5],
                ],
                [
                  [tl.x, tl.y],
                  [tr.x, tr.y],
                  [bl.x, bl.y],
                  [alignPt.x, alignPt.y],
                ]
              );
              if (H) {
                const text = decodeQrMatrix(sampleGridWithHomography(bin, w, h, dim, H));
                if (text !== null) return text;
              }
            }
          }

          // 2. Pure affine mapping from the 3 Finder Centers (ideal for flat screenshots)
          const brX = tr.x + bl.x - tl.x;
          const brY = tr.y + bl.y - tl.y;
          const HAffine = computeHomography(
            [
              [3.5, 3.5],
              [dim - 3.5, 3.5],
              [3.5, dim - 3.5],
              [dim - 3.5, dim - 3.5],
            ],
            [
              [tl.x, tl.y],
              [tr.x, tr.y],
              [bl.x, bl.y],
              [brX, brY],
            ]
          );
          if (HAffine) {
            const text = decodeQrMatrix(sampleGridWithHomography(bin, w, h, dim, HAffine));
            if (text !== null) return text;
          }
        }
      }
    }
  }
  return null;
}

function buildHybridBinary(luma: Uint8Array, w: number, h: number, invert: boolean): Uint8Array {
  const bw = Math.max(1, Math.ceil(w / 8));
  const bh = Math.max(1, Math.ceil(h / 8));
  const means = new Uint8Array(bw * bh);

  for (let by = 0; by < bh; by++) {
    const y0 = Math.min(by * 8, Math.max(0, h - 8));
    for (let bx = 0; bx < bw; bx++) {
      const x0 = Math.min(bx * 8, Math.max(0, w - 8));
      let sum = 0;
      let min = 255;
      let max = 0;
      const yLim = Math.min(h, y0 + 8);
      const xLim = Math.min(w, x0 + 8);
      let cnt = 0;
      for (let yy = y0; yy < yLim; yy++) {
        const off = yy * w;
        for (let xx = x0; xx < xLim; xx++) {
          const v = luma[off + xx];
          sum += v;
          if (v < min) min = v;
          if (v > max) max = v;
          cnt++;
        }
      }
      let avg = cnt > 0 ? Math.floor(sum / cnt) : 127;
      if (max - min <= 24) {
        avg = Math.floor(min / 2);
        if (by > 0 && bx > 0) {
          const neighbor = Math.floor(
            (means[(by - 1) * bw + bx] + 2 * means[by * bw + (bx - 1)] + means[(by - 1) * bw + (bx - 1)]) / 4
          );
          if (min < neighbor) avg = neighbor;
        }
      }
      means[by * bw + bx] = avg;
    }
  }

  const out = new Uint8Array(w * h);
  for (let by = 0; by < bh; by++) {
    const top = Math.max(2, Math.min(bh - 3, by));
    for (let bx = 0; bx < bw; bx++) {
      const left = Math.max(2, Math.min(bw - 3, bx));
      let sum = 0;
      let used = 0;
      for (let dy = -2; dy <= 2; dy++) {
        const ry = top + dy;
        if (ry < 0 || ry >= bh) continue;
        for (let dx = -2; dx <= 2; dx++) {
          const rx = left + dx;
          if (rx < 0 || rx >= bw) continue;
          sum += means[ry * bw + rx];
          used++;
        }
      }
      const thresh = used > 0 ? Math.floor(sum / used) : 127;
      const y0 = by * 8;
      const x0 = bx * 8;
      const yLim = Math.min(h, y0 + 8);
      const xLim = Math.min(w, x0 + 8);
      for (let yy = y0; yy < yLim; yy++) {
        const off = yy * w;
        for (let xx = x0; xx < xLim; xx++) {
          const isDark = luma[off + xx] <= thresh;
          out[off + xx] = (invert ? !isDark : isDark) ? 1 : 0;
        }
      }
    }
  }
  return out;
}

function buildOtsuBinary(luma: Uint8Array, w: number, h: number, invert: boolean): Uint8Array {
  const hist = new Uint32Array(256);
  const total = w * h;
  for (let i = 0; i < total; i++) hist[luma[i]]++;

  let sum = 0;
  for (let i = 0; i < 256; i++) sum += i * hist[i];

  let sumB = 0;
  let wB = 0;
  let maxVar = -1;
  let threshold = 127;
  for (let t = 0; t < 256; t++) {
    wB += hist[t];
    if (wB === 0) continue;
    const wF = total - wB;
    if (wF === 0) break;
    sumB += t * hist[t];
    const mB = sumB / wB;
    const mF = (sum - sumB) / wF;
    const between = wB * wF * (mB - mF) * (mB - mF);
    if (between > maxVar) {
      maxVar = between;
      threshold = t;
    }
  }

  const out = new Uint8Array(total);
  for (let i = 0; i < total; i++) {
    const isDark = luma[i] <= threshold;
    out[i] = (invert ? !isDark : isDark) ? 1 : 0;
  }
  return out;
}

function downscaleLuma(luma: Uint8Array, w: number, h: number, maxDim: number): {
  luma: Uint8Array;
  w: number;
  h: number;
} {
  const scale = Math.max(w, h) / maxDim;
  if (scale <= 1.05) return { luma, w, h };
  const nw = Math.max(32, Math.round(w / scale));
  const nh = Math.max(32, Math.round(h / scale));
  const out = new Uint8Array(nw * nh);
  for (let y = 0; y < nh; y++) {
    const sy0 = Math.floor(y * scale);
    const sy1 = Math.min(h, Math.max(sy0 + 1, Math.floor((y + 1) * scale)));
    for (let x = 0; x < nw; x++) {
      const sx0 = Math.floor(x * scale);
      const sx1 = Math.min(w, Math.max(sx0 + 1, Math.floor((x + 1) * scale)));
      let sum = 0;
      let cnt = 0;
      for (let sy = sy0; sy < sy1; sy++) {
        const off = sy * w;
        for (let sx = sx0; sx < sx1; sx++) {
          sum += luma[off + sx];
          cnt++;
        }
      }
      out[y * nw + x] = cnt > 0 ? Math.round(sum / cnt) : 255;
    }
  }
  return { luma: out, w: nw, h: nh };
}

export function decodeQrFromRgba(rgba: Uint8ClampedArray | Uint8Array, width: number, height: number): string | null {
  if (width < 21 || height < 21 || rgba.length < width * height * 4) return null;
  const total = width * height;
  const fullLuma = new Uint8Array(total);
  for (let i = 0; i < total; i++) {
    const off = i * 4;
    const a = rgba[off + 3];
    if (a < 32) {
      fullLuma[i] = 255;
    } else {
      fullLuma[i] = (rgba[off] * 77 + rgba[off + 1] * 150 + rgba[off + 2] * 29) >> 8;
    }
  }

  const scales: Array<{ luma: Uint8Array; w: number; h: number }> = [];
  if (Math.max(width, height) > 1080) {
    scales.push(downscaleLuma(fullLuma, width, height, 1024));
  }
  scales.push({ luma: fullLuma, w: width, h: height });

  for (const s of scales) {
    const hybrid = tryDecodeBinarized(buildHybridBinary(s.luma, s.w, s.h, false), s.w, s.h);
    if (hybrid !== null) return hybrid;

    const otsu = tryDecodeBinarized(buildOtsuBinary(s.luma, s.w, s.h, false), s.w, s.h);
    if (otsu !== null) return otsu;

    const inverted = tryDecodeBinarized(buildHybridBinary(s.luma, s.w, s.h, true), s.w, s.h);
    if (inverted !== null) return inverted;
  }
  return null;
}

export async function decodeQrFromBlob(blob: Blob): Promise<string | null> {
  if (typeof window === "undefined") return null;

  // 1. Hardware Vision / Chromium BarcodeDetector fast-path when supported by host OS WebView
  const DetectorCtor = (window as any).BarcodeDetector;
  if (typeof DetectorCtor === "function" && typeof createImageBitmap === "function") {
    try {
      const detector = new DetectorCtor({ formats: ["qr_code"] });
      const bmp = await createImageBitmap(blob);
      try {
        const codes = await detector.detect(bmp);
        if (Array.isArray(codes)) {
          for (const item of codes) {
            const val = typeof item?.rawValue === "string" ? item.rawValue.trim() : "";
            if (val) return val;
          }
        }
      } finally {
        bmp.close();
      }
    } catch {
      // Fall through to pure-TS ISO/IEC 18004 decoder
    }
  }

  // 2. Universal Canvas + ISO/IEC 18004 QR decoder (works across Windows WebView2, macOS 11+, Linux WebKitGTK)
  if (typeof document === "undefined") return null;
  if (typeof createImageBitmap === "function") {
    try {
      const bmp = await createImageBitmap(blob);
      try {
        const maxSide = 1600;
        const scale = Math.min(1, maxSide / Math.max(bmp.width, bmp.height, 1));
        const w = Math.max(21, Math.round(bmp.width * scale));
        const h = Math.max(21, Math.round(bmp.height * scale));
        const canvas = document.createElement("canvas");
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext("2d", { willReadFrequently: true });
        if (ctx) {
          ctx.fillStyle = "#FFFFFF";
          ctx.fillRect(0, 0, w, h);
          ctx.drawImage(bmp, 0, 0, w, h);
          const imgData = ctx.getImageData(0, 0, w, h);
          return decodeQrFromRgba(imgData.data, w, h);
        }
      } finally {
        bmp.close();
      }
    } catch {
      // Fallback to HTMLImageElement below
    }
  }

  return new Promise<string | null>((resolve) => {
    const objUrl = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      try {
        const maxSide = 1600;
        const scale = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight, 1));
        const w = Math.max(21, Math.round(img.naturalWidth * scale));
        const h = Math.max(21, Math.round(img.naturalHeight * scale));
        const canvas = document.createElement("canvas");
        canvas.width = w;
        canvas.height = h;
        const ctx = canvas.getContext("2d", { willReadFrequently: true });
        if (!ctx) {
          resolve(null);
          return;
        }
        ctx.fillStyle = "#FFFFFF";
        ctx.fillRect(0, 0, w, h);
        ctx.drawImage(img, 0, 0, w, h);
        const imgData = ctx.getImageData(0, 0, w, h);
        resolve(decodeQrFromRgba(imgData.data, w, h));
      } catch {
        resolve(null);
      } finally {
        URL.revokeObjectURL(objUrl);
      }
    };
    img.onerror = () => {
      URL.revokeObjectURL(objUrl);
      resolve(null);
    };
    img.src = objUrl;
  });
}

export async function decodeQrFromBase64Image(
  dataBase64: string,
  mime = "image/png"
): Promise<string | null> {
  if (!dataBase64) return null;
  try {
    const clean = dataBase64.replace(/\s+/g, "");
    const bin = atob(clean);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return decodeQrFromBlob(new Blob([bytes], { type: mime || "image/png" }));
  } catch {
    return null;
  }
}
