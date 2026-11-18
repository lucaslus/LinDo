/* 与 composeApp LinDoIcons.kt 同源的 24×24 线性图标体系（统一 1.75 描边、圆角端点与光学留白） */
import type { ReactNode, SVGProps } from "react";

type P = { size?: number; strokeWidth?: number } & SVGProps<SVGSVGElement>;

function Base({ size = 20, strokeWidth, children, ...rest }: P & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
      {...rest}
      style={{ strokeWidth: strokeWidth ?? 1.75, ...(rest.style || {}) }}
    >
      {children}
    </svg>
  );
}

export function IconSun(props: P) {
  return (
    <Base {...props}>
      <circle cx="12" cy="12" r="3.8" />
      <path d="M12 2.5v2.2M12 19.3v2.2M5.28 5.28l1.56 1.56M17.16 17.16l1.56 1.56M2.5 12h2.2M19.3 12h2.2M5.28 18.72l1.56-1.56M17.16 6.84l1.56-1.56" />
    </Base>
  );
}

export function IconMoon(props: P) {
  return (
    <Base {...props}>
      <path d="M20.2 13.4A8.2 8.2 0 0 1 10.6 3.8a8.2 8.2 0 1 0 9.6 9.6Z" />
    </Base>
  );
}

export function IconDownload(props: P) {
  return (
    <Base {...props}>
      <path d="M12 3.8v11.2M7.8 10.8 12 15l4.2-4.2M4.5 17.2v1.6c0 .95.75 1.7 1.7 1.7h11.6c.95 0 1.7-.75 1.7-1.7v-1.6" />
    </Base>
  );
}

export function IconArrowRight(props: P) {
  return (
    <Base {...props}>
      <path d="M5 12h14M13.2 6.2 19 12l-5.8 5.8" />
    </Base>
  );
}

/** 高刷新率与顺滑滚动（仪表指针） */
export function IconGauge(props: P) {
  return (
    <Base {...props}>
      <path d="M4.2 16.8a8.8 8.8 0 1 1 15.6 0" />
      <path d="M12 13.5 15.8 8.8" />
      <circle cx="12" cy="13.8" r="1.4" fill="currentColor" stroke="none" />
      <path d="M6.8 18.8h10.4" />
    </Base>
  );
}

export function IconBookmark(props: P) {
  return (
    <Base {...props}>
      <path d="M18.2 20.2 12 15.7l-6.2 4.5V5.6c0-1 .8-1.8 1.8-1.8h8.8c1 0 1.8.8 1.8 1.8v14.6Z" />
    </Base>
  );
}

export function IconShieldCheck(props: P) {
  return (
    <Base {...props}>
      <path d="M12 3 4.8 6.1v5.3c0 4.8 3.1 8.6 7.2 9.9 4.1-1.3 7.2-5.1 7.2-9.9V6.1L12 3Z" />
      <path d="m9.2 12.2 2 2 3.8-3.8" />
    </Base>
  );
}

export function IconPalette(props: P) {
  return (
    <Base {...props}>
      <path d="M12 2.8a9.2 9.2 0 0 0 0 18.4c1.3 0 2.3-1 2.3-2.3 0-.58-.22-1.1-.58-1.5a2.1 2.1 0 0 1 1.58-3.5h1.6a4.3 4.3 0 0 0 4.3-4.3c0-3.8-4.1-6.8-9.2-6.8Z" />
      <circle cx="7.6" cy="11" r="1.05" fill="currentColor" stroke="none" />
      <circle cx="10.2" cy="7.6" r="1.05" fill="currentColor" stroke="none" />
      <circle cx="14.2" cy="7.6" r="1.05" fill="currentColor" stroke="none" />
      <circle cx="16.8" cy="10.4" r="1.05" fill="currentColor" stroke="none" />
    </Base>
  );
}

/** 宽屏双栏（居中 50:50 分栏 + 左栏底部悬浮导航示意） */
export function IconSplitColumns(props: P) {
  return (
    <Base {...props}>
      <rect x="3" y="4.5" width="18" height="15" rx="2.6" />
      <path d="M12 4.5v15" />
      <path d="M6 16.7h3" />
    </Base>
  );
}

export function IconUsers(props: P) {
  return (
    <Base {...props}>
      <circle cx="9.2" cy="8" r="3.2" />
      <path d="M3.4 19.2c0-2.6 2.5-4.6 5.8-4.6s5.8 2 5.8 4.6" />
      <path d="M15.4 5.2a3 3 0 0 1 0 5.6M17.2 14.9c2 .5 3.4 2.1 3.4 4.3" />
    </Base>
  );
}

export function IconRefresh(props: P) {
  return (
    <Base {...props}>
      <path d="M4.9 8.2A7.8 7.8 0 0 1 19.2 9M19.6 5.2v4h-4" />
      <path d="M19.1 15.8A7.8 7.8 0 0 1 4.8 15M4.4 18.8v-4h4" />
    </Base>
  );
}

export function IconCheck(props: P) {
  return (
    <Base {...props}>
      <path d="M19.5 6.5 9.4 16.6 4.5 11.7" />
    </Base>
  );
}

export function IconExternalLink(props: P) {
  return (
    <Base {...props}>
      <path d="M18 13.5v4.3c0 1-.8 1.7-1.7 1.7H6.2c-1 0-1.7-.8-1.7-1.7V7.7c0-1 .8-1.7 1.7-1.7h4.3M14.8 4.5h4.7v4.7M10.5 13.5l9-9" />
    </Base>
  );
}

/* ───────── 四类终端设备切换图标（统一线条与圆角） ───────── */

export function IconDevicePhone(props: P) {
  return (
    <Base {...props}>
      <rect x="6.5" y="2.8" width="11" height="18.4" rx="2.6" />
      <path d="M10.4 18.3h3.2" />
    </Base>
  );
}

export function IconDeviceFold(props: P) {
  return (
    <Base {...props}>
      <rect x="3.2" y="4.2" width="17.6" height="15.6" rx="2.6" />
      <path d="M12 4.2v15.6" strokeDasharray="1.8 2.1" />
    </Base>
  );
}

export function IconDeviceTablet(props: P) {
  return (
    <Base {...props}>
      <rect x="2.6" y="5" width="18.8" height="14" rx="2.5" />
      <circle cx="12" cy="7.2" r="0.65" fill="currentColor" stroke="none" />
    </Base>
  );
}

export function IconDeviceDesktop(props: P) {
  return (
    <Base {...props}>
      <rect x="3.4" y="4.2" width="17.2" height="11.8" rx="2" />
      <path d="M2 19h20" />
    </Base>
  );
}
