# LinDo 全局体验与正确性审查

> 本文保留修复前的发现与证据。后续实现与验证状态见[全局修复记录](fixes-global-2026-11-06.md)。

### 复审 / follow-up review

范围：`main` 的 `0ee99bb`，重点覆盖 2026 年 11 月 6 日的 21 个提交，并追踪编辑、回复、草稿、阅读分页、徽章、认证、正文渲染和共享状态组件的实际调用。审查前工作区干净。本轮新增审查资料，没有修改业务代码、提交或推送；没有使用真实账号发帖、回复、投票、私信或上传附件。

**主要结论：无底色的信息流和分类标签方向已经比较明确，但内容编辑与部分新功能还存在真实错误，应先修内容安全和交互真实性，再继续打磨外观。** 慢网覆盖输入、键盘遮挡和大字号截断已在独立 Android 模拟器复现；其余发现分别注明测试或静态证据。

## 核心问题（按优先级排序）

### 1. 🟡 Major，编辑旧帖会覆盖刚输入的文字，也可能损失原始格式

定位：`composeApp/src/commonMain/kotlin/do_/linux/lindo/ui/LinDoApp.kt:4082`、`:4578`。

帖子没有 `raw` 时，编辑框先填入从 cooked HTML 删标签得到的文本，且立即允许输入和保存。后台获取原始 Markdown 后，只检查帖子 ID 就直接覆盖 `editPostRawText`。独立 fixture 将原文请求延迟 6 秒，先输入 `auditUserTyped`，响应后新文字消失，恢复成服务器原文；见[输入后](review-evidence/2026-11-06-global/edit-before-raw-response.png)与[原文返回后](review-evidence/2026-11-06-global/edit-after-raw-response.png)。若原文获取失败，直接保存临时文本会丢失原有链接、图片和 Markdown 标记。

保存成功还只更新 `cookedHtml`，没有更新 `raw`。当帖子原来带有 `raw` 时，再次编辑会读回旧内容，可能把上一轮修改覆盖掉。建议把编辑准备明确分为原文加载、可编辑、失败三种状态；未获取可信原文时提供重试；保存后同时合并新的 `raw` 和 cooked 内容。

### 2. 🟡 Major，草稿的关闭、恢复与版本管理没有形成完整闭环

定位：`LinDoApp.kt:9055`、`:9129`、`:9210`、`:9651`，`core/network/LinDoNetworkStack.kt:3116`，均在上述源码目录下。

编辑内容只在 composable 的 `remember` 中，2.2 秒后才自动保存；立即关闭、下滑关闭或进程结束会丢失尚未保存的文字。普通重新打开回复框也不会恢复刚才输入的内容。恢复云草稿时，工作台仍从 `draftSeq=0` 开始，没有接收草稿列表里的真实 sequence；已有草稿的 sequence 非零时，后续保存会冲突。回复发布后的删除又固定传 `0`，新话题传的是打开时的旧 sequence，均未使用自动保存后的最新版本。

另有两个状态问题：仅改标签不会触发保存，因为 effect 的 key 不含 `draftTags`；保存请求被下一次输入取消时，没有 `finally` 复位 `isSyncingDraft`，`draftSynced` 也不在新修改时清除，可能持续显示旧的“已存草稿”。

