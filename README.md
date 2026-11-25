# LinDo

面向 [LINUX DO](https://linux.do) 社区打造的第三方开源跨平台客户端，提供 **Android 原生版**（Kotlin + Compose Multiplatform）与 **macOS / Windows / Linux 原生桌面版**（Tauri 2 + Rust + React）。

- **官方网站与文档**：[https://lindo.app](https://lindo.app)（文档见 `/docs`，安全说明见 `/docs/security`）
- **安装包下载**：[GitHub Releases](https://github.com/lucaslus/LinDo/releases/latest)（支持 Android `.apk`、macOS Apple Silicon / Intel `.dmg`、Windows `-setup.exe` / `.msi`、Linux `AppImage` / `.deb` / `.rpm`）

---

## 📦 下载安装

<!-- LINDO_DOWNLOAD_MATRIX_START -->
[![Latest Release](https://img.shields.io/github/v/release/lucaslus/LinDo?logo=github&label=latest&color=F59E0B)](https://github.com/lucaslus/LinDo/releases/latest) [![Total Downloads](https://img.shields.io/github/downloads/lucaslus/LinDo/total?logo=github&label=downloads&color=2ea043)](https://github.com/lucaslus/LinDo/releases)

当前最新正式版：**`v1.1.4`**（点击对应系统徽章即可直接下载安装包，完整校验文件见 [GitHub Releases](https://github.com/lucaslus/LinDo/releases/latest)）：

| OS | Download |
| :--- | :--- |
| **Android** | [![APK-ARMv8](https://img.shields.io/badge/APK-ARMv8-2ea043?logo=android&logoColor=white)](https://github.com/lucaslus/LinDo/releases/download/v1.1.4/lindo-1.1.4-build104.apk) |
| **Windows** | [![Setup-x64](https://img.shields.io/badge/Setup-x64-0078D4)](https://github.com/lucaslus/LinDo/releases/download/v1.1.4/Lindo-1.1.4-windows-x64-setup.exe)<br/>[![MSI-x64](https://img.shields.io/badge/MSI-x64-4B9CD3)](https://github.com/lucaslus/LinDo/releases/download/v1.1.4/Lindo-1.1.4-windows-x64.msi) |
| **macOS** | [![DMG-Apple Silicon](https://img.shields.io/badge/DMG-Apple_Silicon-181717?logo=apple&logoColor=white)](https://github.com/lucaslus/LinDo/releases/download/v1.1.4/Lindo-1.1.4-macos-arm64.dmg)<br/>[![DMG-Intel X64](https://img.shields.io/badge/DMG-Intel_X64-0071C5?logo=apple&logoColor=white)](https://github.com/lucaslus/LinDo/releases/download/v1.1.4/Lindo-1.1.4-macos-x64.dmg) |
| **Linux** | [![AppImage-x64](https://img.shields.io/badge/AppImage-x64-D94625?logo=linux&logoColor=white)](https://github.com/lucaslus/LinDo/releases/latest)<br/>[![DebPackage-x64](https://img.shields.io/badge/DebPackage-x64-E95420?logo=debian&logoColor=white)](https://github.com/lucaslus/LinDo/releases/latest)<br/>[![RpmPackage-x64](https://img.shields.io/badge/RpmPackage-x64-D97706?logo=redhat&logoColor=white)](https://github.com/lucaslus/LinDo/releases/latest) |
<!-- LINDO_DOWNLOAD_MATRIX_END -->

> Arch Linux / Omarchy 用户也可直接使用仓库内的 [`packaging/arch/PKGBUILD`](packaging/arch/PKGBUILD) 本地打包安装。

---

## 🛡️ 安全、隐私与代码审计声明

<p align="left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="website/public/brand/security-seal-dark.svg" />
    <img src="website/public/brand/security-seal.svg" width="84" height="84" alt="LinDo 零隐私收集与开源代码安全审计徽章" />
  </picture>
</p>

作为第三方社区客户端，我们将**完全安全、零隐私收集、100% 开源可审计**视为最基本的底线，欢迎任何社区成员随时逐行检视源码：

1. **零隐私收集 · 零自建后端 · 零遥测埋点**
   - 项目**不设任何自建中转服务器**，**未接入任何第三方统计、埋点、广告或崩溃上报 SDK**（无 Sentry、Firebase、Google Analytics、友盟等）。
   - 账号登录凭证（Cookie / 本机生成的 Discourse `User-Api-Key`）、多账号会话、未发草稿、浏览进度与本地诊断日志 **100% 仅存储在用户本机系统沙盒目录**，绝不向任何外部服务上传。
2. **严格的出站通信边界**
   - 客户端网络请求仅直连 `linux.do` 官方域名及官方静态/头像 CDN（`cdn.ldstatic.com`、`avatars.discourse-cdn.com`）；
   - 版本更新检查仅请求公开接口 `api.github.com/repos/lucaslus/LinDo/releases/latest`；
   - Android 端仅在系统 DNS 解析失败或被污染时，通过标准 HTTPS DoH（`223.5.5.5` / `1.12.12.12`）解析官方域名 IP。
3. **专项代码安全审计与客户端防护**
   - **防钓鱼外链分级拦截**：打开帖子外链前进行域名二次确认，自动识别并拦截 **IDN 同形异义词（`xn--`）** 与 **文本伪装钓鱼链接**（如文字显示可信网址但实际指向陌生域名）。
   - **受控 WebView 白名单隔离**：应用内验证与登录 WebView 严格校验导航域名，拒绝越权跳转指令；本地诊断日志自动对敏感令牌与 Cookie 脱敏。
4. **公开透明的构建流水线与哈希校验**
   - 所有正式安装包均由 GitHub Actions 官方托管 Runner 通过 [`.github/workflows/release.yml`](.github/workflows/release.yml) 从公开源码自动编译打包，并随附 `SHA256SUMS.txt` 校验清单，任何人均可核对安装包 SHA-256 指纹或直接在本地从源码构建。

---

## 🤝 完全开源共建与长期路线图

LinDo **100% 开源**，非常欢迎各位佬友参与共建：

- **欢迎代码安全审计与优化建议**：无论你是想对网络/存储链路进行独立代码安全审计，还是遇到了体验痛点、有性能调优想法或新功能点子，都非常欢迎直接提交 [Issues](https://github.com/lucaslus/LinDo/issues) 或发起 Pull Request。
- **长期持续更新 + 纯本地特色周边功能**：项目将持续跟进 LINUX DO 社区体验做长期维护迭代。在此基础上，我们还会在**严格坚持「纯本地运行、零数据上传」**的前提下，陆续加入更多好玩实用的**特色本地周边功能**（例如桌面端零度娘本地陪伴挂件与状态提醒、本地离线帖子收藏归档与全文检索、本地阅读习惯看板与快捷效率工具等）。

---

## 💻 平台架构概览

| 目录 | 平台与职责 | 技术栈 |
| --- | --- | --- |
| [`composeApp/`](composeApp) | Android 原生客户端（及共享业务/测试集） | Kotlin Multiplatform + Compose Multiplatform |
| [`desktop-tauri/`](desktop-tauri) | macOS（Apple Silicon / Intel）、Windows 与 Linux 桌面客户端 | Tauri 2 + Rust + React + TypeScript |
| [`website/`](website) | 官方网站与在线文档站 | Next.js 16 + Nextra 4 + Tailwind CSS v4 |

---

## 🛠️ 开发者构建与调试指南

### 1. 桌面端（Tauri 2：macOS / Windows / Linux）

需要 Node.js 22+、pnpm 10+ 与 Rust stable 工具链：

```sh
cd desktop-tauri
pnpm install
pnpm tauri dev      # 启动本地开发窗口
pnpm tauri build    # 构建对应平台的生产安装包
```

### 2. Android 客户端本地构建

需要 JDK 21、Android SDK 和已启动的 Android 模拟器或真机。Gradle daemon 固定使用 JDK 21；macOS 可选择本机安装的 JDK：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest
```

Debug APK：`composeApp/build/outputs/apk/debug/composeApp-debug.apk`。历史审查记录见 [`docs/review-2026-11-06.md`](docs/review-2026-11-06.md)。

### 3. Android 自动预览

```sh
python3 scripts/android-dev.py --device emulator-5554
```

脚本监听共享代码、Android 代码、资源与 Gradle 配置，自动增量构建、`adb install -r` 覆盖安装并重启。保留登录数据，内存中的页面状态会重置。编译失败时保留已安装版本。`--once` 仅部署一次，`--skip-initial` 从监听开始；同一工作区只允许一个实例。macOS 优先使用 Android Studio JBR，其他环境使用 `JAVA_HOME`。

这是自动重新部署。Android Studio 的 [Live Edit](https://developer.android.com/develop/ui/compose/tooling/iterative-development) 可用于支持范围内的 Compose 函数体即时预览；[Compose Hot Reload](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html) 面向桌面 JVM。

### 4. 零度娘品牌与通用状态

最终资产与来源见 [`design/brand-v3`](design/brand-v3/README.md)。App 使用真实 SVG 路径导出的 VectorDrawable，透明底人物；加载与刷新使用 Compose 图层轻摇摆，无 Lottie 依赖。网络异常、空内容、完成、分页结束、发送与上传等状态共用品牌组件。

```sh
python3 scripts/generate-brand-vectors.py
```

WebView 使用全屏网页与系统返回键，网站自身的登录、验证及加载页面由网站呈现。

### 5. 自动化 UI 回归

已安装 Debug APK 后运行，无额外 Python 依赖：

```sh
python3 scripts/android-ui-smoke.py --device emulator-5554
```

覆盖欢迎页明暗主题、1.3/1.6 大字体、六种状态、关闭动效、全屏 WebView 与返回、重复释放、断网恢复、列表滚动、网页渲染进程恢复、系统图标加载、富文本结构/链接及 8 个入口的网络失败展示，共 22 项。测试会短暂调整系统字体和网络并在结束时恢复，不清除登录数据，不发送帖子、点赞或私信。结果在 `build/ui-smoke/results.json`，同目录保存截图。

Debug 专属预览（不退出当前账号）：

```sh
adb shell am start -n do_.linux.lindo/.MainActivity --es lindo.preview welcome
adb shell am start -n do_.linux.lindo/.MainActivity --ez lindo.brand_preview true
```

返回正常 App：`adb shell am start -S -n do_.linux.lindo/.MainActivity`。预览 Intent 仅在 debuggable 应用生效。

---

## 📋 数据与验证边界

Connect 展示取得的官方周期指标；获取失败时明确标为账户累计数据，不推算保级资格。动态头像使用 CDN 原件，尊重动效开关及窗口焦点。正文支持常用富文本格式，投票/公式等互动块提供明确的原网页入口。详细发现、修复与测试证据见 [`docs/review-2026-11-06.md`](docs/review-2026-11-06.md)。

Cookie、令牌及调试导入文件不得加入仓库。

徽章内置矢量由 LinuxDo 提供的 Font Awesome Free SVG 转换而来。图标 © Fonticons, Inc.，采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 许可。[Font Awesome Free 许可](https://fontawesome.com/license/free)
