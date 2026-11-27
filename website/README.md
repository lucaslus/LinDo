# LinDo 官网

Next.js 与 Nextra 构建的官网。首页位于 `app/page.tsx`，用户文档位于 `content/`，变更记录位于 `lib/changelog.ts`。

```sh
npm install
npm run dev
npm run lint
npm run build
```

`npm run build` 会生成 Pagefind 文档搜索索引。首页从 GitHub Releases 读取正式版、包体大小和下载链接。接口暂不可用时，下载按钮指向发布页。

## 发布官网

[官网](https://lucaslus.github.io/LinDo/) 使用 GitHub Pages。`main` 上的网站改动和成功的客户端发布都会触发 [Publish website](../.github/workflows/website.yml)，重新生成下载链接和文档搜索索引。

本地运行 `NEXT_PUBLIC_BASE_PATH=/LinDo npm run build:pages` 可生成相同目录结构的静态文件，产物在 `out/`。

## 界面截图

首页从左到右并排展示 macOS、Pixel 9、Pixel 9 Pro Fold 内屏和 Pixel Tablet 的截图。窄屏可横向滑动，点击任一设备可查看大图。四台设备都打开 Neo 的置顶帖 [《请不要把互联网上的戾气带来这里！》](https://linux.do/t/topic/482293)。这组截图取自 v1.1.8 的 macOS 客户端和三台安装了 Android build 108 的模拟器。各设备在应用内切换深浅色后分别截图。原始分辨率的无损 WebP 存放在 `public/shots/v1.1.8/`，官网和仓库首页共用这八张图。

运行 `npm run build` 后，可用 `npx playwright install chromium` 和 `npm run test:e2e` 验证截图布局、主题切换和放大操作。

`package.json` 中 `overrides.zod` 固定为 `4.1.12`。升级 Nextra 后，可重新验证文档 Layout 和 Pagefind 索引，再评估是否移除固定版本。
