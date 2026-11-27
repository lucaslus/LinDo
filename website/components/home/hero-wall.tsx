import { APP_SCREENSHOTS, type ScreenshotKey } from "@/components/theme-screenshot";
import { assetPath } from "@/lib/site";

type ShotTile = {
  kind: "shot";
  id: string;
  shot: ScreenshotKey;
  pos: string;
  size?: "sm" | "md" | "lg";
};

type MotifTile = {
  kind: "motif";
  id: string;
  variant: "feed" | "reader" | "palette" | "split" | "qr" | "connect" | "brand";
  size?: "sm" | "md" | "lg";
};

type WallTile = ShotTile | MotifTile;

const ROWS: readonly (readonly WallTile[])[] = [
  [
    { kind: "shot", id: "r1-1", shot: "desktop", pos: "0% 10%", size: "md" },
    { kind: "motif", id: "r1-2", variant: "palette", size: "sm" },
    { kind: "shot", id: "r1-3", shot: "fold", pos: "50% 15%", size: "lg" },
    { kind: "motif", id: "r1-4", variant: "feed", size: "md" },
    { kind: "shot", id: "r1-5", shot: "desktop", pos: "85% 22%", size: "lg" },
    { kind: "motif", id: "r1-6", variant: "qr", size: "sm" },
    { kind: "shot", id: "r1-7", shot: "tablet", pos: "18% 45%", size: "md" },
  ],
  [
    { kind: "motif", id: "r2-1", variant: "reader", size: "md" },
    { kind: "shot", id: "r2-2", shot: "desktop", pos: "14% 28%", size: "lg" },
    { kind: "motif", id: "r2-3", variant: "split", size: "sm" },
    { kind: "shot", id: "r2-4", shot: "desktop", pos: "76% 18%", size: "md" },
    { kind: "motif", id: "r2-5", variant: "connect", size: "md" },
    { kind: "shot", id: "r2-6", shot: "fold", pos: "8% 82%", size: "lg" },
    { kind: "motif", id: "r2-7", variant: "brand", size: "sm" },
  ],
  [
    { kind: "shot", id: "r3-1", shot: "tablet", pos: "82% 20%", size: "lg" },
    { kind: "motif", id: "r3-2", variant: "feed", size: "md" },
    { kind: "shot", id: "r3-3", shot: "phone", pos: "50% 24%", size: "sm" },
    { kind: "motif", id: "r3-4", variant: "reader", size: "md" },
    { kind: "shot", id: "r3-5", shot: "desktop", pos: "22% 58%", size: "lg" },
    { kind: "motif", id: "r3-6", variant: "palette", size: "sm" },
    { kind: "shot", id: "r3-7", shot: "desktop", pos: "70% 56%", size: "md" },
  ],
  [
    { kind: "motif", id: "r4-1", variant: "qr", size: "sm" },
    { kind: "shot", id: "r4-2", shot: "fold", pos: "68% 42%", size: "lg" },
    { kind: "motif", id: "r4-3", variant: "reader", size: "md" },
    { kind: "shot", id: "r4-4", shot: "desktop", pos: "20% 72%", size: "md" },
    { kind: "motif", id: "r4-5", variant: "split", size: "sm" },
    { kind: "shot", id: "r4-6", shot: "tablet", pos: "30% 18%", size: "lg" },
    { kind: "motif", id: "r4-7", variant: "connect", size: "md" },
  ],
];

const SWATCHES = ["#F97316", "#F59E0B", "#2563EB", "#059669", "#E03E3E", "#8B5CF6", "#0891B2"];

