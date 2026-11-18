import nextra from "nextra";

const withNextra = nextra({
  contentDirBasePath: "/docs",
  defaultShowCopyCode: true,
  search: { codeblocks: false },
});

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  agentRules: false,
};

export default withNextra(nextConfig);