建议由同一个编辑会话保存正文、目标、脏状态、sequence 和保存结果；本地先落草稿，云端同步使用真实版本，关闭时处理未保存内容，发布与自动保存串行收尾。Discourse 上游会校验 sequence；删除冲突甚至可返回成功 JSON 而实际未清除，因此不能只检查 HTTP 200。[Draft 实现](https://github.com/discourse/discourse/blob/main/app/models/draft.rb)、[DraftsController](https://github.com/discourse/discourse/blob/main/app/controllers/drafts_controller.rb)。这是上游契约与本地代码的核对，没有用真实账号执行破坏性验证。

### 3. 🟡 Major，正文投票只改变本地选择，没有真正投票

定位：`composeApp/src/commonMain/kotlin/do_/linux/lindo/ui/PostRichContent.kt:244`。

投票显示“点击选项参与”和“已选择”，点击只修改 `selectedPollIdx`。没有调用仓库里的 `voteInPoll`，没有提交按钮，也没有原网页入口。退出页面后选择丢失，服务器不会收到投票。这是可交互占位实现被呈现为真实功能，不能以删除原来的回退提示视为补齐。

建议保留 poll name、option ID、已投状态、多选和关闭状态，接通真实提交与结果刷新；完成前展示清楚的“前往原帖投票”操作，避免假成功。

### 4. 🟡 Major，编辑器预览与发布效果不一致，代码内容还会被改写

定位：`LinDoApp.kt:10218`、`:10242`，`WorkbenchAstLivePreviewBox`。

本地“编译器”没有处理普通 Markdown 的粗体、链接、图片、行内代码、有序列表等，却显示“与发帖后展示效果一致”。临时测试中，`**bold**` 没有产生粗体节点；代码块 `<item>value</item>` 被当作 HTML 解析成 `value`。代码块替换后还会继续执行表情和逐行替换，进一步改变源码。

建议采用支持所需 Markdown 方言的解析器，并把代码块视为不可继续替换的字面内容；Discourse 扩展语法通过插件或服务端 cooked 预览补齐。尚未对齐的预览应明确说明范围。

### 5. 🟡 Major，剧透文字没有隐藏，会立即显示正文

定位：`composeApp/src/commonMain/kotlin/do_/linux/lindo/core/render/DiscourseAstRenderer.kt:102` 的节点分发，以及 `PostRichContent.kt`。

解析器处理 `<details>`，但没有为 `span.spoiler` 或 `div.spoilered` 建立隐藏节点。临时 fixture 的 `<span class="spoiler">secret fixture</span>` 直接进入普通 Paragraph；本地预览生成的 `.spoilered` 也会直接显示。这会破坏作者的阅读意图。

建议建立专门的剧透节点及点击揭示状态，确保初始视觉和无障碍语义都不直接暴露隐藏内容；用真实 cooked 结构补回归样本。

### 6. 🟡 Major，从中间楼层进入长帖后，分页会把前面的楼层追加到后面

定位：`LinDoApp.kt:3507`、`:3539`、`:3551`、`:4076`，`core/network/LinDoNetworkStack.kt:1094`。

通知、搜索可以请求 `/t/{id}/{postNumber}.json` 的中间窗口。后续却从完整 stream 中取所有未加载 ID 的最前 20 个，再追加到现有 posts 末尾，没有按 stream 顺序归并。假设初始窗口是 #90–#110，后续可能出现 #90–#110、#1–#20 的顺序。回复对象尚未在内存时，头像跳楼的回调只做 `indexOfFirst`，找不到就没有反应。

同一窗口假设还影响 `posts.first()`：Topic Map 和本地摘要会把返回窗口第一条当作主帖。建议区分头帖、当前窗口和完整 stream，按 stream 位置归并并保持滚动锚点；跳转未加载楼层时先加载对应窗口。

### 7. 🟡 Major，他人的徽章页面混入当前账号的徽章

定位：`LinDoApp.kt:1695`、`:8330`、`:8354`。

从他人名片打开徽章页时，`targetUsername` 是对方，但调用仍传入当前账号的 `fullUserProfile.badges` 和 title；页面把这些数据与对方返回的徽章合并，再统一标成 `isEarned=true`。结果是对方的“已获徽章”含有自己的徽章，首次加载和完成加载后都受影响。这是身份数据混用，不是徽章图标样式问题。

建议所有种子数据与缓存都携带所属 username，只有与目标相同才能合并；他人的 title 也从目标资料读取。空结果应能明确替换旧列表。

### 8. 🟡 Major，授权回调没有验证 nonce，也未消费待授权状态

定位：`composeApp/src/commonMain/kotlin/do_/linux/lindo/core/network/LinDoNetworkStack.kt:437`、`:497`。

生成授权链接时创建并保存了 `pendingNonce`，解密回调时却没有比较返回 nonce。临时测试用本次公开 RSA 公钥生成含错误 nonce 的加密 payload，`handleAuthRedirectUri` 仍返回 true 并接受测试 key；没有使用真实账号或真实密钥。成功后待授权私钥和 nonce 也没有被清除。

建议校验回调 scheme/host、本次 nonce 和待授权有效期，成功后消费该授权状态并清除持久化私钥。此处已证明状态校验缺口，没有宣称完成真实账号接管或线上利用。

### 9. 🟡 Major，编辑草稿会被当成新话题恢复

定位：`LinDoApp.kt:4540`、`:1816`，`core/network/LinDoNetworkStack.kt:2953`、`:3124`。

旧帖编辑自动保存到 `edit_post_{id}`，但 `saveCloudDraft` 只区分新话题和 reply，编辑也保存成 `action=reply`，`postId` 又传入楼层编号。读取草稿时没有保留 action 或真正的 post ID，topic ID 只从 `topic_` key 推导；`edit_post_...` 因而变成 topicId=0，点击后进入发布新话题。

建议为新话题、回复、编辑建立明确的草稿类型与恢复路由，分别保留 topic ID、post ID 和回复楼层编号，保存修改成功后清理对应编辑草稿。

### 10. 🟡 Major，认证数据仍在默认备份范围内

定位：`composeApp/src/androidMain/AndroidManifest.xml:8`，`MainActivity.kt:231`、`:919`。

`allowBackup=true`，没有备份排除规则；普通 SharedPreferences 保存 Cookie、API key 和待授权 RSA 私钥。Android Auto Backup 默认包含 SharedPreferences，所以这些认证数据会进入允许备份的数据范围。此项是现有风险仍未解决，不代表本轮观察到了实际泄漏。[Android Auto Backup 文档](https://developer.android.com/identity/data/autobackup)。

建议对认证偏好、WebView 会话和调试导入文件配置云备份及设备迁移排除规则；长期秘密使用 Keystore 支持的保护方案，临时授权状态及时销毁。

### 11. 🟡 Major，旧帖编辑弹层不适配键盘，保存操作被遮挡

定位：`LinDoApp.kt:4515`。

旧帖编辑是居中的自绘 Box，内部 Column 没有 IME inset、可滚动容器或高度约束。1080×2340、默认字号的独立模拟器中，聚焦后工具栏第二行和保存/取消区域落到键盘后面，见[键盘下的编辑弹层](review-evidence/2026-11-06-global/edit-after-raw-response.png)。新话题和回复已使用 ModalBottomSheet 与 IME padding，因此同一工作台在三个入口的操作体验不同。

建议共用编辑容器，使标题和提交操作固定可达，正文与扩展工具可滚动，处理系统返回键及未保存内容。个人资料和私信表单也应按同一套键盘场景验收。

### 12. 🟡 Major，部分纯矢量正文会静默消失

定位：`core/render/DiscourseAstRenderer.kt:248`。

未知 SVG 只要 `node.text()` 为空就被认定为装饰图标。临时 fixture 中带 `role="img"`、有 path 的 SVG 被解析成空列表；纯路径图表并不一定有文本。因此，去掉“不支持”的提示后，某些有意义的内容会一起消失。

建议仅对明确的站点 UI 图标执行忽略或图标映射；正文图形保留安全渲染或按楼层打开原帖的可达入口。该问题已在构造样本复现，未统计线上帖子的发生比例。

### 13. 🟢 Minor，编辑工具行、大字号与低对比度仍有明显缺口

定位：`LinDoApp.kt:272`、`:9696`、`:10074`、`:10922`。

回复框的普通字号 placeholder 与光标起点已经对齐。1.6 倍系统字号、有正文且键盘打开时，固定 88 dp 草稿位加编辑/预览、字数、撤销、重做和“中英空格”挤在一行，右侧操作被截断；旧帖窄弹层在普通字号下也能看到该问题。见[大字号回复框](review-evidence/2026-11-06-global/reply-font-1.6-keyboard.png)。

辅助文字 `#9499A3` 对白底约 2.86:1，深色 `#6B7280` 对 `#161920` 约 3.64:1；金色 `#F59E0B` 对白底约 2.15:1，不适合直接承担小字号操作文字。可按普通文字 4.5:1 的可读性目标调整文本色，保留品牌色用于装饰和强调。[W3C 对比度说明](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)。

建议把草稿状态移到独立辅助行，把低频排版操作收进更多菜单；保持图标小而触摸区域足够大。现有通用圆形按钮已改大，但工具行仍有直接挂 clickable 的 16 dp 图标和固定 32 dp 行；需要逐项核对实际命中区域及 TalkBack，不能从图标尺寸推断全部按钮的可点范围。

### 14. 🟡 Major，默认开发脚本已不能构建，Lint 的成功状态也不代表完整分析

定位：`scripts/android-dev.py:87`，`README.md`，`composeApp/build.gradle.kts:108`，`gradle/libs.versions.toml`。

当前机器 Android Studio JBR 是 Java 25.0.3，README 和开发脚本优先使用它；当前 Gradle 8.13 在配置阶段报 `25.0.3` 并退出。脚本即使已有有效 `JAVA_HOME` 也会优先选择该 JBR。本轮显式使用 JDK 21 后，Android/桌面测试及 Debug 构建完成。[Gradle Java 兼容矩阵](https://docs.gradle.org/current/userguide/compatibility.html)。

仍然关闭了所有 AarMetadata 任务；本次 Lint 命令虽然成功，日志含 `binary version ... 2.4.0, expected ... 2.2.0` 诊断，不能报告完整 Kotlin 静态检查通过。建议固定受支持的 JDK 与构建组合，优先尊重有效的 JAVA_HOME，恢复 AAR 检查并重新验证 Lint 和 release 包。

## 设计统一建议

建议把当前偏好写成少量可复用规则，避免继续逐页删除背景、逐页调整几个 dp。

| 元素 | 建议统一的表现 | 当前需要收口的位置 |
| --- | --- | --- |
| 分类和 Tag | 分类圆点加文字，Tag 纯文字，选中用字重、颜色或短线 | 标题区已接近目标；编辑工具、个人资料和私信仍有不同控件样式 |
| 操作按钮 | 默认透明图标或文字，用位置、字重、状态表达主次 | 旧帖编辑的底部按钮、工具 chip、私信整行按钮与主流程不同 |
| 内容容器 | 编辑画布、代码和引用按内容需要保留轻底，减少多层包框 | 不把内容容器和操作控件一起机械去底色 |
| 编辑界面 | 新话题、回复、修改旧帖共用容器和工作台布局 | 统一标题、关闭、提交、键盘避让、错误位置和草稿状态 |
| Placeholder | 与输入正文共用字体和内边距，短句说明输入目的 | 普通字号现有对齐可保留；补小屏、大字号、长标题与多行场景 |
| 输入规则 | 字数不足、版块限制等在输入附近明确提示 | 发布禁用时缺少原因，服务端失败又常被统一成“请登录/字数不足” |
| 零度娘状态 | 首次加载、刷新、分页、发送使用固定尺寸层级 | 先区分加载、空、失败、成功，再选对应形象；轻量状态不要挤压正文 |
| 错误恢复 | 保留当前输入，显示真实失败原因及重试动作 | 编辑保存失败没有可见错误，回复和发帖丢弃了具体错误信息 |
| 动态操作 | 显示处理中，失败后回滚并反馈 | 表态可反复点击并并发请求；当前失败回滚依赖旧快照，需补连续点击场景 |

额外建议：版块可选 Tag 获取失败时应明确失败或使用标记过来源的缓存。当前 `fetchCategoryAllowedTags` 返回固定五个 Tag，不应把这当成该版块的官方允许列表；切换版块后也应核对已选标签是否仍然允许。

`LinDoApp.kt` 已有 12,817 行。建议按阅读、创作、消息、个人资料和共享组件逐步拆分；先把编辑会话与通用 ActionButton、Tag、输入框、状态行统一，再迁移页面。这里没有用文件大小推断性能问题，目的是避免同一交互修一处、漏两处。

## 验证结果与证据

| 验证 | 本轮结果 |
| --- | --- |
| Android 与桌面现有单元测试 | 各 37 项，0 failure、0 error；部分任务复用已有构建输出 |
| Debug 构建 | 显式 JDK 21 成功；默认 JBR 25 失败 |
| Lint Debug | 任务完成、XML 无 Error/Fatal；日志存在 Kotlin metadata 不兼容诊断 |
| 临时针对性测试 | 6 项，5 项失败，分别复现错误 nonce 被接受、粗体预览丢失、代码标签丢失、剧透直接显示、SVG 静默丢失；另 1 项通过 |
| 原有 UI 脚本的选定 7 项 | 4 通过、3 失败；失败不能统一解释为 App 崩溃，详见下文 |
| 独立 fixture UI | 复现慢网覆盖用户输入、旧帖编辑键盘遮挡、1.6 倍字号工具行截断；查看普通回复与深色新话题布局 |

原 UI 脚本的三项失败：状态页仍断言旧文案；renderer 用例要求已登录首页，本轮独立模拟器没有真实登录；富文本用例找到了表格、配图、代码、投票，但期待的旧回退文案已移除，其中投票丢失真正交互见问题 3。没有把这三项重写成“全部通过”。

可复核资料位于 [review-evidence/2026-11-06-global](review-evidence/2026-11-06-global/)，包含临时测试源码、可在隔离工作区应用的 UI fixture patch、机器可读结果和截图。fixture 只替换宿主入口与网络返回，复用当前生产 composable；数据都是本地示例，网络请求均由 fake transport 接收。业务 APK 在本轮审查中没有包含这些 fixture 改动。

真实账号的发布、修改、投票、草稿冲突、图片上传和权限限制尚需在修复后完成授权的端到端复测。现有阅读上报队列持久化、真机高刷新率及长期内存验收仍沿用前一份报告的待办，不能根据短时模拟器测试断言无泄漏或稳定 120 fps。

## 汇总表

| 顺序 | 修复主题 | 优先原因 |
| --- | --- | --- |
| 1 | 旧帖原文、草稿闭环、编辑草稿路由、键盘可操作性 | 直接影响内容丢失和错误发布 |
| 2 | 投票、预览、剧透、SVG、长帖楼层归并 | 保证看到的内容和发生的操作真实一致 |
| 3 | nonce、认证备份、目标用户徽章隔离 | 收紧认证状态和身份数据边界 |
| 4 | 按钮、Tag、字号、对比度、placeholder 和状态组件 | 用统一组件完成全局设计收口 |
| 发布前 | JDK、AAR 检查、完整 Lint、release 与真机复验 | 恢复可信构建和验收基础 |

## 结论

**Verdict: Request Changes**

目前适合继续开发和体验。下一轮优先修“编辑不丢内容、草稿恢复正确、投票有真实结果”，随后统一创作容器和控件规则。本轮是本地审查，没有发布外部 review credential 或评审消息。

当前 Qfei-code-review version 1.10.1
