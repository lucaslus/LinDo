"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import clsx from "clsx";
import Link from "next/link";
import { AndroidLogo, AppleLogo, WindowsLogo } from "@phosphor-icons/react";
import {
  IconArrowRight,
  IconBookmark,
  IconCodeAudit,
  IconCompass,
  IconDownload,
  IconFileCheck,
  IconGauge,
  IconLock,
  IconPalette,
  IconQrCode,
  IconShieldCheck,
  IconSplitColumns,
  IconUsers,
} from "@/components/icons";
import { LingduHead, SecuritySeal, Wordmark } from "@/components/brand";
import { DeviceShowcase, type DeviceKey } from "@/components/showcase";
import { encodeQrSvg } from "@/lib/qr";
import type { ReleaseDownloads } from "@/lib/release";
import { SITE } from "@/lib/site";
import { Reveal } from "./shell";

type Os = "android" | "mac" | "win" | null;
type MacArch = "arm64" | "x64";

function useVisitorPlatform() {
  const [os, setOs] = useState<Os>(null);
  const [macArch, setMacArch] = useState<MacArch>("arm64");

  useEffect(() => {
    const ua = navigator.userAgent;
    if (/Android/i.test(ua)) {
      setOs("android");
      return;
    }
    if (/Mac OS X|Macintosh/i.test(ua) && !/iPhone|iPad/i.test(ua)) {
      setOs("mac");
      const uaData = (
        navigator as Navigator & {
          userAgentData?: { getHighEntropyValues?: (hints: string[]) => Promise<{ architecture?: string }> };
        }
      ).userAgentData;
      uaData
        ?.getHighEntropyValues?.(["architecture"])
        .then((res) => {
          if (res.architecture === "x86") setMacArch("x64");
        })
        .catch(() => {});
      return;
    }
    if (/Windows/i.test(ua)) {
      setOs("win");
    }
  }, []);

  return { os, macArch };
}

/* ───────────────────────── 首屏 ───────────────────────── */

export function Hero({ devices, release }: { devices: DeviceKey[]; release: ReleaseDownloads }) {
  const { os, macArch } = useVisitorPlatform();

  const primaryCta = useMemo(() => {
    if (!release.hasDirectAssets || !os) {
      const label =
        os === "mac"
          ? "下载 macOS 版"
          : os === "win"
            ? "下载 Windows 版"
            : os === "android"
              ? "下载 Android 版"
              : "免费下载";
      return { label, href: "#download", external: false };
    }
    if (os === "mac") {
      return {
        label: macArch === "x64" ? "下载 macOS 版 (Intel)" : "下载 macOS 版",
        href: macArch === "x64" ? release.mac.x64Url : release.mac.arm64Url,
        external: true,
      };
    }
    if (os === "win") {
      return { label: "下载 Windows 版", href: release.win.exeUrl, external: true };
    }
    return { label: "下载 Android 版", href: release.android.apkUrl, external: true };
  }, [macArch, os, release]);

  return (
    <section id="top" className="relative pt-12 sm:pt-20">
      <div className="relative mx-auto max-w-[1200px] px-5 text-center sm:px-6">
        <div className="hero-enter">
          <Link href="/changelog" className="update-pill">
            <span className="update-dot" />
            <span className="num">Android v{release.versions.android}</span>
            <span style={{ color: "var(--ink-3)" }}>·</span>
            <span className="num">桌面 v{release.versions.desktop}</span>
            <span className="hidden sm:inline" style={{ color: "var(--ink-3)" }}>
              ·
            </span>
            <span className="hidden sm:inline" style={{ color: "var(--ink-2)" }}>
              持续更新中
            </span>
            <IconArrowRight size={13} strokeWidth={2} />
          </Link>
          <h1 className="t-display mt-6">
            你好<span className="punct-tight">，</span>佬友<span className="punct-end">。</span>
          </h1>
          <p className="t-body mx-auto mt-4 max-w-[620px] text-[16px]">
            为 LINUX DO 打造的第三方客户端，Android 原生绘制，桌面端轻量常驻。
          </p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-2.5">
            <a
              href={primaryCta.href}
              {...(primaryCta.external ? { target: "_blank", rel: "noreferrer" } : {})}
              className="btn-primary"
            >
              <IconDownload size={18} strokeWidth={2} />
              {primaryCta.label}
            </a>
            {primaryCta.external && (
              <a href="#download" className="btn-ghost">
                全部平台
              </a>
            )}
            <a href={SITE.repo} target="_blank" rel="noreferrer" className="btn-ghost">
              GitHub
              <IconArrowRight size={16} />
            </a>
          </div>
          <div className="mt-4">
            <a href="#security" className="hero-trust">
              <span style={{ color: "#10b981" }} className="inline-flex">
                <IconShieldCheck size={15} strokeWidth={2} />
              </span>
              <span>零隐私收集 · 纯本地存储 · 通过代码安全审计 · 100% 开源透明</span>
            </a>
          </div>
        </div>
        <div className="hero-enter hero-enter-delay mt-11 sm:mt-13">
          <DeviceShowcase devices={devices} />
        </div>
      </div>
    </section>
  );
}

