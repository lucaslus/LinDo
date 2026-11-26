import { SITE } from "./site";

const RELEASE_API = "https://api.github.com/repos/lucaslus/LinDo/releases/latest";

const PACKAGES = {
  android: /\.apk$/i,
  macArm64: /(?:arm64|aarch64)\.dmg$/i,
  macIntel: /(?:x64|x86_64|intel)\.dmg$/i,
  winSetup: /-setup\.exe$/i,
  winMsi: /\.msi$/i,
  linuxDeb: /\.deb$/i,
  linuxRpm: /\.rpm$/i,
  linuxAppImage: /\.appimage$/i,
  checksums: /^SHA256SUMS\.txt$/i,
};

type GitHubAsset = {
  name: string;
  size: number;
  browser_download_url: string;
};

type GitHubRelease = {
  tag_name: string;
  html_url: string;
  assets: GitHubAsset[];
};

type Package = keyof typeof PACKAGES;
export type ReleaseAsset = { url: string; bytes: number };
export type ReleaseDownloads = {
  tag?: string;
  url: string;
  assets: Partial<Record<Package, ReleaseAsset>>;
};

export function formatMiB(bytes: number) {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

const fallback: ReleaseDownloads = { url: SITE.releases, assets: {} };

export async function getReleaseDownloads(): Promise<ReleaseDownloads> {
  try {
    const res = await fetch(RELEASE_API, {
      headers: { Accept: "application/vnd.github+json" },
      next: { revalidate: 300 },
      signal: AbortSignal.timeout(3500),
    });
    if (!res.ok) return fallback;

    const release: GitHubRelease = await res.json();
    if (!Array.isArray(release.assets)) return fallback;

    const prefix = `${SITE.repo}/releases/`;
    const available = release.assets.filter((asset) => (
      typeof asset?.name === "string" && Number.isFinite(asset.size) && asset.size > 0 &&
      typeof asset.browser_download_url === "string" && asset.browser_download_url.startsWith(`${prefix}download/`)
    ));
    const assets: ReleaseDownloads["assets"] = {};
    for (const key of Object.keys(PACKAGES) as Package[]) {
      const asset = available.find(({ name }) => PACKAGES[key].test(name));
      if (asset) assets[key] = { url: asset.browser_download_url, bytes: asset.size };
    }

    return {
      tag: typeof release.tag_name === "string" ? release.tag_name : undefined,
      url: typeof release.html_url === "string" && release.html_url.startsWith(`${prefix}tag/`) ? release.html_url : SITE.releases,
      assets,
    };
  } catch {
    return fallback;
  }
}
