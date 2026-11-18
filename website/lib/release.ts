import { readFileSync } from "node:fs";
import path from "node:path";
import { SITE } from "./site";

export type ReleaseDownloads = {
  hasDirectAssets: boolean;
  releaseUrl: string;
  versions: {
    android: string;
    desktop: string;
  };
  sizes: {
    androidMb: string;
    desktopMb: string;
  };
  android: {
    apkUrl: string;
    qrUrl: string;
  };
  mac: {
    arm64Url: string;
    x64Url: string;
  };
  win: {
    exeUrl: string;
    msiUrl: string;
  };
};

type GhAsset = {
  name?: string;
  size?: number;
  browser_download_url?: string;
};

type GhRelease = {
  tag_name?: string;
  html_url?: string;
  draft?: boolean;
  assets?: GhAsset[];
};

function readRepoVersions(): { android: string; desktop: string } {
  let android: string = SITE.versions.android;
  let desktop: string = SITE.versions.desktop;

  try {
    const gradlePath = path.resolve(process.cwd(), "../composeApp/build.gradle.kts");
    const gradleText = readFileSync(gradlePath, "utf8");
    const m = gradleText.match(/versionName\s*=\s*"([^"]+)"/);
    if (m?.[1]) android = m[1];
  } catch {}

  try {
    const pkgPath = path.resolve(process.cwd(), "../desktop-tauri/package.json");
    const pkg = JSON.parse(readFileSync(pkgPath, "utf8")) as { version?: string };
    if (pkg.version) desktop = pkg.version;
  } catch {}

  return { android, desktop };
}

function toMb(bytes?: number, fallback?: string): string {
  if (!bytes || bytes <= 0) return fallback ?? "10";
  return Math.max(1, Math.round(bytes / (1024 * 1024))).toString();
}

export async function getReleaseDownloads(): Promise<ReleaseDownloads> {
  const localVersions = readRepoVersions();
  const fallbackUrl = SITE.releases;

  const fallback: ReleaseDownloads = {
    hasDirectAssets: false,
    releaseUrl: fallbackUrl,
    versions: localVersions,
    sizes: { ...SITE.sizes },
    android: {
      apkUrl: fallbackUrl,
      qrUrl: `${SITE.url}/#download`,
    },
    mac: {
      arm64Url: fallbackUrl,
      x64Url: fallbackUrl,
    },
    win: {
      exeUrl: fallbackUrl,
      msiUrl: fallbackUrl,
    },
  };

  try {
    const res = await fetch("https://api.github.com/repos/lucaslus/LinDo/releases/latest", {
      headers: {
        Accept: "application/vnd.github+json",
        "User-Agent": "lindo-website",
      },
      next: { revalidate: 300 },
      signal: AbortSignal.timeout(3000),
    });

    if (!res.ok) return fallback;
    const data = (await res.json()) as GhRelease;
    if (!data || data.draft || !Array.isArray(data.assets) || data.assets.length === 0) {
      return fallback;
    }

    const validAssets = data.assets.filter(
      (a): a is Required<Pick<GhAsset, "name" | "browser_download_url">> & GhAsset =>
        typeof a.name === "string" &&
        typeof a.browser_download_url === "string" &&
        a.browser_download_url.startsWith("https://github.com/"),
    );

    const apk = validAssets.find((a) => /\.apk$/i.test(a.name));
    const macArm64 =
      validAssets.find((a) => /macos-(arm64|aarch64)\.dmg$/i.test(a.name)) ??
      validAssets.find((a) => /\.dmg$/i.test(a.name) && !/(x64|x86_64|intel)/i.test(a.name));
    const macX64 =
      validAssets.find((a) => /macos-(x64|x86_64|intel)\.dmg$/i.test(a.name)) ?? macArm64;
    const winExe =
      validAssets.find((a) => /-setup\.exe$/i.test(a.name)) ??
      validAssets.find((a) => /\.exe$/i.test(a.name));
    const winMsi = validAssets.find((a) => /\.msi$/i.test(a.name));

    const apkVer = apk?.name.match(/lindo-([0-9]+\.[0-9]+\.[0-9]+)/i)?.[1];
    const deskVer =
      (macArm64 ?? winExe ?? winMsi)?.name.match(/lindo-([0-9]+\.[0-9]+\.[0-9]+)/i)?.[1] ??
      data.tag_name?.replace(/^v/i, "");

    const releaseUrl = data.html_url || fallbackUrl;
    const hasDirectAssets = Boolean(apk || macArm64 || winExe || winMsi);

    return {
      hasDirectAssets,
      releaseUrl,
      versions: {
        android: apkVer || localVersions.android,
        desktop: deskVer || localVersions.desktop,
      },
      sizes: {
        androidMb: toMb(apk?.size, SITE.sizes.androidMb),
        desktopMb: toMb(macArm64?.size ?? macX64?.size, SITE.sizes.desktopMb),
      },
      android: {
        apkUrl: apk?.browser_download_url ?? releaseUrl,
        qrUrl:
          apk?.browser_download_url && apk.browser_download_url.length <= 120
            ? apk.browser_download_url
            : `${SITE.url}/#download`,
      },
      mac: {
        arm64Url: macArm64?.browser_download_url ?? releaseUrl,
        x64Url: macX64?.browser_download_url ?? releaseUrl,
      },
      win: {
        exeUrl: winExe?.browser_download_url ?? winMsi?.browser_download_url ?? releaseUrl,
        msiUrl: winMsi?.browser_download_url ?? winExe?.browser_download_url ?? releaseUrl,
      },
    };
  } catch {
    return fallback;
  }
}
