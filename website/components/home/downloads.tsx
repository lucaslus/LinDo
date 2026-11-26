"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { AndroidLogo, AppleLogo, LinuxLogo, WindowsLogo } from "@phosphor-icons/react";
import { IconArrowRight, IconDownload, IconQrCode } from "@/components/icons";
import { encodeQrSvg } from "@/lib/qr";
import { formatMiB, type ReleaseDownloads } from "@/lib/release";
import { SITE } from "@/lib/site";

type Package = keyof ReleaseDownloads["assets"];

type Platform = {
  name: string;
  match: RegExp;
  notes: string;
  icon: typeof AndroidLogo;
  packages: [Package, string][];
};

const PLATFORMS: Platform[] = [
  { name: "Android", match: /Android/i, notes: "Android 7.0+ · arm64", icon: AndroidLogo, packages: [["android", "下载 APK"]] },
  { name: "macOS", match: /Macintosh|Mac OS X/i, notes: "macOS 11+ · DMG", icon: AppleLogo, packages: [["macArm64", "Apple Silicon"], ["macIntel", "Intel"]] },
  { name: "Windows", match: /Windows/i, notes: "Windows 10 / 11 · x64", icon: WindowsLogo, packages: [["winSetup", "安装程序"], ["winMsi", "MSI"]] },
  { name: "Linux", match: /Linux/i, notes: "Linux · x86_64", icon: LinuxLogo, packages: [["linuxDeb", "DEB"], ["linuxRpm", "RPM"], ["linuxAppImage", "AppImage"]] },
];

export function Downloads({ release }: { release: ReleaseDownloads }) {
  const [platform, setPlatform] = useState<string>();
  const [showQr, setShowQr] = useState(false);
  useEffect(() => {
    const ua = navigator.userAgent;
    if (!/iPhone|iPad/i.test(ua) && !(navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1)) {
      setPlatform(PLATFORMS.find(({ match }) => match.test(ua))?.name);
    }
  }, []);

  const apkUrl = release.assets.android?.url;
  const qrDirect = Boolean(apkUrl && apkUrl.length <= 120);
  const qr = showQr ? encodeQrSvg(qrDirect && apkUrl ? apkUrl : `${SITE.url}/#download`) : null;

  return (
    <section id="download" className="home-wrap home-section download-section" aria-labelledby="download-title">
      <div className="section-intro">
        <h2 id="download-title">下载 LinDo</h2>
      </div>

      <div className="download-grid">
        {PLATFORMS.map(({ name, notes, icon: Icon, packages }) => {
          const isCurrent = name === platform;
          const available = packages.flatMap(([key, label]) => {
            const asset = release.assets[key];
            return asset ? [{ label, asset }] : [];
          });
          return (
            <article key={name} className="download-card matte-card" data-current={isCurrent ? "true" : undefined}>
              <div className="download-card-top">
                <span className="download-os-icon-raw" aria-hidden="true">
                  <Icon size={34} weight="fill" />
                </span>
                {isCurrent && (
                  <span className="download-current-label">
                    <span className="download-current-dot" aria-hidden="true" />
                    当前系统
                  </span>
                )}
              </div>

              <h3>{name}</h3>
              <p className="t-meta">
                {notes}
                {name === "Android" && release.assets.android && ` · ${formatMiB(release.assets.android.bytes)}`}
              </p>

              <div className="package-actions">
                {available.length > 0 ? (
                  available.map(({ label, asset }, idx) => (
                    <a
                      key={label}
                      href={asset.url}
                      className={isCurrent && idx === 0 ? "dl-link dl-link-primary" : "dl-link"}
                      aria-label={`下载 ${name} ${label === "下载 APK" ? "APK" : label}`}
                    >
                      <IconDownload size={15} />
                      {label}
                    </a>
                  ))
                ) : (
                  <a href={release.url} className={isCurrent ? "dl-link dl-link-primary" : "dl-link"}>
                    查看发布页
                    <IconArrowRight size={14} />
                  </a>
                )}
              </div>

              <div className="download-card-foot">
                {name === "Android" && (
                  <>
                    <button
                      type="button"
                      className="home-text-link"
                      aria-controls="android-download-qr"
                      aria-expanded={showQr}
                      onClick={() => setShowQr(!showQr)}
                    >
                      <IconQrCode size={15} />
                      {showQr ? "收起二维码" : "手机扫码"}
                    </button>
                    <div id="android-download-qr" hidden={!qr}>
                      {qr && (
                        <div className="dl-qr-panel mt-2.5 flex">
                          <span className="dl-qr-box" aria-hidden="true">
                            <svg viewBox={`0 0 ${qr.size} ${qr.size}`} width={76} height={76} shapeRendering="crispEdges">
                              <rect width={qr.size} height={qr.size} fill="#fff" />
                              <path d={qr.path} fill="#11151e" />
                            </svg>
                          </span>
                          <p className="t-meta">{qrDirect ? "扫码下载 Android APK" : "扫码打开手机下载页"}</p>
                        </div>
                      )}
                    </div>
                  </>
                )}
                {name === "macOS" && (
                  <Link href="/docs/install#macos" className="home-text-link">
                    首次安装说明
                    <IconArrowRight size={14} />
                  </Link>
                )}
                {name === "Windows" && (
                  <Link href="/docs/install#windows" className="home-text-link">
                    安装说明
                    <IconArrowRight size={14} />
                  </Link>
                )}
                {name === "Linux" && (
                  <Link href="/docs/install#linux" className="home-text-link">
                    Arch / Omarchy 安装说明
                    <IconArrowRight size={14} />
                  </Link>
                )}
              </div>
            </article>
          );
        })}
      </div>

      <div className="download-links">
        <Link href="/docs/install" className="home-text-link">
          安装指南
          <IconArrowRight size={15} />
        </Link>
      </div>
    </section>
  );
}
