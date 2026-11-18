import { existsSync } from "node:fs";
import path from "node:path";
import { DownloadSection, Highlights, Hero, SiteFooter } from "@/components/home/sections";
import { SiteNav } from "@/components/home/shell";
import type { DeviceKey } from "@/components/showcase";

/** 桌面端安装包体积（MB，DMG），随发布更新 */
const DESKTOP_SIZE = "10";
/** Android release APK 体积（MB） */
const ANDROID_SIZE = "12";

export default function HomePage() {
  const candidates: DeviceKey[] = ["phone", "fold", "tablet", "desktop"];
  const devices = candidates.filter((k) =>
    existsSync(path.join(process.cwd(), `public/shots/${k}-light.webp`)),
  );
  return (
    <div className="site">
      <SiteNav />
      <main>
        <Hero devices={devices.length > 0 ? devices : ["phone"]} />
        <Highlights desktopSize={DESKTOP_SIZE} androidSize={ANDROID_SIZE} />
        <DownloadSection />
      </main>
      <SiteFooter />
    </div>
  );
}
