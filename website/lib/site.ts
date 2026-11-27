export const SITE = {
  url: "https://lucaslus.github.io/LinDo",
  name: "LinDo",
  description: "LINUX DO 第三方客户端，支持 Android、macOS、Windows 和 Linux。手机上读帖，折叠屏和平板可并排显示列表和正文。",
  repo: "https://github.com/lucaslus/LinDo",
  releases: "https://github.com/lucaslus/LinDo/releases/latest",
  releasesPage: "https://github.com/lucaslus/LinDo/releases",
  issues: "https://github.com/lucaslus/LinDo/issues",
  forum: "https://linux.do/",
} as const;

export function assetPath(path: string) {
  return (process.env.NEXT_PUBLIC_BASE_PATH ?? "") + path;
}
