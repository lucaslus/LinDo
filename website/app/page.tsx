import { existsSync } from "node:fs";
import path from "node:path";
import { DownloadSection, Highlights, Hero, SecuritySection, SiteFooter } from "@/components/home/sections";
import { SiteNav } from "@/components/home/shell";
import type { DeviceKey } from "@/components/showcase";
import { getReleaseDownloads } from "@/lib/release";

export default async function HomePage() {
  const candidates: DeviceKey[] = ["phone", "fold", "tablet", "desktop"];
  const devices = candidates.filter((k) =>
    existsSync(path.join(process.cwd(), `public/shots/${k}-light.webp`)),
  );
  const release = await getReleaseDownloads();

  return (
    <div className="site">
      <SiteNav />
      <main>
        <Hero devices={devices.length > 0 ? devices : ["phone"]} release={release} />
        <Highlights desktopSize={release.sizes.desktopMb} androidSize={release.sizes.androidMb} />
        <SecuritySection />
        <DownloadSection release={release} />
      </main>
      <SiteFooter />
    </div>
  );
}
