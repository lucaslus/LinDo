import type { Metadata } from "next";
import Link from "next/link";
import { CHANGELOG } from "@/lib/changelog";
import { SITE } from "@/lib/site";
import { SiteNav } from "@/components/home/shell";
import { SiteFooter } from "@/components/home/sections";

export const metadata: Metadata = { title: "更新日志" };

export default function ChangelogPage() {
  return (
    <div className="site changelog-hero-bg">
      <SiteNav />
      <main className="mx-auto max-w-[820px] px-5 pt-12 pb-24 sm:pt-16">
        <Link
          href="/"
          className="inline-flex items-center gap-1 text-[13px] font-medium transition-colors hover:text-[var(--ink-1)]"
          style={{ color: "var(--ink-3)" }}
        >
          ← 返回首页
        </Link>
        <h1 className="t-h2 mt-3" style={{ color: "var(--ink-1)" }}>
          更新日志
        </h1>
        <p className="t-body mt-3">
          查看近期功能与修复，安装包和完整变更记录见{" "}
          <a
            className="font-medium underline underline-offset-4 transition-colors"
            style={{ color: "var(--accent)" }}
            href={SITE.releasesPage}
          >
            GitHub Releases
          </a>
          。
        </p>

        <ol className="mt-10 flex flex-col gap-3.5">
          {CHANGELOG.map((r) => (
            <li
              key={r.date + (r.version ?? "")}
              className="matte-card grid gap-x-8 gap-y-3 rounded-[16px] p-6 sm:grid-cols-[164px_1fr]"
            >
              <div>
                <p className="num text-[14px] font-semibold" style={{ color: "var(--ink-1)" }}>
                  {r.date}
                </p>
                {r.version && (
                  <div className="mt-1.5 flex flex-wrap gap-x-2 gap-y-1">
                    {r.version.split(" · ").map((v) => (
                      <span
                        key={v}
                        className="num text-[12.5px] font-medium whitespace-nowrap"
                        style={{ color: "var(--accent)" }}
                      >
                        {v}
                      </span>
                    ))}
                  </div>
                )}
              </div>
              <ul className="space-y-2.5">
                {r.items.map((it) => (
                  <li key={it} className="relative pl-4 text-[14.5px] leading-relaxed" style={{ color: "var(--ink-1)" }}>
                    <span
                      className="absolute top-[0.72em] left-0 h-[4px] w-[4px] rounded-full"
                      style={{ background: "var(--accent-dot)" }}
                    />
                    {it}
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ol>
      </main>
      <SiteFooter />
    </div>
  );
}
