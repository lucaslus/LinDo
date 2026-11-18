"use client";

import { useEffect, useState, type ReactNode } from "react";
import clsx from "clsx";
import Link from "next/link";
import { AndroidLogo, AppleLogo, WindowsLogo } from "@phosphor-icons/react";
import {
  IconArrowRight,
  IconBookmark,
  IconDownload,
  IconGauge,
  IconPalette,
  IconShieldCheck,
  IconSplitColumns,
  IconUsers,
} from "@/components/icons";
import { LingduHead, Wordmark } from "@/components/brand";
import { DeviceShowcase, type DeviceKey } from "@/components/showcase";
import { SITE } from "@/lib/site";
import { Reveal } from "./shell";

/* ───────────────────────── 首屏 ───────────────────────── */

export function Hero({ devices }: { devices: DeviceKey[] }) {
  return (
    <section id="top" className="relative pt-14 sm:pt-22">
      <div className="relative mx-auto max-w-[1200px] px-6 text-center">
        <Reveal>
          <Link href="/changelog" className="update-pill">
            <span className="update-dot" />
            <span className="num">v{SITE.versions.android}</span>
            <span style={{ color: "var(--ink-3)" }}>·</span>
            <span style={{ color: "var(--ink-2)" }}>持续更新优化中</span>
            <IconArrowRight size={13} strokeWidth={2} />
          </Link>
          <h1 className="t-display mt-6">
            你好<span className="punct-tight">，</span>佬友<span className="punct-end">。</span>
          </h1>
          <p className="t-body mx-auto mt-4 max-w-[620px] text-[16px]">
            为 LINUX DO 打造的第三方客户端，Android 原生绘制，桌面端轻量常驻。
          </p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-2.5">
            <a href="#download" className="btn-primary">
              <IconDownload size={18} strokeWidth={2} />
              免费下载
            </a>
            <a href={SITE.repo} target="_blank" rel="noreferrer" className="btn-ghost">
              GitHub
              <IconArrowRight size={16} />
            </a>
          </div>
        </Reveal>
        <Reveal delay={120} className="mt-12 sm:mt-14">
          <DeviceShowcase devices={devices} />
        </Reveal>
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
    <section id="highlights" className="mx-auto max-w-[1200px] scroll-mt-16 px-6 py-24 sm:py-32">
      <Reveal className="max-w-[720px]">
        <h2 className="t-h2">Android 原生，桌面端轻量</h2>
      </Reveal>

      <Reveal delay={80} className="mt-12">
        <div className="grid sm:grid-cols-3" style={{ borderTop: "0.5px solid var(--hairline)", borderBottom: "0.5px solid var(--hairline)" }}>
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

/* ───────────────────────── 下载 ───────────────────────── */

type Os = "android" | "mac" | "win" | null;

export function DownloadSection() {
  const [os, setOs] = useState<Os>(null);
  useEffect(() => {
    const ua = navigator.userAgent;
    if (/Android/i.test(ua)) setOs("android");
    else if (/Mac OS X|Macintosh/i.test(ua) && !/iPhone|iPad/i.test(ua)) setOs("mac");
    else if (/Windows/i.test(ua)) setOs("win");
  }, []);

  const items = [
    { key: "android" as const, icon: AndroidLogo, name: "Android", version: SITE.versions.android, detail: "Android 7.0 及以上 · APK" },
    { key: "mac" as const, icon: AppleLogo, name: "macOS", version: SITE.versions.desktop, detail: "macOS 11 及以上 · DMG" },
    { key: "win" as const, icon: WindowsLogo, name: "Windows", version: SITE.versions.desktop, detail: "Windows 10/11 · MSI" },
  ];

  return (
    <section id="download" className="mx-auto max-w-[1200px] scroll-mt-16 px-6 py-24 sm:py-32">
      <Reveal className="text-center">
        <LingduHead size={72} expression="happy" className="mx-auto" />
        <h2 className="t-h2 mt-6">下载 LinDo</h2>
        <p className="t-body mt-3">免费开源，安装包均托管在 GitHub Releases。</p>
      </Reveal>
      <Reveal delay={80} className="mx-auto mt-12 max-w-[960px]">
        <div className="grid sm:grid-cols-3" style={{ borderTop: "0.5px solid var(--hairline)", borderBottom: "0.5px solid var(--hairline)" }}>
          {items.map(({ key, icon: Icon, name, version, detail }, i) => (
            <a
              key={key}
              href={SITE.releases}
              target="_blank"
              rel="noreferrer"
              className={clsx(
                "group flex flex-col items-start px-7 py-9 transition-colors hover:bg-[var(--hover)]",
                i > 0 && "max-sm:border-t sm:border-l",
              )}
              style={{ borderColor: "var(--hairline)" }}
            >
              <div className="flex w-full items-center gap-2.5">
                <Icon size={26} weight="fill" />
                <span className="text-[18px] font-semibold tracking-[-0.01em]">{name}</span>
                {os === key && (
                  <span className="ml-auto rounded-[5px] px-1.5 py-[1px] text-[12px] font-medium" style={{ background: "var(--chip-bg)", color: "var(--chip-text)" }}>
                    当前设备
                  </span>
                )}
              </div>
              <p className="t-meta mt-2">
                <span className="num">v{version}</span> · {detail}
              </p>
              <span className="mt-7 inline-flex items-center gap-1.5 text-[14.5px] font-semibold" style={{ color: "var(--accent)" }}>
                前往下载
                <span className="transition-transform group-hover:translate-x-0.5">
                  <IconArrowRight size={15} strokeWidth={2} />
                </span>
              </span>
            </a>
          ))}
        </div>
      </Reveal>
    </section>
  );
}

/* ───────────────────────── 页脚 ───────────────────────── */

export function SiteFooter() {
  return (
    <footer style={{ borderTop: "0.5px solid var(--hairline)" }}>
      <div className="mx-auto flex max-w-[1200px] flex-col gap-5 px-6 py-10 sm:flex-row sm:items-center">
        <div className="flex items-center gap-2">
          <LingduHead size={26} />
          <Wordmark size={17} />
        </div>
        <p className="text-[13px] sm:ml-4" style={{ color: "var(--ink-3)" }}>
          LINUX DO 第三方开源客户端 · 持续迭代维护中
        </p>
        <nav className="flex gap-5 text-[13.5px] sm:ml-auto" style={{ color: "var(--ink-2)" }}>
          <Link href="/changelog">更新日志</Link>
          <a href="/docs">文档</a>
          <a href={SITE.repo} target="_blank" rel="noreferrer">
            GitHub
          </a>
          <a href={SITE.forum} target="_blank" rel="noreferrer">
            LINUX DO
          </a>
        </nav>
      </div>
    </footer>
  );
}
