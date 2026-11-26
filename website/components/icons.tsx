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

export function IconMenu(props: P) {
  return (
    <Base {...props}>
      <path d="M4 7.5h16M4 12h16M4 16.5h16" />
    </Base>
  );
}

export function IconClose(props: P) {
  return (
    <Base {...props}>
      <path d="M6.5 6.5l11 11M17.5 6.5l-11 11" />
    </Base>
  );
}

export function IconQrCode(props: P) {
  return (
    <Base {...props}>
      <rect x="3.5" y="3.5" width="6.5" height="6.5" rx="1.2" />
      <rect x="14" y="3.5" width="6.5" height="6.5" rx="1.2" />
      <rect x="3.5" y="14" width="6.5" height="6.5" rx="1.2" />
      <path d="M14 14h2.5v2.5H14zM18 14h2.5v2.5H18zM16.2 16.5h2v2h-2zM14 18.5h2.5V20.5H14zM18.2 18.5H20.5V20.5h-2.3z" />
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

/** 零隐私本机锁存 */
export function IconLock(props: P) {
  return (
    <Base {...props}>
      <rect x="5" y="10.5" width="14" height="10" rx="2.4" />
      <path d="M8.2 10.5V7.6a3.8 3.8 0 0 1 7.6 0v2.9" />
      <circle cx="12" cy="15.5" r="1.2" fill="currentColor" stroke="none" />
    </Base>
  );
}

/** 开源代码安全审计 */
export function IconCodeAudit(props: P) {
  return (
    <Base {...props}>
      <path d="m8.2 8.5-3.7 3.5 3.7 3.5M15.8 8.5l3.7 3.5-3.7 3.5M13.4 6.5l-2.8 11" />
    </Base>
  );
}

/** 构建产物与哈希核验 */
export function IconFileCheck(props: P) {
  return (
    <Base {...props}>
      <path d="M14 3.5H7.2c-1 0-1.7.8-1.7 1.8v13.4c0 1 .7 1.8 1.7 1.8h9.6c1 0 1.7-.8 1.7-1.8V8L14 3.5Z" />
      <path d="M13.8 3.6V8h4.5M9.2 14.2l2 2 3.8-4" />
    </Base>
  );
}

/** 长期路线图与本地特色生态 */
export function IconCompass(props: P) {
  return (
    <Base {...props}>
      <circle cx="12" cy="12" r="8.8" />
      <path d="m15.4 8.6-2.1 5.3-5.3 2.1 2.1-5.3 5.3-2.1Z" />
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

/** GitHub 官方实心 Octocat 图标 */
export function IconGitHub({ size = 19, ...rest }: { size?: number } & SVGProps<SVGSVGElement>) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden {...rest}>
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M12 2C6.477 2 2 6.484 2 12.017c0 4.425 2.865 8.18 6.839 9.504.5.092.682-.217.682-.483 0-.237-.008-.868-.013-1.703-2.782.605-3.369-1.343-3.369-1.343-.454-1.158-1.11-1.466-1.11-1.466-.908-.62.069-.608.069-.608 1.003.07 1.53 1.032 1.53 1.032.892 1.53 2.341 1.088 2.91.832.092-.647.35-1.088.636-1.338-2.22-.253-4.555-1.113-4.555-4.951 0-1.093.39-1.988 1.029-2.688-.103-.253-.446-1.272.098-2.65 0 0 .84-.27 2.75 1.026A9.564 9.564 0 0 1 12 6.844a9.59 9.59 0 0 1 2.504.337c1.909-1.296 2.747-1.027 2.747-1.027.546 1.379.202 2.398.1 2.651.64.7 1.028 1.595 1.028 2.688 0 3.848-2.339 4.695-4.566 4.943.359.309.678.92.678 1.855 0 1.338-.012 2.419-.012 2.747 0 .268.18.58.688.482A10.02 10.02 0 0 0 22 12.017C22 6.484 17.522 2 12 2Z"
      />
    </svg>
  );
}
