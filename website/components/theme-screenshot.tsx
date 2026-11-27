type AppScreenshot = {
  light: string;
  dark: string;
  width: number;
  height: number;
  alt: string;
};

export const APP_SCREENSHOTS = {
  desktop: {
    light: "/shots/macos-v115.webp",
    dark: "/shots/macos-v115-dark.webp",
    width: 1800,
    height: 1192,
    alt: "macOS 上并排显示的运营反馈列表和置顶帖《请不要把互联网上的戾气带来这里！》",
  },
  phone: {
    light: "/shots/phone-light-neo.webp",
    dark: "/shots/phone-dark-neo.webp",
    width: 1080,
    height: 2424,
    alt: "Pixel 9 单栏阅读 Neo 的置顶帖《请不要把互联网上的戾气带来这里！》",
  },
  fold: {
    light: "/shots/fold-light-neo.webp",
    dark: "/shots/fold-dark-neo.webp",
    width: 2076,
    height: 2152,
    alt: "展开的折叠屏上并排显示话题列表和置顶帖《请不要把互联网上的戾气带来这里！》",
  },
  tablet: {
    light: "/shots/tablet-light-neo.webp",
    dark: "/shots/tablet-dark-neo.webp",
    width: 2560,
    height: 1600,
    alt: "Pixel Tablet 横屏并排显示运营反馈列表和 Neo 的置顶帖《请不要把互联网上的戾气带来这里！》",
  },
} as const satisfies Record<string, AppScreenshot>;

export type ScreenshotKey = keyof typeof APP_SCREENSHOTS;

export function ThemeScreenshot({ shot }: { shot: AppScreenshot }) {
  return (
    <span className="theme-shot">
      {(["light", "dark"] as const).map(theme => (
        <img key={theme} className={`theme-shot-${theme}`} src={shot[theme]} alt={shot.alt}
          width={shot.width} height={shot.height} decoding="async" loading="lazy" />
      ))}
    </span>
  );
}
