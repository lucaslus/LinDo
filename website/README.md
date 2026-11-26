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

首页按网站主题切换同一界面的浅色和深色截图。仅在两张截图都存在时显示对应设备。素材位于 `public/shots/`。macOS 和 Android 的阅读示例使用同一篇置顶帖 [《请不要把互联网上的戾气带来这里！》](https://linux.do/t/topic/482293)。

`package.json` 中 `overrides.zod` 固定为 `4.1.12`。升级 Nextra 后，可重新验证文档 Layout 和 Pagefind 索引，再评估是否移除固定版本。
