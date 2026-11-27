import { expect, test } from "@playwright/test";

test("the homepage links to the verification guide and the community", async ({ page }) => {
  await page.goto("./");
  const community = page.getByRole("contentinfo").getByRole("link", { name: /LINUX DO 社区/ });
  await expect(community).toHaveAttribute("href", "https://linux.do/");
  await page.getByRole("link", { name: "查看验证指南" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "遇到 Cloudflare 验证怎么办？" })).toBeVisible();
  await expect(page.getByRole("cell").filter({ hasText: "Android" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Cloudflare 的排查步骤" })).toBeVisible();
});
