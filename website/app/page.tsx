import { Downloads } from "@/components/home/downloads";
import { Hero, Highlights, SecuritySection, SiteFooter } from "@/components/home/sections";
import { SiteNav } from "@/components/home/shell";
import { getReleaseDownloads } from "@/lib/release";
import "./home.css";

export default async function HomePage() {
  const release = await getReleaseDownloads();

  return (
    <div className="site home">
      <a className="home-skip" href="#main-content">跳到主要内容</a>
      <SiteNav />
      <main id="main-content" tabIndex={-1}>
        <Hero />
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
