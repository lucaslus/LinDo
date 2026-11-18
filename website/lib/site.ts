export const SITE = {
  name: "LinDo",
  description: "LINUX DO 第三方开源客户端，提供 Android 原生版与 macOS、Windows 桌面版。",
  repo: "https://github.com/lucaslus/LinDo",
  releases: "https://github.com/lucaslus/LinDo/releases/latest",
  forum: "https://linux.do",
  versions: {
    android: "0.2.34",
    desktop: "1.0.45",
  },
};

/** 与 App 的 BrandThemeOption / desktop-tauri palette.ts 一一对应 */
export const ACCENTS = [
  { key: "gold", label: "金", swatch: "#F59E0B" },
  { key: "blue", label: "蓝", swatch: "#2563EB" },
  { key: "green", label: "绿", swatch: "#059669" },
  { key: "red", label: "红", swatch: "#E03E3E" },
  { key: "purple", label: "紫", swatch: "#8B5CF6" },
  { key: "teal", label: "青", swatch: "#0891B2" },
  { key: "orange", label: "橙", swatch: "#F97316" },
] as const;

export type AccentKey = (typeof ACCENTS)[number]["key"];
