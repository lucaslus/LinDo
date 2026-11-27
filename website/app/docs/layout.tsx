import type { ReactNode } from "react";
import Link from "next/link";
import { Footer, LastUpdated, Layout, Navbar } from "nextra-theme-docs";
import { getPageMap } from "nextra/page-map";
import { Search } from "nextra/components";
import { assetPath, SITE } from "@/lib/site";
import "nextra-theme-docs/style.css";

const logo = (
  <span style={{ display: "inline-flex", alignItems: "center", gap: 8, fontWeight: 600, fontSize: 16.5, letterSpacing: "-0.02em" }}>
    {/* eslint-disable-next-line @next/next/no-img-element */}
    <img src={assetPath("/brand/lindo-mark.svg")} alt="" width={26} height={26} />
    <span>
      Lin<span style={{ color: "var(--logo-do, #e07b08)" }}>Do</span>
    </span>
    <span style={{ fontWeight: 450, color: "var(--ink-3)", fontSize: 13.5 }}>文档</span>
  </span>
);

export default async function DocsLayout({ children }: { children: ReactNode }) {
  return (
    <Layout
      navbar={
        <Navbar logo={logo} logoLink="/" projectLink={SITE.repo}>
          <Link
            href="/#download"
            style={{
              fontSize: 13.5,
              fontWeight: 560,
              padding: "6px 14px",
              borderRadius: 999,
              background: "var(--cta-bg, #151922)",
              color: "var(--cta-fg, #f8fafc)",
              textDecoration: "none",
              whiteSpace: "nowrap",
              boxShadow: "0 1px 0 rgba(255,255,255,0.2) inset, 0 6px 16px -8px rgba(15,23,42,0.35)",
            }}
          >
            下载客户端
          </Link>
        </Navbar>
      }
      footer={<Footer><span>友情链接 · <a className="underline underline-offset-4" href={SITE.forum}>LINUX DO 社区</a></span></Footer>}
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
