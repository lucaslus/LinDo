import { defineConfig } from "@playwright/test";

const deployedURL = process.env.PLAYWRIGHT_BASE_URL;
const baseURL = deployedURL ?? "http://127.0.0.1:3174/";

export default defineConfig({
  testDir: "./tests",
  use: { baseURL, channel: process.env.PLAYWRIGHT_CHANNEL, reducedMotion: "reduce" },
  webServer: deployedURL ? undefined : {
    command: "npm run start -- --hostname 127.0.0.1 --port 3174",
    url: baseURL,
    reuseExistingServer: !process.env.CI,
  },
});
