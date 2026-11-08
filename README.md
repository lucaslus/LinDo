# LinDo

基于 Kotlin Multiplatform 与 Compose Multiplatform 的 LINUX DO 第三方客户端，包含 Android 和桌面 JVM 目标。当前主要在 Android 模拟器验证。

## 本地构建

需要 JDK 21、Android SDK 和已启动的 Android 模拟器。Gradle daemon 固定使用 JDK 21；macOS 可选择本机安装的 JDK：

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
./gradlew :composeApp:assembleDebug
./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest
```

Debug APK：`composeApp/build/outputs/apk/debug/composeApp-debug.apk`。桌面启动：`./gradlew :composeApp:run`。构建工具链存在兼容性债务，不能以 Debug 构建成功替代发布验证，见[审查报告](docs/review-2026-11-06.md)。

## Android 自动预览

```sh
python3 scripts/android-dev.py --device emulator-5554
```

脚本监听共享代码、Android 代码、资源与 Gradle 配置，自动增量构建、`adb install -r` 覆盖安装并重启。保留登录数据，内存中的页面状态会重置。编译失败时保留已安装版本。`--once` 仅部署一次，`--skip-initial` 从监听开始；同一工作区只允许一个实例。macOS 优先使用 Android Studio JBR，其他环境使用 `JAVA_HOME`。

这是自动重新部署。Android Studio 的 [Live Edit](https://developer.android.com/develop/ui/compose/tooling/iterative-development) 可用于支持范围内的 Compose 函数体即时预览；[Compose Hot Reload](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html) 面向桌面 JVM。

## 零度娘品牌与通用状态

最终资产与来源见 [design/brand-v3](design/brand-v3/README.md)。App 使用真实 SVG 路径导出的 VectorDrawable，透明底人物；加载与刷新使用 Compose 图层轻摇摆，无 Lottie 依赖。网络异常、空内容、完成、分页结束、发送与上传等状态共用品牌组件。

```sh
python3 scripts/generate-brand-vectors.py
```

WebView 使用全屏网页与系统返回键，网站自身的登录、验证及加载页面由网站呈现。

## 自动化 UI 回归

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

## 数据与验证边界

Connect 展示取得的官方周期指标；获取失败时明确标为账户累计数据，不推算保级资格。动态头像使用 CDN 原件，尊重动效开关及窗口焦点。正文支持常用富文本格式，投票/公式等互动块提供明确的原网页入口；实时消息、WebView 文件下载等与 Web 仍有差距。详细发现、修复、测试证据和后续方案见[审查报告](docs/review-2026-11-06.md)。

Cookie、令牌及调试导入文件不得加入仓库。

徽章内置矢量由 LinuxDo 提供的 Font Awesome Free SVG 转换而来。图标 © Fonticons, Inc.，采用 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 许可。 [Font Awesome Free 许可](https://fontawesome.com/license/free)
