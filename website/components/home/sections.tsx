import Link from "next/link";
import { LingduHead, Wordmark } from "@/components/brand";
import { HeroBackdropWall } from "@/components/home/hero-wall";
import {
  IconArrowRight,
  IconDownload,
  IconLock,
  IconQrCode,
  IconShieldCheck,
  IconSplitColumns,
} from "@/components/icons";
import { DeviceShowcase } from "@/components/showcase";
import { formatMiB, type ReleaseDownloads } from "@/lib/release";
import { SITE } from "@/lib/site";

export function Hero() {
  return (
    <section className="home-hero" aria-labelledby="home-title">
      <HeroBackdropWall />
      <div className="hero-inner">
        <div className="hero-header">
          <h1 id="home-title">
            Lin<span className="brand-do">Do</span>
          </h1>

          <p className="hero-subtitle">LINUX DO 第三方客户端</p>

          <div className="hero-actions">
            <a href="#download" className="btn-primary">
              <IconDownload size={17} />
              下载 LinDo
            </a>
            <Link href="/docs/install" className="btn-secondary">
              安装指南
              <IconArrowRight size={15} />
            </Link>
          </div>
        </div>

        <DeviceShowcase />
      </div>
    </section>
  );
}

export function Highlights({ release }: { release: ReleaseDownloads }) {
  const { android, macArm64 } = release.assets;
  return (
    <section id="highlights" className="home-wrap home-section" aria-labelledby="highlights-title">
      <div className="section-intro">
        <h2 id="highlights-title">功能</h2>
        <Link href="/docs/features" className="home-text-link">
          功能与设置
          <IconArrowRight size={15} />
        </Link>
      </div>

      <div className="home-features">
        <article className="feature-card matte-card">
          <span className="feature-icon-raw" aria-hidden="true">
            <IconShieldCheck size={34} strokeWidth={1.6} />
          </span>
          <h3>连接恢复</h3>
          <p>切网后尝试恢复加载。站点验证可在应用内完成。</p>
        </article>
        <article className="feature-card matte-card">
          <span className="feature-icon-raw" aria-hidden="true">
            <IconSplitColumns size={34} strokeWidth={1.6} />
          </span>
          <h3>折叠屏分栏</h3>
          <p>外屏单栏。展开后列表和正文并排，拖动分隔线可调宽度。</p>
        </article>
        <article className="feature-card matte-card">
          <span className="feature-icon-raw" aria-hidden="true">
            <IconQrCode size={34} strokeWidth={1.6} />
          </span>
          <h3>跨设备登录</h3>
          <p>在已登录的设备生成登录码。手机可扫码，电脑可识别二维码截图，也可以粘贴口令。</p>
        </article>
        <article className="feature-card matte-card">
          <span className="feature-icon-raw" aria-hidden="true">
            <LingduHead size={36} />
          </span>
          <h3>Android 启动图标</h3>
          <p>可换零度娘表情或 LINUX DO 标志。图标背景可跟随系统深浅色。</p>
        </article>
      </div>

      <div className="home-footprint matte-card">
        <div className="home-footprint-copy">
          <h3>原生 Android</h3>
          <p>帖子列表、正文和编辑器使用 Kotlin 与 Compose。</p>
        </div>
        {(android || macArm64) && (
          <div className="footprint-stats">
            {android && (
              <div className="package-stat">
                <strong className="num">{formatMiB(android.bytes)}</strong>
                <span>Android arm64 APK</span>
              </div>
            )}
            {macArm64 && (
              <div className="package-stat">
                <strong className="num">{formatMiB(macArm64.bytes)}</strong>
                <span>macOS Apple Silicon DMG</span>
              </div>
            )}
          </div>
        )}
      </div>
    </section>
  );
}

export function SecuritySection() {
  return (
    <section id="security" className="home-wrap home-section home-section-compact" aria-labelledby="security-title">
      <div className="home-security matte-card">
        <div className="security-head">
          <span className="security-icon-raw" aria-hidden="true">
            <IconLock size={32} strokeWidth={1.6} />
          </span>
          <div>
            <h2 id="security-title">隐私</h2>
            <p>登录凭证、草稿和阅读进度保存在设备上。LinDo 没有自建中转服务，也没有接入遥测 SDK。</p>
          </div>
        </div>
        <div className="security-actions">
          <Link href="/docs/security" className="home-text-link">
            网络与隐私
            <IconArrowRight size={15} />
          </Link>
          <a href={`${SITE.repo}/blob/main/.github/workflows/release.yml`} className="home-text-link">
            构建流程
            <IconArrowRight size={15} />
          </a>
        </div>
      </div>
    </section>
  );
}

export function SiteFooter() {
  return (
    <footer className="hairline-t">
      <div className="mx-auto flex max-w-[1120px] flex-col gap-5 px-5 py-9 sm:flex-row sm:items-center">
        <div className="flex items-center gap-3">
          <Link href="/" className="flex items-center gap-2" aria-label="LinDo 首页">
            <LingduHead size={26} />
            <Wordmark size={16} />
          </Link>
          <span className="text-[12.5px]" style={{ color: "var(--ink-3)" }}>
            LINUX DO 第三方开源客户端
          </span>
        </div>
        <nav
          className="flex flex-wrap gap-x-5 gap-y-2 text-[13.5px] sm:ml-auto"
          style={{ color: "var(--ink-2)" }}
          aria-label="页脚链接"
        >
          <Link href="/docs" className="transition-colors hover:text-[var(--ink-1)]">文档</Link>
          <Link href="/changelog" className="transition-colors hover:text-[var(--ink-1)]">更新日志</Link>
          <a href={SITE.issues} className="transition-colors hover:text-[var(--ink-1)]">反馈</a>
          <a href={SITE.repo} className="transition-colors hover:text-[var(--ink-1)]">GitHub</a>
          <a href={SITE.forum} className="transition-colors hover:text-[var(--ink-1)]">LINUX DO</a>
        </nav>
      </div>
    </footer>
  );
}
