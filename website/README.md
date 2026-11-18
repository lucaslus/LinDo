# LinDo 官网

Next.js 16 + Nextra 4。首页（`app/page.tsx`）为自定义页面，文档位于 `/docs`（`content/*.mdx`）。

```sh
npm install
npm run dev      # http://localhost:3000
npm run build    # 构建并生成 Pagefind 搜索索引
```

## 结构

- `app/globals.css`：浅色/深色语义 palette 与七种主题色，与 App `buildLinDoPalette` / `BrandThemeOption` 对齐。官网与所有 App 渲染图读取同一套变量，因此切换明暗、主题色时一起变化。
- `components/mock/`：用 HTML 还原的 App 界面（手机首页、帖子阅读、桌面三栏），演示数据见 `lib/demo-data.ts`（虚构用户与话题）。
- `components/fold-section.tsx`：滚动驱动的折叠屏开合动画。
- `components/theme.tsx`：主题切换（View Transitions 圆形展开）与主题色。
- `public/screenshots/`：真机截图，按 `名称-light.png` / `名称-dark.png` 成对放置，随主题交叉淡入。
- `public/brand/`：零度娘资产，来自 `design/brand-v3`。

## 注意

`package.json` 中 `overrides.zod` 固定为 4.1.12：zod 4.6 修改了 `z.custom` 的可选性判断，会导致 Nextra 4.6.1 的文档 Layout 报错（`expected nonoptional … at children`）。升级 Nextra 后可尝试移除。