/* ───────────────────────── 亮点 ───────────────────────── */

function Stat({ value, unit, title, children }: { value: string; unit?: string; title: string; children: ReactNode }) {
  return (
    <div className="px-6 py-10 sm:px-8 sm:py-12">
      <p className="hl-num">
        {value}
        {unit && (
          <span className="ml-1 text-[0.4em] font-medium tracking-normal" style={{ color: "var(--ink-2)" }}>
            {unit}
          </span>
        )}
      </p>
      <p className="t-h3 mt-6">{title}</p>
      <p className="t-body mt-1.5">{children}</p>
    </div>
  );
}

const DETAILS = [
  {
    icon: IconGauge,
    title: "高刷跟手",
    text: "Android 自动启用屏幕最高刷新率。macOS 解除了 WebView 帧率上限，鼠标滚轮带平滑惯性。",
  },
  {
    icon: IconBookmark,
    title: "读到哪记到哪",
    text: "展开楼层、完成验证或返回列表后，刚才读到的楼层与未发出的草稿都在原处。",
  },
  {
    icon: IconShieldCheck,
    title: "应用内完成验证",
    text: "遇到站点验证直接在当前窗口通过，结束后自动接续刚才的操作。",
  },
  {
    icon: IconPalette,
    title: "字号与配色",
    text: "五档字号、七种主题色，深浅模式各有单独调校的对比度与层级。",
  },
  {
    icon: IconSplitColumns,
    title: "宽屏分栏可调",
    text: "折叠屏与平板默认居中对半分栏，可拖拽调整宽度，也能在设置里切回单栏扩宽。",
  },
  {
    icon: IconUsers,
    title: "多账号切换",
    text: "支持同时保存多个社区账号，点击头像即可切换。",
  },
];

export function Highlights({ desktopSize, androidSize }: { desktopSize: string; androidSize: string }) {
  return (
    <section id="highlights" className="mx-auto max-w-[1200px] scroll-mt-16 px-5 py-24 sm:px-6 sm:py-32">
      <Reveal className="max-w-[720px]">
        <h2 className="t-h2">Android 原生，桌面端轻量</h2>
      </Reveal>

      <Reveal delay={80} className="mt-12">
        <div
          className="grid sm:grid-cols-3"
          style={{ borderTop: "0.5px solid var(--hairline)", borderBottom: "0.5px solid var(--hairline)" }}
        >
          <Stat value={androidSize} unit="MB" title="Android 安装包">
            基于 Kotlin 与 Compose 构建，界面由系统原生直接渲染。
          </Stat>
          <div className="max-sm:border-t sm:border-l" style={{ borderColor: "var(--hairline)" }}>
            <Stat value="165" unit="Hz" title="自适应高刷新率">
              覆盖 60 至 165Hz 屏幕，折叠屏开合切屏后自动重新匹配帧率。
            </Stat>
          </div>
          <div className="max-sm:border-t sm:border-l" style={{ borderColor: "var(--hairline)" }}>
            <Stat value={desktopSize} unit="MB" title="macOS 安装包">
              基于 Tauri 2 构建，直接复用系统 WebView，不捆绑浏览器内核。
            </Stat>
          </div>
        </div>
      </Reveal>

      <div className="mt-20 grid gap-x-14 gap-y-12 sm:grid-cols-2 lg:grid-cols-3">
        {DETAILS.map(({ icon: Icon, title, text }, i) => (
          <Reveal key={title} delay={(i % 3) * 70}>
            <span className="feature-icon">
              <Icon size={20} />
            </span>
            <p className="t-h3 mt-4">{title}</p>
            <p className="t-body mt-1.5">{text}</p>
          </Reveal>
        ))}
      </div>
    </section>
  );
}

