import type { MetadataRoute } from "next";
import { SITE } from "@/lib/site";

export default function sitemap(): MetadataRoute.Sitemap {
  const now = new Date();
  const routes = ["", "/changelog", "/docs", "/docs/install", "/docs/features", "/docs/security", "/docs/faq"];
  return routes.map((route) => ({
    url: `${SITE.url}${route}`,
    lastModified: now,
    changeFrequency: route === "" || route === "/changelog" ? "daily" : "weekly",
    priority: route === "" ? 1 : 0.8,
  }));
}