function MotifCard({ variant }: { variant: MotifTile["variant"] }) {
  switch (variant) {
    case "feed":
      return (
        <div className="wall-motif wall-motif-feed">
          <div className="wm-row">
            <span className="wm-dot" style={{ background: "#3B82F6" }} />
            <div className="wm-lines">
              <span className="wm-bar wm-bar-lg" />
              <span className="wm-bar wm-bar-sm" />
            </div>
            <span className="wm-chip">开发调优</span>
          </div>
          <div className="wm-row">
            <span className="wm-dot" style={{ background: "#10B981" }} />
            <div className="wm-lines">
              <span className="wm-bar wm-bar-md" />
              <span className="wm-bar wm-bar-xs" />
            </div>
            <span className="wm-chip">资源荟萃</span>
          </div>
          <div className="wm-row">
            <span className="wm-dot" style={{ background: "#F59E0B" }} />
            <div className="wm-lines">
              <span className="wm-bar wm-bar-lg" />
              <span className="wm-bar wm-bar-sm" />
            </div>
            <span className="wm-chip">前沿快讯</span>
          </div>
        </div>
      );
    case "reader":
      return (
        <div className="wall-motif wall-motif-reader">
          <div className="wm-head">
            <span className="wm-avatar" />
            <span className="wm-bar wm-bar-sm" />
            <span className="wm-tag">Markdown</span>
          </div>
          <div className="wm-code">
            <span className="wm-code-line" style={{ width: "76%" }} />
            <span className="wm-code-line wm-code-accent" style={{ width: "58%" }} />
            <span className="wm-code-line" style={{ width: "86%" }} />
            <span className="wm-code-line" style={{ width: "44%" }} />
          </div>
        </div>
      );
    case "palette":
      return (
        <div className="wall-motif wall-motif-palette">
          <span className="wm-kicker">THEME PALETTE</span>
          <div className="wm-swatches">
            {SWATCHES.map((hex) => (
              <span key={hex} className="wm-swatch" style={{ background: hex }} />
            ))}
          </div>
          <div className="wm-bar wm-bar-md" />
        </div>
      );
    case "split":
      return (
        <div className="wall-motif wall-motif-split">
          <div className="wm-pane">
            <span className="wm-bar wm-bar-md" />
            <span className="wm-bar wm-bar-lg" />
            <span className="wm-bar wm-bar-sm" />
            <span className="wm-pill-nav" />
          </div>
          <span className="wm-divider" />
          <div className="wm-pane">
            <span className="wm-bar wm-bar-lg" />
            <span className="wm-bar wm-bar-md" />
            <span className="wm-bar wm-bar-lg" />
            <span className="wm-bar wm-bar-sm" />
          </div>
        </div>
      );
    case "qr":
      return (
        <div className="wall-motif wall-motif-qr">
          <svg viewBox="0 0 44 44" width="54" height="54" className="wm-qr-svg" aria-hidden="true">
            <rect x="3" y="3" width="12" height="12" rx="2" fill="none" stroke="currentColor" strokeWidth="2.4" />
            <rect x="6.5" y="6.5" width="5" height="5" rx="1" fill="currentColor" />
            <rect x="29" y="3" width="12" height="12" rx="2" fill="none" stroke="currentColor" strokeWidth="2.4" />
            <rect x="32.5" y="6.5" width="5" height="5" rx="1" fill="currentColor" />
            <rect x="3" y="29" width="12" height="12" rx="2" fill="none" stroke="currentColor" strokeWidth="2.4" />
            <rect x="6.5" y="32.5" width="5" height="5" rx="1" fill="currentColor" />
            <rect x="20" y="6" width="4" height="4" rx="1" fill="currentColor" />
            <rect x="20" y="14" width="4" height="4" rx="1" fill="currentColor" />
            <rect x="20" y="22" width="6" height="6" rx="1.2" fill="var(--logo-do)" />
            <rect x="30" y="21" width="5" height="5" rx="1" fill="currentColor" />
            <rect x="21" y="33" width="6" height="5" rx="1" fill="currentColor" />
            <rect x="32" y="31" width="8" height="8" rx="1.5" fill="currentColor" />
          </svg>
          <span className="wm-kicker">QR AUTH</span>
        </div>
      );
    case "connect":
      return (
        <div className="wall-motif wall-motif-connect">
          <span className="wm-kicker">CONNECT · TRUST</span>
          <div className="wm-bars">
            <span style={{ height: "42%" }} />
            <span style={{ height: "68%" }} />
            <span style={{ height: "86%", background: "var(--logo-do)" }} />
            <span style={{ height: "60%" }} />
            <span style={{ height: "92%" }} />
            <span style={{ height: "74%" }} />
          </div>
        </div>
      );
    case "brand":
      return (
        <div className="wall-motif wall-motif-brand">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={assetPath("/brand/lindo-mark.svg")} alt="" width={40} height={40} className="wm-mascot" loading="lazy" decoding="async" />
          <span className="wm-wordmark">
            Lin<span style={{ color: "var(--logo-do)" }}>Do</span>
          </span>
        </div>
      );
  }
}

const MARQUEE_COPIES = [0, 1, 2] as const;

export function HeroBackdropWall() {
  return (
    <div className="hero-wall-viewport" aria-hidden="true">
      <div className="hero-wall-plane">
        {ROWS.map((row, rowIndex) => (
          <div key={rowIndex} className="hero-wall-row">
            <div
              className={`hero-wall-track hero-wall-track-${rowIndex + 1} ${
                rowIndex % 2 === 0 ? "hero-wall-track-left" : "hero-wall-track-right"
              }`}
            >
              {MARQUEE_COPIES.map((copy) => (
                <div key={copy} className="hero-wall-group">
                  {row.map((tile) => (
                    <div key={`${copy}-${tile.id}`} className={`wall-tile wall-tile-${tile.size ?? "md"}`}>
                      {tile.kind === "shot" ? (
                        <>
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img
                            src={assetPath(APP_SCREENSHOTS[tile.shot].light)}
                            alt=""
                            className="wall-tile-img wall-img-light"
                            style={{ objectPosition: tile.pos }}
                            decoding="async"
                          />
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img
                            src={assetPath(APP_SCREENSHOTS[tile.shot].dark)}
                            alt=""
                            className="wall-tile-img wall-img-dark"
                            style={{ objectPosition: tile.pos }}
                            decoding="async"
                          />
                        </>
                      ) : (
                        <MotifCard variant={tile.variant} />
                      )}
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
      <div className="hero-wall-scrim" />
    </div>
  );
}
