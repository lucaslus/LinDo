# LinDo — Linux.do High-Performance Native Client (Android / macOS / Windows)

> **LinDo (Linux.do for Android & Desktop)** — 基于 **Kotlin Multiplatform (KMP) + Compose Multiplatform** 打造的性能第一、全端原生级 [Linux.do](https://linux.do) 第三方客户端。

---

## 核心特性（针对现有开源客户端痛点的根治重构）

1. **五合一全格式新徽章引擎 (`UniversalBadgeEngine`)**
   - **SVG 徽章直出与魔数嗅探**：解决现有客户端将 `.svg` 徽章或伪装成位图的 SVG 直接交给位图 `Image` 导致裂图或空白（`SizedBox.shrink()`）的 Bug。
   - **站点动态 SVG Sprite 热同步 (`/svg-sprite/linux.do/...`)**：自动提取并缓存 `<symbol id="...">` 矢量符号，社区新上线的自定义徽章无需客户端发版即可动态显示。
   - **零丢失等级勋章兜底**：对未知未来图标自动回退为金/银/铜首字勋章，绝不吞掉任何一枚用户徽章。
   - **顶层 `user_badges` 跨分页注入 (`BadgeInjector`)**：同时兼容 Map 与 List 响应结构，并在长帖翻页时保持用户徽章映射。

2. **双栈无闪烁/无重影动态头像 (`SmartAvatar`)**
   - **静态缩略图秒显打底 + 动图首帧原子接管**：话题卡片与帖子详情页楼层统一先展示几 KB 的 `avatar_template` 静态缩略图，当顶层 `animated_avatar`（GIF / WebP）首帧解码就绪后立即隐藏静态底图，彻底消除透明 GIF 透出底图造成的“双影”。
   - **完整对齐 Linux.do 站点身份特效 (`LinuxDoSiteRules`)**：
     - `@neo` 站长专属 `10%` 圆角方形头像 + `#00AEFF` 冰蓝脉冲光晕；
     - `g-merchant` 认证商家专属 `#F5BF03` 鎏金脉冲光晕；
     - `种子用户` 与 `Premium` 专属全息流光渐变头衔（`UserTitleTag`）；
     - 右下角 `FlairBadgeOverlay` 群组角标、生日 🎂 与纪念日 🍰 标识；
     - 支持全局一键切换「60fps 动效模式 / 省电静态模式」。

3. **原生 AST 帖子渲染引擎 (`DiscourseAstParser`)**
   - 在后台线程一次性将 Discourse `cooked` HTML 转换为结构化 `PostBlockNode` AST（标题、段落、代码块、嵌套引用卡 `aside.quote`、折叠块 `details`、列表、图片画廊），结合内容签名 LRU 缓存，实现长帖 60/120fps 丝滑滚动。

4. **防盾网络竞速与免掉登录认证架构 (`CloudflareEdgeRacer` & `CookieSentinel`)**
   - **Cloudflare 边缘节点并发竞速**：并发探测候选 Anycast 节点 `/cdn-cgi/trace` 延迟并锁定单一最快出口 IP，防止出口漂移触发 Cloudflare 频繁弹盾。
   - **User API Key (RSA-2048) 授权通道**：通过 `User-Api-Key` 请求头通信，摆脱对易失效 WebView Cookie 的依赖。
   - **RFC 6265bis 4元组 Cookie 哨兵**：强制对 `_t`、`cf_clearance`、`_forum_session` 执行单一 canonical 去重，根治 `host-only` 与 `.linux.do` 多变体共存导致的“假掉登录”。

---

## 快速构建与运行

```bash
# 使用 JDK 21 或 Android Studio JBR 运行测试（Desktop JVM + Android Unit Tests）
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew :composeApp:desktopTest :composeApp:testDebugUnitTest

# 启动 macOS / Windows 桌面端原生窗口
./gradlew :composeApp:run

# 构建 Android Debug APK（兼容 arm64-v8a 与 x86 模拟器）
./gradlew :composeApp:assembleDebug
```
