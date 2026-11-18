import type { Metadata } from "next";
import Link from "next/link";
import { CHANGELOG } from "@/lib/changelog";
import { SiteNav } from "@/components/home/shell";
import { SiteFooter } from "@/components/home/sections";

export const metadata: Metadata = { title: "更新日志" };

export default function ChangelogPage() {
  return (
    <div className="site">
      <SiteNav />
      <main className="mx-auto max-w-[760px] px-6 pt-16 pb-28 sm:pt-24">
        <Link href="/" className="t-meta hover:text-[var(--ink-1)]">
          LinDo
        </Link>
        <h1 className="t-h2 mt-3">更新日志</h1>
        <p className="t-body mt-3">安装包发布在 GitHub Releases，这里记录每次更新里用得到的变化。</p>

        <ol className="mt-14">
          {CHANGELOG.map((r) => (
            <li key={r.date + (r.version ?? "")} className="grid gap-x-10 gap-y-3 py-8 sm:grid-cols-[170px_1fr]" style={{ borderTop: "0.5px solid var(--hairline)" }}>
              <div>
                <p className="num text-[14px] font-medium" style={{ color: r.wip ? "var(--accent)" : "var(--ink-1)" }}>
                  {r.wip && <span className="mr-1.5 inline-block h-1.5 w-1.5 -translate-y-[2px] rounded-full align-middle" style={{ background: "var(--accent)" }} />}
                  {r.date}
                </p>
                {r.version?.split(" · ").map((v) => (
                  <p key={v} className="t-meta num mt-1 whitespace-nowrap">
                    {v}
                  </p>
                ))}
              </div>
              <ul className="space-y-2.5">
                {r.items.map((it) => (
                  <li key={it} className="relative pl-4 text-[15px] leading-relaxed" style={{ color: "var(--ink-1)" }}>
                    <span className="absolute top-[0.72em] left-0 h-[3px] w-[3px] rounded-full" style={{ background: "var(--ink-3)" }} />
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
