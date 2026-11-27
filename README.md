<p align="center">
  <img src="website/public/icon-192.png" width="88" height="88" alt="LinDo 图标" />
</p>
<h1 align="center">LinDo</h1>
<p align="center">LINUX DO 第三方客户端，支持 Android、macOS、Windows 和 Linux。</p>
<p align="center">
  <a href="https://lucaslus.github.io/LinDo/">官网</a> ·
  <a href="#下载安装">下载安装</a> ·
  <a href="https://lucaslus.github.io/LinDo/docs/">使用文档</a> ·
  <a href="https://lucaslus.github.io/LinDo/docs/verification/">站点验证</a> ·
  <a href="https://linux.do/">LINUX DO 社区</a>
</p>
<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="website/public/shots/v1.1.8/desktop-dark.webp">
    <source media="(prefers-color-scheme: light)" srcset="website/public/shots/v1.1.8/desktop-light.webp">
    <img src="website/public/shots/v1.1.8/desktop-light.webp" width="31.4%" alt="macOS 窗口并排显示话题列表和 Neo 的置顶帖《请不要把互联网上的戾气带来这里！》">
  </picture>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="website/public/shots/v1.1.8/phone-dark.webp">
    <source media="(prefers-color-scheme: light)" srcset="website/public/shots/v1.1.8/phone-light.webp">
    <img src="website/public/shots/v1.1.8/phone-light.webp" width="9.25%" alt="Pixel 9 竖屏阅读置顶帖">
  </picture>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="website/public/shots/v1.1.8/fold-dark.webp">
    <source media="(prefers-color-scheme: light)" srcset="website/public/shots/v1.1.8/fold-light.webp">
    <img src="website/public/shots/v1.1.8/fold-light.webp" width="20%" alt="Pixel 9 Pro Fold 展开内屏，左侧是话题列表，右侧是置顶帖正文">
  </picture>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="website/public/shots/v1.1.8/tablet-dark.webp">
    <source media="(prefers-color-scheme: light)" srcset="website/public/shots/v1.1.8/tablet-light.webp">
    <img src="website/public/shots/v1.1.8/tablet-light.webp" width="33.25%" alt="Pixel Tablet 横屏分栏阅读置顶帖">
  </picture>
</p>

<p align="center"><a href="https://linux.do/t/topic/482293">截图中的话题 · Neo《请不要把互联网上的戾气带来这里！》</a></p>

## 下载安装

