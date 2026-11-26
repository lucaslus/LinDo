export type AppScreenshot = {
  light: string;
  dark: string;
  width: number;
  height: number;
  alt: string;
};

export const APP_SCREENSHOTS = {
  desktop: {
    light: "/shots/macos-v115.webp", dark: "/shots/macos-v115-dark.webp",
    width: 1800, height: 1192, alt: "macOS 版 LinDo，左侧是话题列表，右侧是帖子正文",
  },
  phone: {
    light: "/shots/pixel9-v115-light.webp", dark: "/shots/pixel9-v115.webp",
    width: 728, height: 1634, alt: "Android 版 LinDo，单栏阅读社区置顶帖",
  },
  foldCover: {
    light: "/shots/pixel9-fold-cover-v115-light.webp", dark: "/shots/pixel9-fold-cover-v115.webp",
    width: 728, height: 1634, alt: "折叠屏外屏，单栏显示帖子",
  },
  foldOpen: {
    light: "/shots/pixel9-fold-open-v115-light.webp", dark: "/shots/pixel9-fold-open-v115.webp",
    width: 1440, height: 1493, alt: "折叠屏内屏，帖子列表与正文并排",
  },
  icon: {
    light: "/shots/pixel9-icon-v115-light.webp", dark: "/shots/pixel9-icon-v115-dark.webp",
    width: 728, height: 1634, alt: "Android 应用图标设置，可选样式和背景",
  },
} as const satisfies Record<string, AppScreenshot>;

export type ScreenshotKey = keyof typeof APP_SCREENSHOTS;

export function ThemeScreenshot({
  shot, className, link = false, priority = false,
}: { shot: AppScreenshot; className?: string; link?: boolean; priority?: boolean }) {
  return (
    <span className={className ? `theme-shot ${className}` : "theme-shot"}>
      {(["light", "dark"] as const).map((mode) => {
        const img = (
          <img
            src={shot[mode]}
            alt={shot.alt}
            width={shot.width}
            height={shot.height}
            fetchPriority={priority ? "high" : undefined}
            decoding="async"
          />
        );
        const variant = `theme-shot-${mode}`;
        return link ? (
          <a key={mode} className={variant} href={shot[mode]} target="_blank" rel="noreferrer">{img}</a>
        ) : (
          <span key={mode} className={variant}>{img}</span>
        );
      })}
    </span>
  );
}
