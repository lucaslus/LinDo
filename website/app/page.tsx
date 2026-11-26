import { existsSync } from "node:fs";
import path from "node:path";
import { Downloads } from "@/components/home/downloads";
import { Hero, Highlights, SecuritySection, SiteFooter } from "@/components/home/sections";
import { SiteNav } from "@/components/home/shell";
import { APP_SCREENSHOTS, type ScreenshotKey } from "@/components/theme-screenshot";
import { getReleaseDownloads } from "@/lib/release";
import "./home.css";

export default async function HomePage() {
  const release = await getReleaseDownloads();
  const available = (Object.keys(APP_SCREENSHOTS) as ScreenshotKey[]).filter((key) => {
    const { light, dark } = APP_SCREENSHOTS[key];
    return [light, dark].every((shot) => existsSync(path.join(process.cwd(), "public", shot.slice(1))));
  });

  return (
    <div className="site home">
      <a className="home-skip" href="#main-content">跳到主要内容</a>
      <SiteNav />
      <main id="main-content" tabIndex={-1}>
        <Hero available={available} />
        <div className="home-sections-field">
          <Highlights release={release} />
          <SecuritySection />
          <Downloads release={release} />
        </div>
      </main>
      <SiteFooter />
    </div>
  );
}