<!-- LINDO_DOWNLOAD_MATRIX_START -->
当前正式版 **[v1.1.12](https://github.com/lucaslus/LinDo/releases/tag/v1.1.12)**。校验清单 `SHA256SUMS.txt` 随版本发布。

| 平台 | 安装包 |
| :--- | :--- |
| **Android** | [APK · arm64](https://github.com/lucaslus/LinDo/releases/download/v1.1.12/lindo-1.1.12-build112.apk) |
| **Windows** | [安装程序 · x64](https://github.com/lucaslus/LinDo/releases/download/v1.1.12/Lindo-1.1.12-windows-x64-setup.exe)　·　[MSI · x64](https://github.com/lucaslus/LinDo/releases/download/v1.1.12/Lindo-1.1.12-windows-x64.msi) |
| **macOS** | [Apple Silicon DMG](https://github.com/lucaslus/LinDo/releases/download/v1.1.12/Lindo-1.1.12-macos-arm64.dmg)　·　[Intel DMG](https://github.com/lucaslus/LinDo/releases/download/v1.1.12/Lindo-1.1.12-macos-x64.dmg) |
| **Linux** | [AppImage · x86_64](https://github.com/lucaslus/LinDo/releases/download/v1.1.12/Lindo-1.1.12-linux-x86_64.AppImage)　·　[DEB · x86_64](https://github.com/lucaslus/LinDo/releases/download/v1.1.12/Lindo-1.1.12-linux-amd64.deb)　·　[RPM · x86_64](https://github.com/lucaslus/LinDo/releases/download/v1.1.12/Lindo-1.1.12-linux-x86_64.rpm) |
<!-- LINDO_DOWNLOAD_MATRIX_END -->

Arch Linux / Omarchy 可用仓库内的 [`PKGBUILD`](packaging/arch/PKGBUILD) 本地构建。[分平台安装说明](https://lucaslus.github.io/LinDo/docs/install/)

## 在 LinDo 里逛社区

| 功能 | 用法 |
| --- | --- |
| 阅读与回复 | 回到帖子可以接着上次的位置读。点引用能跳到原楼层，写回复时可以预览 Markdown。草稿保存在本机，登录后也可同步到论坛。 |
| 折叠屏与平板 | 折叠屏合上时用单栏，展开后左边选帖、右边阅读。拖动分隔线可以调宽度，也可以切回单栏。 |
| 跨设备登录 | 在已登录的设备上生成二维码或口令。手机可以扫码，电脑可以导入二维码截图，两端都能粘贴口令。 |
| 原生 Android | arm64 安装包约 17 MiB。帖子列表、正文和编辑器使用 Kotlin 与 Compose。零度娘的启动图标可以换表情，图标背景可以跟随系统深浅色。 |
| Connect | 查看社区返回的周期指标。只有账号累计数据时，面板会标明。 |

## 切网与站点验证

从 Wi-Fi 切到移动网络后，LinDo 会尝试重新加载帖子。遇到 Cloudflare 验证时，点「去验证」，在应用里按页面提示完成即可。如果显示验证已通过、帖子却没加载出来，可以单独重试连接。[站点验证指南](https://lucaslus.github.io/LinDo/docs/verification/)

## 隐私与安装包校验

登录凭证和阅读进度保存在设备上，社区请求直接发往 LINUX DO。LinDo 没有接入遥测 SDK。GitHub Actions 构建的正式安装包附有 `SHA256SUMS.txt`。[网络与隐私](https://lucaslus.github.io/LinDo/docs/security/) · [发布工作流](.github/workflows/release.yml)

## 参与开发

遇到问题时，请附上系统、LinDo 版本和复现步骤。[GitHub Issues](https://github.com/lucaslus/LinDo/issues)

| 目录 | 职责 |
| --- | --- |
| [`composeApp/`](composeApp) | Android 客户端、共享业务代码与测试 |
| [`desktop-tauri/`](desktop-tauri) | macOS、Windows、Linux 桌面客户端 |
| [`website/`](website) | 官网和文档 |

<details>
<summary>展开本地构建与调试说明</summary>

### 桌面端（Tauri 2）

使用 Node.js 22+、pnpm 10+ 和 Rust stable。

```sh
cd desktop-tauri
pnpm install
pnpm tauri dev      # 启动本地开发窗口
pnpm tauri build    # 构建对应平台的生产安装包
```

### Android 本地构建

使用 JDK 21 和 Android SDK。下例是 macOS 上 Android Studio 自带的 JDK 路径。

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest
```

Debug 安装包位于 `composeApp/build/outputs/apk/debug/composeApp-debug.apk`。

### Android 自动预览

```sh
python3 scripts/android-dev.py --device emulator-5554
```

脚本监听共享代码、Android 代码、资源与 Gradle 配置，自动增量构建、`adb install -r` 覆盖安装并重启。保留登录数据，内存中的页面状态会重置。编译失败时保留已安装版本。`--once` 仅部署一次，`--skip-initial` 从监听开始；同一工作区只允许一个实例。macOS 优先使用 Android Studio JBR，其他环境使用 `JAVA_HOME`。

Android Studio 的 [Live Edit](https://developer.android.com/develop/ui/compose/tooling/iterative-development) 可用于支持范围内的 Compose 函数体即时预览；[Compose Hot Reload](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html) 面向桌面 JVM。

### 零度娘品牌与通用状态

最终资产与来源见 [`design/brand-v3`](design/brand-v3/README.md)。App 使用真实 SVG 路径导出的 VectorDrawable，透明底人物；加载与刷新使用 Compose 图层轻摇摆，无 Lottie 依赖。网络异常、空内容、完成、分页结束、发送与上传等状态共用品牌组件。

```sh
python3 scripts/generate-brand-vectors.py
```

WebView 使用全屏网页与系统返回键，网站自身的登录、验证及加载页面由网站呈现。

### 自动化 UI 回归

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

</details>

## 友情链接

[LINUX DO 社区](https://linux.do/)

## 许可证

LinDo 的项目代码使用 [MIT License](LICENSE)。LINUX DO 的名称、标志和截图中的社区内容，其权利属于各自权利人。

徽章内置矢量由 LINUX DO 提供的 Font Awesome Free SVG 转换而来。图标 © Fonticons, Inc.，采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 许可。[Font Awesome Free 许可](https://fontawesome.com/license/free)
