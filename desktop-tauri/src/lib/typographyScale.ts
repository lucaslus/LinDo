/**
 * 大厂最佳实践字号体系（PC / Android 双端同口径）：
 * - 统一 5 档：最小(0.88) / 小(0.94) / 标准(1.00) / 大(1.12) / 最大(1.28)
 * - 基准层级严禁倒挂：详情主标题(21px) > 宽松 Feed 标题(16px) > 正文(15px) = 紧凑 Feed 标题(15px SemiBold) >= 回复正文(14.5px) > 元信息(>=12px)
 * - 三层非线性弹性缩放：核心阅读层全额响应，辅助元信息半幅温和缩放防撑爆胶囊，固定导航骨架极小幅受限防裁切。
 */

export interface FontScalePreset {
  readonly label: string;
  readonly scale: number;
}

export const FONT_SCALE_PRESETS: readonly FontScalePreset[] = [
  { label: "最小", scale: 0.88 },
  { label: "小", scale: 0.94 },
  { label: "标准", scale: 1.0 },
  { label: "大", scale: 1.12 },
  { label: "最大", scale: 1.28 },
];

export const PC_TYPOGRAPHY_BASELINES = {
  detailTitlePx: 21,
  feedLooseTitlePx: 16,
  feedCompactTitlePx: 15,
  postBodyPx: 15,
  replyBodyPx: 14.5,
  compactMetaPx: 12,
  looseMetaPx: 12.2,
} as const;

export interface TieredFontScales {
  /** 核心阅读层（Feed 标题、详情标题、正文、回复）：100% 跟随用户档位 */
  reading: number;
  /** 辅助元信息层（标签、版块、时间、计数）：50% 温和缩放并锁安全上下限 */
  meta: number;
  /** 固定导航骨架层（顶栏、侧栏导航、角标）：25% 极小幅微调防爆版 */
  chrome: number;
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

function round3(v: number): number {
  return Math.round(v * 1000) / 1000;
}

export function nearestFontPreset(scale: number): FontScalePreset {
  const safe = Number.isFinite(scale) && scale > 0 ? scale : 1.0;
  return FONT_SCALE_PRESETS.reduce((best, cur) =>
    Math.abs(cur.scale - safe) < Math.abs(best.scale - safe) ? cur : best
  );
}

export function computeTieredFontScales(rawScale: number): TieredFontScales {
  const reading = clamp(Number.isFinite(rawScale) && rawScale > 0 ? rawScale : 1.0, 0.85, 1.35);
  const delta = reading - 1.0;
  return {
    reading: round3(reading),
    meta: round3(clamp(1.0 + delta * 0.5, 0.94, 1.14)),
    chrome: round3(clamp(1.0 + delta * 0.25, 0.96, 1.08)),
  };
}
