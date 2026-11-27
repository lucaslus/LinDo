# LinDo 官网

Next.js 与 Nextra 构建的官网。首页位于 `app/page.tsx`，用户文档位于 `content/`，变更记录位于 `lib/changelog.ts`。

```sh
npm install
npm run dev
npm run lint
npm run build
```

`npm run build` 会生成 Pagefind 文档搜索索引。首页从 GitHub Releases 读取正式版、包体大小和下载链接，按 5 分钟重新验证缓存。发布接口暂不可用时提供 GitHub Releases 入口。

## 界面截图

首页从左到右并排展示 macOS、Pixel 9、Pixel 9 Pro Fold 内屏和 Pixel Tablet 的截图。窄屏可横向滑动，点击任一设备可查看大图。四台设备都打开 Neo 的置顶帖 [《请不要把互联网上的戾气带来这里！》](https://linux.do/t/topic/482293)。Android 图片取自同一 1.1.6 本地构建的模拟器画面，保留原始分辨率，以无损 WebP 存放在 `public/shots/`。每台设备的深浅色图片在 `components/theme-screenshot.tsx` 中配对。

运行 `npm run build` 后，可用 `npx playwright install chromium` 和 `npm run test:e2e` 验证截图布局、主题切换和放大操作。

`package.json` 中 `overrides.zod` 固定为 `4.1.12`。升级 Nextra 后，可重新验证文档 Layout 和 Pagefind 索引，再评估是否移除固定版本。
