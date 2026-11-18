import type { ReactNode } from "react";
import { Footer, LastUpdated, Layout, Navbar } from "nextra-theme-docs";
import { getPageMap } from "nextra/page-map";
import { Search } from "nextra/components";
import { SITE } from "@/lib/site";
import "nextra-theme-docs/style.css";

const logo = (
  <span style={{ display: "inline-flex", alignItems: "center", gap: 8, fontWeight: 600, fontSize: 17 }}>
    {/* eslint-disable-next-line @next/next/no-img-element */}
    <img src="/brand/lindo-mark.svg" alt="" width={28} height={28} />
    <span>
      Lin<span style={{ color: "#F59E0B" }}>Do</span>
    </span>
    <span style={{ fontWeight: 400, opacity: 0.6, fontSize: 14 }}>文档</span>
  </span>
);

export default async function DocsLayout({ children }: { children: ReactNode }) {
  return (
    <Layout
      navbar={<Navbar logo={logo} logoLink="/" projectLink={SITE.repo} />}
      footer={<Footer>LinDo · LINUX DO 第三方客户端</Footer>}
      pageMap={await getPageMap("/docs")}
      docsRepositoryBase={`${SITE.repo}/tree/main/website`}
      editLink="在 GitHub 上编辑此页"
      feedback={{ content: "有问题？反馈给我们" }}
      toc={{ title: "本页目录", backToTop: "回到顶部" }}
      themeSwitch={{ dark: "深色", light: "浅色", system: "跟随系统" }}
      search={<Search placeholder="搜索文档…" emptyResult="没有找到相关内容" errorText="搜索索引加载失败" loading="加载中…" />}
      lastUpdated={<LastUpdated locale="zh-CN">最后更新于</LastUpdated>}
      sidebar={{ defaultMenuCollapseLevel: 1 }}
    >
      <>{children}</>
    </Layout>
  );
}