/* ───────────────────────── 安全、隐私与开源共建 ───────────────────────── */

const TRUST_PILLARS = [
  {
    icon: IconLock,
    title: "零隐私收集，数据不出本机",
    text: "不设任何自建中转后端，未接入任何统计、广告或崩溃上报 SDK。登录 Cookie、API Key、草稿箱、阅读历史与诊断日志 100% 仅保存在设备本地沙盒。",
  },
  {
    icon: IconCodeAudit,
    title: "代码安全审计与防钓鱼保护",
    text: "网络与渲染链路经过专项代码安全审计：内置外链域名二次确认、IDN 同形异义词（xn--）与伪装钓鱼链接拦截，应用内 WebView 严格限制受控域名白名单。",
  },
  {
    icon: IconFileCheck,
    title: "透明自动构建与 SHA-256 核验",
    text: "全平台安装包均由 GitHub Actions 官方托管环境从公开源码自动编译打包，每次发版自动生成 SHA256SUMS.txt 哈希清单，支持随时校验指纹或自行编译。",
  },
  {
    icon: IconCompass,
    title: "完全开源共建 · 长期更新与本地周边",
    text: "欢迎各位佬友开展独立代码审计、反馈安全或体验问题、提交优化需求与 PR。我们将长期持续维护，并在坚持「纯本地处理」的前提下陆续加入桌面陪伴挂件、离线归档检索等特色周边功能。",
  },
];

export function SecuritySection() {
  return (
    <section
      id="security"
      className="scroll-mt-16"
      style={{ borderTop: "0.5px solid var(--hairline)", borderBottom: "0.5px solid var(--hairline)" }}
    >
      <div className="mx-auto max-w-[1200px] px-5 py-24 sm:px-6 sm:py-28">
        <Reveal className="mx-auto max-w-[680px] text-center">
          <SecuritySeal size={92} className="mx-auto" />
          <div className="mt-5">
            <span className="trust-eyebrow">
              <IconShieldCheck size={14} strokeWidth={2.1} />
              零遥测 · 代码安全审计 · 100% 开源可查
            </span>
          </div>
          <h2 className="t-h2 mt-4">纯粹干净，把放心写进源码里</h2>
          <p className="t-body mt-3.5 text-[15.5px]">
            第三方社区客户端最核心的底线是安全与信任。LinDo 只与 LINUX DO 官方服务直连通信，不触碰、不中转、不收集任何用户隐私数据，每一行代码均在 GitHub 公开接受社区检验。
          </p>
        </Reveal>

        <Reveal delay={80} className="mx-auto mt-14 max-w-[1040px]">
          <div
            className="grid sm:grid-cols-2"
            style={{ borderTop: "0.5px solid var(--hairline)", borderBottom: "0.5px solid var(--hairline)" }}
          >
            {TRUST_PILLARS.map(({ icon: Icon, title, text }, i) => (
              <div
                key={title}
                className={clsx(
                  "px-6 py-9 sm:px-9 sm:py-10",
                  i > 0 && "max-sm:border-t",
                  i >= 2 && "sm:border-t",
                  i % 2 === 1 && "sm:border-l",
                )}
                style={{ borderColor: "var(--hairline)" }}
              >
                <span className="trust-icon">
                  <Icon size={19} />
                </span>
                <h3 className="t-h3 mt-4">{title}</h3>
                <p className="t-body mt-2">{text}</p>
              </div>
            ))}
          </div>
        </Reveal>

        <Reveal delay={130} className="mt-9 flex flex-wrap items-center justify-center gap-2.5">
          <Link href="/docs/security" className="dl-pill">
            <IconShieldCheck size={15} />
            查看安全与网络边界说明
          </Link>
          <a
            href={`${SITE.repo}/blob/main/.github/workflows/release.yml`}
            target="_blank"
            rel="noreferrer"
            className="dl-pill"
          >
            <IconFileCheck size={15} />
            核验自动构建流水线
          </a>
          <a href={SITE.issues} target="_blank" rel="noreferrer" className="dl-pill">
            <IconCodeAudit size={15} />
            参与代码审计 / 提优化建议
          </a>
        </Reveal>
      </div>
    </section>
  );
}

