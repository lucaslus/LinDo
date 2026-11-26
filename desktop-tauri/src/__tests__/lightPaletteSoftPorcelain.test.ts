import fs from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { BRAND_THEMES, applyPaletteToDocument } from "../lib/palette";

function parseHexRgb(hex: string): [number, number, number] {
  const clean = hex.trim().replace(/^#/, "");
  expect(clean).toMatch(/^[0-9a-fA-F]{6}$/);
  return [
    Number.parseInt(clean.slice(0, 2), 16),
    Number.parseInt(clean.slice(2, 4), 16),
    Number.parseInt(clean.slice(4, 6), 16),
  ];
}

function relativeLuminance(hex: string): number {
  const [r, g, b] = parseHexRgb(hex).map((v) => v / 255);
  const toLinear = (c: number) =>
    c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  return 0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b);
}

function contrastRatio(hexA: string, hexB: string): number {
  const l1 = relativeLuminance(hexA);
  const l2 = relativeLuminance(hexB);
  const lighter = Math.max(l1, l2);
  const darker = Math.min(l1, l2);
  return (lighter + 0.05) / (darker + 0.05);
}

function readVar(name: string): string {
  return document.documentElement.style.getPropertyValue(name).trim();
}

describe("Desktop Light Theme Proposal A (柔雾瓷白 Soft Porcelain)", () => {
  it("applies exact Proposal A light tokens across all 26 CSS variables", () => {
    applyPaletteToDocument(false, "WARM_ORANGE", 1.0);

    expect(document.documentElement.classList.contains("dark")).toBe(false);
    expect(document.documentElement.style.colorScheme).toBe("light");

    expect(readVar("--canvas")).toBe("#F4F6F8");
    expect(readVar("--grouped-canvas")).toBe("#F4F6F8");
    expect(readVar("--card-surface")).toBe("#F8F9FB");
    expect(readVar("--feed-surface")).toBe("#ECEEF2");
    expect(readVar("--selected-card-bg")).toBe("#FAFBFD");
    expect(readVar("--selected-card-border")).toBe(
      "color-mix(in srgb, #9A3412 22%, rgba(15, 23, 42, 0.08))"
    );
    expect(readVar("--subtle-surface")).toBe("#E5E7EB");
    expect(readVar("--nav-bar-surface")).toBe("#E5E7EB");
    expect(readVar("--tonal-pill")).toBe("#DFE1E5");
    expect(readVar("--on-tonal-pill")).toBe("#161A22");
    expect(readVar("--ink-primary")).toBe("#161A22");
    expect(readVar("--ink-body")).toBe("#242933");
    expect(readVar("--ink-secondary")).toBe("#4F5665");
    expect(readVar("--ink-tertiary")).toBe("#5F6776");
    expect(readVar("--hairline")).toBe("rgba(15, 23, 42, 0.088)");
    expect(readVar("--hairline-soft")).toBe("rgba(15, 23, 42, 0.062)");
    expect(readVar("--accent")).toBe("#9A3412");
    expect(readVar("--brand-gold")).toBe("#9A3412");
    expect(readVar("--logo-do")).toBe("#E58E09");
    expect(readVar("--tab-indicator")).toBe("#9A3412");
    expect(readVar("--active-chip-bg")).toBe("#FFEDD5");
    expect(readVar("--active-chip-text")).toBe("#9A3412");
    expect(readVar("--publish-btn-bg")).toBe("#9A3412");
    expect(readVar("--publish-btn-fg")).toBe("#FFFFFF");
    expect(readVar("--hover-overlay")).toBe("rgba(15, 23, 42, 0.044)");
    expect(readVar("--press-overlay")).toBe("rgba(15, 23, 42, 0.078)");
  });

  it("enforces zero-yellow chromaticity (R <= G <= B, -6 <= R-B <= 0) and 5-tier anti-glare surface depth", () => {
    applyPaletteToDocument(false, "WARM_ORANGE", 1.0);

    const surfaceVars = [
      "--selected-card-bg",
      "--card-surface",
      "--canvas",
      "--grouped-canvas",
      "--feed-surface",
      "--subtle-surface",
      "--nav-bar-surface",
      "--tonal-pill",
    ];

    for (const varName of surfaceVars) {
      const hex = readVar(varName);
      expect(hex.toUpperCase()).not.toBe("#FFFFFF");
      const [r, g, b] = parseHexRgb(hex);
      expect(r).toBeLessThanOrEqual(g);
      expect(g).toBeLessThanOrEqual(b);
      expect(r - b).toBeGreaterThanOrEqual(-6);
      expect(r - b).toBeLessThanOrEqual(0);
    }

    const lumSelected = relativeLuminance(readVar("--selected-card-bg"));
    const lumCard = relativeLuminance(readVar("--card-surface"));
    const lumCanvas = relativeLuminance(readVar("--canvas"));
    const lumFeed = relativeLuminance(readVar("--feed-surface"));
    const lumSubtle = relativeLuminance(readVar("--subtle-surface"));
    const lumPill = relativeLuminance(readVar("--tonal-pill"));

    expect(lumCanvas).toBeLessThan(0.93);
    expect(lumCanvas).toBeGreaterThan(0.9);
    expect(lumSelected).toBeGreaterThan(lumCard);
    expect(lumCard).toBeGreaterThan(lumCanvas);
    expect(lumCanvas).toBeGreaterThan(lumFeed);
    expect(lumFeed).toBeGreaterThan(lumSubtle);
    expect(lumSubtle).toBeGreaterThan(lumPill);
  });

  it("passes WCAG 2.1 AA/AAA ink and brand-accent contrast across surfaces and all 7 brand themes", () => {
    applyPaletteToDocument(false, "WARM_ORANGE", 1.0);

    const canvas = readVar("--canvas");
    const feed = readVar("--feed-surface");
    const subtle = readVar("--subtle-surface");
    const pill = readVar("--tonal-pill");

    const inkPrimary = readVar("--ink-primary");
    const inkBody = readVar("--ink-body");
    const inkSecondary = readVar("--ink-secondary");
    const inkTertiary = readVar("--ink-tertiary");
    const onPill = readVar("--on-tonal-pill");

    expect(contrastRatio(inkPrimary, canvas)).toBeGreaterThanOrEqual(15.0);
    expect(contrastRatio(inkPrimary, canvas)).toBeGreaterThan(contrastRatio(inkBody, canvas));
    expect(contrastRatio(inkBody, canvas)).toBeGreaterThanOrEqual(12.0);
    expect(contrastRatio(inkBody, canvas)).toBeLessThanOrEqual(15.5);

    expect(contrastRatio(inkSecondary, canvas)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(inkSecondary, feed)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(inkSecondary, subtle)).toBeGreaterThanOrEqual(4.5);

    // Fixes prior #8A93A3 (3.10:1) WCAG AA failure on canvas, feed, and subtle surfaces
    expect(contrastRatio(inkTertiary, canvas)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(inkTertiary, feed)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(inkTertiary, subtle)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(onPill, pill)).toBeGreaterThanOrEqual(7.0);

    for (const theme of BRAND_THEMES) {
      applyPaletteToDocument(false, theme.key, 1.0);
      const accent = readVar("--accent");
      const brandGold = readVar("--brand-gold");
      const chipBg = readVar("--active-chip-bg");
      const chipText = readVar("--active-chip-text");
      const publishBg = readVar("--publish-btn-bg");
      const publishFg = readVar("--publish-btn-fg");

      if (theme.key === "OFFICIAL_GOLD") {
        expect(brandGold).toBe("#936000");
      }
      expect(readVar("--logo-do")).toBe("#E58E09");
      expect(contrastRatio(accent, canvas)).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(chipText, chipBg)).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(brandGold, canvas)).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(brandGold, feed)).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(publishFg, publishBg)).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("keeps Dark mode palette 100% untouched", () => {
    applyPaletteToDocument(true, "WARM_ORANGE", 1.0);

    expect(document.documentElement.classList.contains("dark")).toBe(true);
    expect(document.documentElement.style.colorScheme).toBe("dark");
    expect(readVar("--canvas")).toBe("#161A23");
    expect(readVar("--grouped-canvas")).toBe("#161A23");
    expect(readVar("--card-surface")).toBe("#161A23");
    expect(readVar("--feed-surface")).toBe("#11141B");
    expect(readVar("--selected-card-bg")).toBe("#1C212E");
    expect(readVar("--subtle-surface")).toBe("#1B202B");
    expect(readVar("--nav-bar-surface")).toBe("#0C0E13");
    expect(readVar("--tonal-pill")).toBe("#212735");
    expect(readVar("--ink-primary")).toBe("#DADBDF");
    expect(readVar("--ink-body")).toBe("#C9CBCF");
    expect(readVar("--ink-secondary")).toBe("#9297A3");
    expect(readVar("--ink-tertiary")).toBe("#6F7582");
    expect(readVar("--logo-do")).toBe("#F59E0B");
  });

  it("prevents hardcoded glaring #FFFFFF background panels outside intentional QR code quiet zones", () => {
    const srcRoot = path.resolve(__dirname, "..");
    const filesToCheck = [
      path.join(srcRoot, "styles/app.css"),
      path.join(srcRoot, "App.tsx"),
      path.join(srcRoot, "components/HomeFeedPane.tsx"),
      path.join(srcRoot, "components/TopicReaderPane.tsx"),
      path.join(srcRoot, "components/SideRail.tsx"),
      path.join(srcRoot, "components/ComposerModal.tsx"),
      path.join(srcRoot, "components/SecondaryScreens.tsx"),
    ];

    const offenders: string[] = [];
    for (const file of filesToCheck) {
      const lines = fs.readFileSync(file, "utf8").split("\n");
      lines.forEach((line, idx) => {
        if (
          /background(?:Color)?\s*:\s*["']#fff(?:fff)?["']/i.test(line) &&
          !lines.slice(Math.max(0, idx - 6), Math.min(lines.length, idx + 8)).some((ctx) =>
            ctx.includes("qrSvg") || ctx.includes("二维码")
          )
        ) {
          offenders.push(`${path.relative(srcRoot, file)}:${idx + 1}: ${line.trim()}`);
        }
      });
    }
    expect(offenders).toEqual([]);
  });
});
