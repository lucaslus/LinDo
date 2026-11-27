import nextra from "nextra";

const withNextra = nextra({
  contentDirBasePath: "/docs",
  defaultShowCopyCode: true,
  search: { codeblocks: false },
});

/** @type {import('next').NextConfig} */
const nextConfig = {
  ...(process.env.NEXT_PUBLIC_BASE_PATH !== undefined && {
    basePath: process.env.NEXT_PUBLIC_BASE_PATH,
    output: "export",
    trailingSlash: true,
    images: { unoptimized: true },
  }),
  reactStrictMode: true,
  agentRules: false,
};

export default withNextra(nextConfig);
