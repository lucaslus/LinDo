# LinDo 官网

Next.js 16 + Nextra 4 构建的 LinDo 官方网站与文档站。首页（`app/page.tsx`）与更新日志（`app/changelog/page.tsx`）采用自定义设计语言，文档位于 `/docs`（`content/*.mdx`）。

```sh
npm install
npm run dev      # http://localhost:3000
npm run lint     # TypeScript 类型检查
npm run build    # 生产构建并生成 Pagefind 搜索索引
```

## 目录结构

- `app/globals.css`：浅色/深色语义调色板（与客户端 `buildLinDoPalette` 同源）、响应式等比设备舞台（CSS Container Query 零 CLS）、折叠屏 3D 展开动画及 View Transitions 圆形切主题样式。
- `components/showcase.tsx`：四终端（手机 / 折叠屏 / 平板 / 电脑）自动轮播舞台，支持空闲分级纹理预解码、鼠标悬停暂停、键盘方向键与触控滑动切换。
- `components/home/`：顶栏（含零度娘表情联动主题开关与移动端菜单）、首屏智能下载 CTA、核心亮点与多平台下载卡片（含桌面端手机扫码安装二维码）。
- `lib/release.ts`：自动从仓库构建配置与 GitHub Releases 获取最新双端版本号、安装包体积及各架构直链（`arm64` / `x64` DMG、EXE / MSI、APK）。
- `lib/qr.ts`：零依赖轻量 ISO/IEC 18004 二维码矢量生成模块。
- `public/shots/`：各终端成对浅色/深色 `@2x` 真机截图（WebP），随主题同步交叉淡入。
- `public/brand/`：零度娘品牌标志与表情矢量图层。
- `public/og.png`：`1200×630` 社交与社区（LINUX DO / Discourse Onebox）链接分享预览图。

## 注意

`package.json` 中 `overrides.zod` 固定为 `4.1.12`：zod 4.6 修改了 `z.custom` 的可选性判断，会导致 Nextra 4.6.1 的文档 Layout 报错（`expected nonoptional … at children`）。升级 Nextra 后可尝试移除。