/* ───────────────────────── 下载 ───────────────────────── */

export function DownloadSection({ release }: { release: ReleaseDownloads }) {
  const { os, macArch } = useVisitorPlatform();
  const [showQr, setShowQr] = useState(false);
  const qr = useMemo(() => encodeQrSvg(release.android.qrUrl), [release.android.qrUrl]);

  return (
    <section id="download" className="mx-auto max-w-[1200px] scroll-mt-16 px-5 py-24 sm:px-6 sm:py-32">
      <Reveal className="text-center">
        <LingduHead size={72} expression="happy" className="mx-auto" />
        <h2 className="t-h2 mt-6">下载 LinDo</h2>
        <p className="t-body mt-3">
          免费开源，所有构建产物与 SHA-256 校验值均托管在{" "}
          <a
            href={SITE.releasesPage}
            target="_blank"
            rel="noreferrer"
            className="underline decoration-[var(--hairline)] underline-offset-4 transition-colors hover:text-[var(--ink-1)]"
          >
            GitHub Releases
          </a>
          。
        </p>
      </Reveal>

      <Reveal delay={80} className="mx-auto mt-12 max-w-[1020px]">
        <div
          className="grid sm:grid-cols-3"
          style={{ borderTop: "0.5px solid var(--hairline)", borderBottom: "0.5px solid var(--hairline)" }}
        >
          {/* Android */}
          <div className="flex flex-col items-start px-6 py-9 sm:px-7">
            <div className="flex w-full items-center gap-2.5">
              <AndroidLogo size={26} weight="fill" />
              <span className="text-[18px] font-semibold tracking-[-0.01em]">Android</span>
              {os === "android" && (
                <span
                  className="ml-auto rounded-[5px] px-1.5 py-[1px] text-[12px] font-medium"
                  style={{ background: "var(--chip-bg)", color: "var(--chip-text)" }}
                >
                  当前设备
                </span>
              )}
            </div>
            <p className="t-meta mt-2">
              <span className="num">v{release.versions.android}</span> · Android 7.0+ · APK
            </p>

            <div className="mt-7 flex flex-wrap items-center gap-2">
              <a href={release.android.apkUrl} target="_blank" rel="noreferrer" className="dl-pill is-primary">
                <IconDownload size={15} strokeWidth={2} />
                下载 APK
              </a>
              <button
                type="button"
                onClick={() => setShowQr((v) => !v)}
                aria-expanded={showQr}
                className={clsx("dl-pill hidden sm:inline-flex", showQr && "is-selected")}
              >
                <IconQrCode size={15} />
                {showQr ? "收起二维码" : "手机扫码"}
              </button>
            </div>

            {showQr && (
              <div className="dl-qr-panel mt-4 hidden sm:flex">
                <span className="dl-qr-box" aria-hidden>
                  <svg viewBox={`0 0 ${qr.size} ${qr.size}`} width={88} height={88} shapeRendering="crispEdges">
                    <rect width={qr.size} height={qr.size} fill="#ffffff" />
                    <path d={qr.path} fill="#111827" />
                  </svg>
                </span>
                <div>
                  <p className="text-[13.5px] font-semibold" style={{ color: "var(--ink-1)" }}>
                    手机相机扫码安装
                  </p>
                  <p className="t-meta mt-1">
                    {release.hasDirectAssets ? "直接拉起最新 APK 下载" : "在手机浏览器中打开下载页"}
                  </p>
                </div>
              </div>
            )}
          </div>

          {/* macOS */}
          <div
            className="flex flex-col items-start px-6 py-9 max-sm:border-t sm:border-l sm:px-7"
            style={{ borderColor: "var(--hairline)" }}
          >
            <div className="flex w-full items-center gap-2.5">
              <AppleLogo size={26} weight="fill" />
              <span className="text-[18px] font-semibold tracking-[-0.01em]">macOS</span>
              {os === "mac" && (
                <span
                  className="ml-auto rounded-[5px] px-1.5 py-[1px] text-[12px] font-medium"
                  style={{ background: "var(--chip-bg)", color: "var(--chip-text)" }}
                >
                  当前设备
                </span>
              )}
            </div>
            <p className="t-meta mt-2">
              <span className="num">v{release.versions.desktop}</span> · macOS 11+ · DMG
            </p>

            <div className="mt-7 flex flex-wrap items-center gap-2">
              <a
                href={release.mac.arm64Url}
                target="_blank"
                rel="noreferrer"
                className={clsx("dl-pill", macArch === "arm64" && "is-primary")}
              >
                {macArch === "arm64" && <IconDownload size={15} strokeWidth={2} />}
                Apple Silicon
              </a>
              <a
                href={release.mac.x64Url}
                target="_blank"
                rel="noreferrer"
                className={clsx("dl-pill", macArch === "x64" && "is-primary")}
              >
                {macArch === "x64" && <IconDownload size={15} strokeWidth={2} />}
                Intel 芯片
              </a>
            </div>
            <Link href="/docs/install#macos" className="t-meta mt-3.5 transition-colors hover:text-[var(--ink-1)]">
              首次打开提示无法验证开发者？ →
            </Link>
          </div>

          {/* Windows */}
          <div
            className="flex flex-col items-start px-6 py-9 max-sm:border-t sm:border-l sm:px-7"
            style={{ borderColor: "var(--hairline)" }}
          >
            <div className="flex w-full items-center gap-2.5">
              <WindowsLogo size={26} weight="fill" />
              <span className="text-[18px] font-semibold tracking-[-0.01em]">Windows</span>
              {os === "win" && (
                <span
                  className="ml-auto rounded-[5px] px-1.5 py-[1px] text-[12px] font-medium"
                  style={{ background: "var(--chip-bg)", color: "var(--chip-text)" }}
                >
                  当前设备
                </span>
              )}
            </div>
            <p className="t-meta mt-2">
              <span className="num">v{release.versions.desktop}</span> · Windows 10/11 · x64
            </p>

            <div className="mt-7 flex flex-wrap items-center gap-2">
              <a href={release.win.exeUrl} target="_blank" rel="noreferrer" className="dl-pill is-primary">
                <IconDownload size={15} strokeWidth={2} />
                安装程序 (.exe)
              </a>
              <a href={release.win.msiUrl} target="_blank" rel="noreferrer" className="dl-pill">
                MSI 包
              </a>
            </div>
          </div>
        </div>
      </Reveal>
    </section>
  );
}

