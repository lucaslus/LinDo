import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

test("the README, gallery and backdrop share the same themed screenshots", async ({ page }) => {
  const readme = readFileSync(resolve(__dirname, "../../README.md"), "utf8");
  await page.goto("./");
  const gallery = page.getByRole("list", { name: "各端界面" });
  for (const theme of ["light", "dark"]) {
    const sources = await gallery.locator(`.theme-shot-${theme}`).evaluateAll(images =>
      images.map(image => image.getAttribute("src")!.split("/shots/")[1])
    );
    const background = await page.locator(`.wall-img-${theme}`).evaluateAll(images =>
      images.map(image => image.getAttribute("src")!.split("/shots/")[1])
    );
    const readmeSources = await page.evaluate(({ readme, theme }) =>
      Array.from(new DOMParser().parseFromString(readme, "text/html")
        .querySelectorAll(`picture source[media="(prefers-color-scheme: ${theme})"]`),
        source => source.getAttribute("srcset")!.replace("website/public/shots/", "")),
    { readme, theme });
    expect(sources).toHaveLength(4);
    expect(readmeSources).toEqual(sources);
    expect(new Set(background)).toEqual(new Set(sources));
  }
});

for (const width of [360, 768, 1440]) {
  test(`four equal-height screenshots with a gap between each pair at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("./");
    const gallery = page.getByRole("list", { name: "各端界面" });
    await expect(gallery.getByRole("button")).toHaveCount(4);
    await expect(gallery).toHaveText("", { useInnerText: true });
    const sources: string[][] = [];

    for (const theme of ["浅色", "深色"]) {
      const toggle = page.getByRole("group", { name: "切换预览主题" }).getByRole("button", { name: theme });
      await toggle.click();
      await expect(toggle).toHaveAttribute("aria-pressed", "true");
      const pictures = gallery.locator("img:visible");
      await expect(pictures).toHaveCount(4);
      for (const picture of await pictures.all()) {
        await picture.scrollIntoViewIfNeeded();
        await expect.poll(() => picture.evaluate(image =>
          image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0
        )).toBe(true);
      }
      sources.push(await pictures.evaluateAll(images => images.map(image => image.getAttribute("src")!)));
      const boxes = await pictures.evaluateAll(images => images.map(image => {
        const { left, right, top, height } = image.getBoundingClientRect();
        return { left, right, top, height };
      }));
      for (const [i, current] of boxes.entries()) {
        if (i === 0) continue;
        const previous = boxes[i - 1];
        const gap = current.left - previous.right;
        expect(current.top).toBeCloseTo(previous.top, 0);
        expect(current.height).toBeCloseTo(previous.height, 0);
        expect(gap).toBeGreaterThanOrEqual(8);
        expect(gap).toBeLessThanOrEqual(18);
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
      if (width >= 768) {
        expect(boxes[0].left).toBeGreaterThanOrEqual(0);
        expect(boxes[3].right).toBeLessThanOrEqual(width);
      }
      if (width === 1440) {
        expect(boxes[0].height).toBeGreaterThan(250);
        expect(boxes[0].height).toBeLessThan(275);
      }
    }
    for (let i = 0; i < 4; i++) expect(sources[0][i]).not.toEqual(sources[1][i]);

    const fold = gallery.getByRole("button", { name: "放大折叠屏截图" });
    await fold.click();
    const dialog = page.getByRole("dialog", { name: "界面大图" });
    await expect(dialog.locator("img:visible")).toHaveAttribute("src", sources[1][2]);
    await dialog.getByRole("button", { name: "平板", exact: true }).click();
    await expect(dialog.locator("img:visible")).toHaveAttribute("src", sources[1][3]);
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(fold).toBeFocused();
  });
}

test("keyboard users can open each screenshot and return to its button", async ({ page }) => {
  await page.goto("./#screens");
  const gallery = page.getByRole("list", { name: "各端界面" });
  const dialog = page.getByRole("dialog", { name: "界面大图" });
  await gallery.getByRole("button", { name: "放大桌面端截图" }).focus();

  for (const name of ["桌面端", "手机", "折叠屏", "平板"]) {
    const button = gallery.getByRole("button", { name: `放大${name}截图`, exact: true });
    await expect(button).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(dialog.getByRole("button", { name, exact: true })).toHaveAttribute("aria-pressed", "true");
    await page.keyboard.press("Escape");
    await expect(button).toBeFocused();
    await page.keyboard.press("Tab");
  }
});

test.describe("touch", () => {
  test.use({ hasTouch: true, isMobile: true, viewport: { width: 360, height: 780 } });

  test("can reach and open the last screenshot in the horizontal strip", async ({ page }) => {
    await page.goto("./#screens");
    const strip = page.locator(".device-family-viewport");
    expect(await strip.evaluate(el => el.scrollWidth)).toBeGreaterThan(await strip.evaluate(el => el.clientWidth));
    await strip.evaluate(el => { el.scrollLeft = el.scrollWidth; });
    await strip.getByRole("button", { name: "放大平板截图" }).tap();
    await expect(page.getByRole("dialog").getByRole("button", { name: "平板", exact: true }))
      .toHaveAttribute("aria-pressed", "true");
  });
});