/* ───────────────────────── 页脚 ───────────────────────── */

export function SiteFooter() {
  return (
    <footer style={{ borderTop: "0.5px solid var(--hairline)" }}>
      <div className="mx-auto flex max-w-[1200px] flex-col gap-5 px-5 py-10 sm:flex-row sm:items-center sm:px-6">
        <div className="flex items-center gap-2">
          <LingduHead size={26} />
          <Wordmark size={17} />
        </div>
        <p className="text-[13px] sm:ml-4" style={{ color: "var(--ink-3)" }}>
          LINUX DO 第三方开源客户端 · 零隐私收集 · 长期迭代维护中
        </p>
        <nav className="flex flex-wrap gap-5 text-[13.5px] sm:ml-auto" style={{ color: "var(--ink-2)" }} aria-label="页脚链接">
          <Link href="/docs/security" className="transition-colors hover:text-[var(--ink-1)]">
            安全与隐私
          </Link>
          <Link href="/changelog" className="transition-colors hover:text-[var(--ink-1)]">
            更新日志
          </Link>
          <Link href="/docs" className="transition-colors hover:text-[var(--ink-1)]">
            文档
          </Link>
          <a href={SITE.repo} target="_blank" rel="noreferrer" className="transition-colors hover:text-[var(--ink-1)]">
            GitHub
          </a>
          <a href={SITE.forum} target="_blank" rel="noreferrer" className="transition-colors hover:text-[var(--ink-1)]">
            LINUX DO
          </a>
        </nav>
      </div>
    </footer>
  );
}
