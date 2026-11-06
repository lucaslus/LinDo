# LinDo Web功能补齐计划（2026-11-06）

> 对照版本：`af0db5a0ca4d67bfcbc4bbdae16601b9c4e4305b`；输入：[完整逐项审计](audit-2026-11-06.md)。

本轮分类为已实现151、部分实现835、缺失326，共1,312项。目标是所有**当前Web可用且账号适用**的能力均可在安卓闭环完成；可先使用经过验证的网页登录流程，不要求第一轮全部原生重写。

## 执行顺序

1. **M0 · 防丢数据与规则一致**：启动T00范围判定、T20结果基础；优先T08无损编辑/草稿、T01字数/抽奖确认/删帖权限；同步修复表态、书签、通知已读和资料保存的假成功。
2. **M1 · 网页补覆盖与核心路径**：T02可验收Web宿主、T03目录和链接，随后T04列表搜索、T05定位已读、T10书签。网页仍未闭环前，不得把已有链接批量标为完成。
3. **M2 · 主站操作闭环**：T06正文媒体、T07编辑器、T09投票/解答/Boost/举报、T11通知推送、T12私信聊天、T13个人与积分、T14设置。
4. **M3 · 插件、独立站与特殊身份**：在适用性明确后完成T15群组看板、T16活动AI应用、T17生态、T18结算和T19管理端。T02完成后即可提前逐项验收其中的网页流程，无须等原生主站全部重写。
5. **M4 · 按ID收口**：T21贯穿每阶段；最终逐项检查服务端状态、权限变化、跨端、异常及系统行为。管理员/未开放/停运候选单独说明，不能伪装已实现。

本轮只产出审计和计划，未更改应用源码。S/M/L为相对复杂度，不是工期承诺；实际排期取决于账号资格、站点接口与网页宿主选型。

## 可先执行的修复任务

- [ ] T20.1：让写入操作返回统一领域结果，消除忽略Boolean/null而更新成功UI；覆盖书签、回应、已读、资料和草稿删除。
- [ ] T08.1：编辑先获取原始raw和版本，再提交；完整保存/恢复草稿字段、sequence与标签，退出前保存，冲突不覆盖。
- [ ] T01.1：从当前站点规则及账号能力决定长度和编辑/删除权限，补抽奖扣分确认；取消确认不得发请求。
- [ ] T02.1：选定WebView/Custom Tabs组合，补文件选择、下载和外部回调；各用一条头像/附件/app.zip/归档/OAuth路径验宿主。
- [ ] T03.1/T04.1：替换静态类别和标签，修复类别+标签+排序组合、hot/top命名与合法空/末页状态。
- [ ] T05.1：让TopicTarget同时带topicId/postNumber，通知/搜索/书签/外链均复用；缺楼层时按stream补取。

## 工作包、依赖与验收出口

<a id="t00"></a>

### T00 · Web 能力和账号资格判定

- 优先级：P0；复杂度：L；依赖：无。
- 主归属：4项（已实现0 / 部分0 / 缺失4）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：建立游客/自用账号/TL/群组/青春版/Premium/开发者/版主管理员矩阵；先判当前工作包适用性，再持续复核690个Web待核项和历史生态，保留来源/日期。
- [ ] 验收出口：每项已判定开放、受条件限制、停用或仍待查；未开放项标不适用并写复核条件，不能因为安卓缺少就直接开发；不用等待全部待核项完成才修复已知缺陷。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`ECO-019`, `ECO-020`, `ECO-021`, `ECO-022`

<a id="t01"></a>

### T01 · 登录、权限和站点规则

- 优先级：P0；复杂度：L；依赖：T00；T20。
- 主归属：71项（已实现1 / 部分48 / 缺失22）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：开放游客读；保存登录前路由/动作；解析 can_* 与禁言/封禁/身份权益；动态长度规则；抽奖资格、社区底板分、豁免与扣分二次确认；权限变化撤销缓存。
- [ ] 验收出口：同账号同对象与Web动作权限一致；扣分取消零请求；普通/例外字数与删帖期限按当前服务端规则；失效登录不丢未提交正文。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`AUTH-001`, `AUTH-002`, `AUTH-003`, `AUTH-004`, `AUTH-005`, `AUTH-006`, `AUTH-007`, `AUTH-008`, `AUTH-009`, `AUTH-010`, `AUTH-011`, `AUTH-012`, `AUTH-013`, `AUTH-014`, `AUTH-015`, `AUTH-016`, `AUTH-017`, `AUTH-018`, `AUTH-019`, `AUTH-020`, `AUTH-021`, `AUTH-022`, `AUTH-023`, `AUTH-024`, `AUTH-025`, `AUTH-026`, `AUTH-027`, `AUTH-028`, `AUTH-029`, `AUTH-030`, `COMPOSE-006`, `COMPOSE-007`, `SETSEC-003`, `SETSEC-004`, `SETSEC-015`, `SETSEC-016`, `SETSEC-017`, `SETSEC-018`, `SETSEC-019`, `RULE-001`, `RULE-002`, `RULE-003`, `RULE-004`, `RULE-005`, `RULE-006`, `RULE-007`, `RULE-008`, `RULE-009`, `RULE-010`, `RULE-011`, `RULE-012`, `RULE-013`, `RULE-014`, `RULE-015`, `RULE-016`, `RULE-018`, `RULE-019`, `RULE-020`, `RULE-021`, `RULE-025`, `RULE-026`, `RULE-027`, `RULE-029`, `STATE-001`, `STATE-002`, `STATE-004`, `STATE-019`, `STATE-020`, `STATE-021`, `STATE-022`, `STATE-023`

<a id="t02"></a>

### T02 · 可验收的网页容器

- 优先级：P1；复杂度：L；依赖：T20；T01的会话/授权基础。
- 主归属：18项（已实现0 / 部分18 / 缺失0）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：增加Web文件选择/下载、必要媒体全屏和权限、外部scheme与新窗口、OAuth回调/返回上下文；按系统实际能力选WebView或Custom Tabs；写操作回到原生后定向回读。
- [ ] 验收出口：逐条验证进入→登录→操作→服务端结果→返回原位置→原生刷新；头像/app.zip/AI附件/归档下载、OAuth取消、播放器全屏都有证据；仅放URL不关闭条目。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`APP-025`, `APP-028`, `APP-029`, `APP-030`, `APP-037`, `APPDEV-008`, `SETACC-013`, `SETACC-022`, `SETPRO-006`, `SETPRO-008`, `CONNECT-016`, `CONNECT-017`, `CONNECT-018`, `CONNECT-019`, `CONNECT-031`, `STATE-045`, `STATE-049`, `STATE-050`

<a id="t03"></a>

### T03 · 动态导航、目录和统一链接路由

- 优先级：P1；复杂度：M；依赖：T01；T02。
- 主归属：59项（已实现14 / 部分20 / 缺失25）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：动态加载全部可见类别、子类、标签和权限标识；补生日/周年/Channel等入口；解析话题/楼层/用户/类别/标签/活动/应用链接与系统App Links；站点外链分级和确认。
- [ ] 验收出口：目录数量/层级/权限与同账号Web一致；相同链接来自通知/正文/外部时目标和返回栈一致；账号切换后无旧权限入口。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`NAV-001`, `NAV-002`, `NAV-003`, `NAV-004`, `NAV-005`, `NAV-006`, `NAV-007`, `NAV-008`, `NAV-009`, `NAV-010`, `NAV-011`, `NAV-012`, `NAV-013`, `NAV-014`, `NAV-015`, `NAV-016`, `NAV-017`, `NAV-018`, `NAV-019`, `NAV-020`, `NAV-021`, `NAV-022`, `NAV-023`, `NAV-024`, `NAV-025`, `NAV-026`, `NAV-027`, `NAV-028`, `NAV-029`, `NAV-030`, `NAV-031`, `AUX-001`, `AUX-002`, `AUX-003`, `AUX-004`, `AUX-005`, `AUX-006`, `AUX-007`, `AUX-008`, `AUX-009`, `AUX-010`, `AUX-011`, `AUX-012`, `AUX-013`, `AUX-014`, `AUX-015`, `AUX-016`, `RULE-024`, `RULE-028`, `ECO-015`, `ECO-016`, `ECO-017`, `ECO-018`, `STATE-040`, `STATE-041`, `STATE-042`, `STATE-043`, `STATE-044`, `STATE-046`

<a id="t04"></a>

### T04 · 列表和搜索完整性

- 优先级：P1；复杂度：L；依赖：T03；T20。
- 主归属：94项（已实现17 / 部分57 / 缺失20）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：区分hot/top/posted/read；保留类别+标签+排序+周期；补状态徽标、参与者、真末页/空状态；搜索分类/用户、高级条件、分页和筛选URL恢复。
- [ ] 验收出口：每种筛选请求URL和Web结果一致；至少跨两页，无重复/漏项；合法空结果不显示网络错误；搜索精确楼层交T05。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`LIST-001`, `LIST-002`, `LIST-003`, `LIST-004`, `LIST-005`, `LIST-006`, `LIST-007`, `LIST-008`, `LIST-009`, `LIST-010`, `LIST-011`, `LIST-012`, `LIST-013`, `LIST-014`, `LIST-015`, `LIST-016`, `LIST-017`, `LIST-018`, `LIST-019`, `LIST-020`, `LIST-021`, `LIST-022`, `LIST-023`, `LIST-024`, `LIST-025`, `LIST-026`, `LIST-027`, `LIST-028`, `LIST-029`, `LIST-030`, `LIST-031`, `LIST-032`, `CAT-001`, `CAT-002`, `CAT-003`, `CAT-004`, `CAT-005`, `CAT-006`, `CAT-007`, `CAT-008`, `CAT-017`, `CAT-018`, `CAT-019`, `CAT-020`, `CAT-021`, `CAT-022`, `CAT-023`, `CAT-024`, `CAT-025`, `CAT-026`, `SEARCH-001`, `SEARCH-002`, `SEARCH-003`, `SEARCH-004`, `SEARCH-005`, `SEARCH-006`, `SEARCH-007`, `SEARCH-008`, `SEARCH-009`, `SEARCH-010`, `SEARCH-011`, `SEARCH-012`, `SEARCH-013`, `SEARCH-014`, `SEARCH-015`, `SEARCH-016`, `SEARCH-017`, `SEARCH-018`, `SEARCH-019`, `SEARCH-020`, `SEARCH-021`, `SEARCH-022`, `SEARCH-023`, `SEARCH-024`, `SEARCH-025`, `SEARCH-026`, `SEARCH-027`, `FILTER-001`, `FILTER-002`, `FILTER-003`, `FILTER-004`, `FILTER-005`, `FILTER-006`, `FILTER-007`, `FILTER-008`, `FILTER-009`, `FILTER-010`, `FILTER-011`, `FILTER-012`, `FILTER-013`, `FILTER-014`, `FILTER-015`, `FILTER-016`, `FILTER-017`

<a id="t05"></a>

### T05 · 阅读流、精确定位与已读同步

- 优先级：P1；复杂度：L；依赖：T03；T20。
- 主归属：48项（已实现9 / 部分18 / 缺失21）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：统一 topicId/postNumber 路由；按stream加载远端楼层、父回复和回复列表；恢复最后阅读位置/未读，持久化账号隔离计时队列；补统计/相关推荐/阅读模式；树形先确认Web启用。
- [ ] 验收出口：首次/中间/末楼、未加载父楼、外部楼层链接、只看楼主、返回列表均准确；前后台/离线/重启不虚增阅读，Web与安卓能恢复同一位置。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`READ-001`, `READ-002`, `READ-003`, `READ-004`, `READ-005`, `READ-006`, `READ-007`, `READ-008`, `READ-009`, `READ-010`, `READ-011`, `READ-012`, `READ-013`, `READ-014`, `READ-015`, `READ-016`, `READ-017`, `READ-018`, `READ-019`, `READ-020`, `READ-021`, `READ-022`, `READ-023`, `READ-024`, `READ-025`, `READ-026`, `READ-027`, `READ-028`, `READ-029`, `READ-030`, `READ-031`, `READ-032`, `READ-033`, `READ-034`, `READ-035`, `READ-036`, `READ-037`, `READ-038`, `READ-039`, `READ-040`, `READ-041`, `READ-042`, `READ-043`, `READ-044`, `READ-045`, `READ-046`, `STATE-024`, `STATE-025`

<a id="t06"></a>

### T06 · 正文与媒体等价展示

- 优先级：P2；复杂度：L；依赖：T02；T05。
- 主归属：102项（已实现32 / 部分55 / 缺失15）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：补Spoiler遮挡、标题层级、任务列表/脚注/锚点/Callout/Onebox/签名/代码高亮；数学和图表以真实cooked样本实现或逐项网页兜底；图片画廊保存分享和音视频控件。
- [ ] 验收出口：样本集覆盖每种格式及嵌套/宽内容；无文字丢失/静默退化；无法原生的组件提供准确上下文且网页操作、返回通过。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`FORMAT-001`, `FORMAT-002`, `FORMAT-003`, `FORMAT-004`, `FORMAT-005`, `FORMAT-006`, `FORMAT-007`, `FORMAT-008`, `FORMAT-009`, `FORMAT-010`, `FORMAT-011`, `FORMAT-012`, `FORMAT-013`, `FORMAT-014`, `FORMAT-015`, `FORMAT-016`, `FORMAT-017`, `FORMAT-018`, `FORMAT-019`, `FORMAT-020`, `FORMAT-021`, `FORMAT-022`, `FORMAT-023`, `FORMAT-024`, `FORMAT-025`, `FORMAT-026`, `FORMAT-027`, `FORMAT-028`, `FORMAT-029`, `FORMAT-030`, `FORMAT-031`, `FORMAT-032`, `FORMAT-033`, `FORMAT-034`, `FORMAT-035`, `FORMAT-036`, `FORMAT-037`, `FORMAT-038`, `FORMAT-039`, `FORMAT-040`, `FORMAT-041`, `FORMAT-042`, `FORMAT-043`, `FORMAT-044`, `FORMAT-045`, `FORMAT-046`, `FORMAT-047`, `FORMAT-048`, `FORMAT-049`, `FORMAT-050`, `FORMAT-051`, `FORMAT-052`, `FORMAT-053`, `FORMAT-054`, `FORMAT-055`, `FORMAT-056`, `FORMAT-057`, `FORMAT-058`, `FORMAT-059`, `FORMAT-060`, `FORMAT-061`, `FORMAT-062`, `FORMAT-063`, `FORMAT-064`, `FORMAT-065`, `FORMAT-066`, `FORMAT-067`, `FORMAT-068`, `FORMAT-069`, `FORMAT-070`, `MEDIA-001`, `MEDIA-002`, `MEDIA-003`, `MEDIA-004`, `MEDIA-005`, `MEDIA-006`, `MEDIA-007`, `MEDIA-008`, `MEDIA-009`, `MEDIA-010`, `MEDIA-011`, `MEDIA-012`, `MEDIA-013`, `MEDIA-014`, `MEDIA-015`, `MEDIA-016`, `MEDIA-017`, `MEDIA-018`, `MEDIA-019`, `MEDIA-020`, `MEDIA-021`, `MEDIA-022`, `MEDIA-023`, `MEDIA-024`, `MEDIA-025`, `MEDIA-026`, `MEDIA-027`, `MEDIA-028`, `MEDIA-029`, `MEDIA-030`, `MEDIA-031`, `MEDIA-032`

<a id="t07"></a>

### T07 · 发布编辑器与上传闭环

- 优先级：P2；复杂度：L；依赖：T01；T20；T06。
- 主归属：42项（已实现16 / 部分18 / 缺失8）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：使用可靠的cook预览；补富文本/全屏/最小化/语言/工具配置、完整表情和提及；类别模板/必填标签/相似主题；审核与限流反馈、上传限制/进度/取消和分享接入。
- [ ] 验收出口：同样源文Web/安卓预览与发布结果一致；超时结果不明先查重；失败保留内容；权限和扣分确认必须先满足T01。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`COMPOSE-001`, `COMPOSE-002`, `COMPOSE-003`, `COMPOSE-004`, `COMPOSE-005`, `COMPOSE-008`, `COMPOSE-009`, `COMPOSE-010`, `COMPOSE-011`, `COMPOSE-012`, `COMPOSE-013`, `COMPOSE-014`, `COMPOSE-015`, `COMPOSE-016`, `COMPOSE-017`, `COMPOSE-018`, `COMPOSE-019`, `COMPOSE-020`, `COMPOSE-021`, `COMPOSE-022`, `COMPOSE-023`, `COMPOSE-024`, `COMPOSE-025`, `COMPOSE-026`, `COMPOSE-027`, `COMPOSE-028`, `COMPOSE-029`, `COMPOSE-030`, `COMPOSE-031`, `COMPOSE-032`, `COMPOSE-033`, `COMPOSE-034`, `COMPOSE-035`, `COMPOSE-036`, `COMPOSE-037`, `COMPOSE-038`, `COMPOSE-039`, `COMPOSE-040`, `COMPOSE-041`, `COMPOSE-042`, `COMPOSE-043`, `STATE-017`

<a id="t08"></a>

### T08 · 草稿无损恢复与编辑删除

- 优先级：P0；复杂度：M；依赖：T20；T01能力接口。
- 主归属：22项（已实现1 / 部分16 / 缺失5）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：编辑先拉取raw与版本；草稿保存/获取完整字段及sequence，标签变化与退出可靠保存；冲突提示和重试；删除/恢复按服务端能力；所有写入失败不假成功。
- [ ] 验收出口：Web建草稿安卓完整恢复并反向保存；正文往返不损坏Markdown；409/过期/断网不覆盖或丢内容；无权限不出现可用删除操作。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`DRAFT-001`, `DRAFT-002`, `DRAFT-003`, `DRAFT-004`, `DRAFT-005`, `DRAFT-006`, `DRAFT-007`, `DRAFT-008`, `DRAFT-009`, `DRAFT-010`, `DRAFT-011`, `DRAFT-012`, `DRAFT-013`, `DRAFT-014`, `DRAFT-015`, `DRAFT-016`, `DRAFT-017`, `DRAFT-018`, `DRAFT-019`, `DRAFT-020`, `DRAFT-021`, `STATE-027`

<a id="t09"></a>

### T09 · 表态、举报、投票、解答与Boost

- 优先级：P2；复杂度：L；依赖：T01；T20；T05。
- 主归属：83项（已实现17 / 部分28 / 缺失38）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：先修表态失败回滚；接通未调用API及权限/配额/期限/撤回；补举报理由、回应者、主题投票、解答状态、Boost全链路与通知。
- [ ] 验收出口：创建/更换/取消/失败均按服务端回读；正文投票与话题投票分开验；扣费/配额/本人限制按Web当前规则；不把API定义当已完成。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`POST-001`, `POST-002`, `POST-003`, `POST-004`, `POST-005`, `POST-006`, `POST-007`, `POST-008`, `POST-009`, `POST-010`, `POST-011`, `POST-012`, `POST-013`, `POST-014`, `POST-015`, `POST-016`, `POST-017`, `POST-018`, `POST-019`, `POST-020`, `POST-021`, `POST-022`, `POST-023`, `POST-024`, `POST-025`, `POST-026`, `POST-027`, `POST-028`, `POST-029`, `POST-030`, `POST-031`, `POST-032`, `POLL-001`, `POLL-002`, `POLL-003`, `POLL-004`, `POLL-005`, `POLL-006`, `POLL-007`, `POLL-008`, `POLL-009`, `POLL-010`, `POLL-011`, `POLL-012`, `POLL-013`, `POLL-014`, `POLL-015`, `POLL-016`, `POLL-017`, `POLL-018`, `POLL-019`, `POLL-020`, `POLL-021`, `POLL-022`, `POLL-023`, `SOLVED-001`, `SOLVED-002`, `SOLVED-003`, `SOLVED-004`, `SOLVED-005`, `SOLVED-006`, `SOLVED-007`, `SOLVED-008`, `SOLVED-009`, `SOLVED-010`, `BOOST-001`, `BOOST-002`, `BOOST-003`, `BOOST-004`, `BOOST-005`, `BOOST-006`, `BOOST-007`, `BOOST-008`, `BOOST-009`, `BOOST-010`, `BOOST-011`, `BOOST-012`, `BOOST-013`, `BOOST-014`, `BOOST-015`, `BOOST-016`, `STATE-029`, `STATE-030`

<a id="t10"></a>

### T10 · 书签与提醒管理

- 优先级：P1；复杂度：M；依赖：T20；T05；T11的目标模型。
- 主归属：19项（已实现2 / 部分8 / 缺失9）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：正确区分创建/删除/更新，回读bookmarked/id；补楼层书签、名称和提醒CRUD/保留规则、分页搜索和失权处理。
- [ ] 验收出口：Web建楼层提醒→安卓编辑/取消→Web结果一致；失败不反转图标；提醒点击准确到楼层。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`BOOKMARK-001`, `BOOKMARK-002`, `BOOKMARK-003`, `BOOKMARK-004`, `BOOKMARK-005`, `BOOKMARK-006`, `BOOKMARK-007`, `BOOKMARK-008`, `BOOKMARK-009`, `BOOKMARK-010`, `BOOKMARK-011`, `BOOKMARK-012`, `BOOKMARK-013`, `BOOKMARK-014`, `BOOKMARK-015`, `BOOKMARK-016`, `BOOKMARK-017`, `BOOKMARK-018`, `STATE-028`

<a id="t11"></a>

### T11 · 通知、实时事件与系统推送

- 优先级：P2；复杂度：L；依赖：T03；T20；T05。
- 主归属：38项（已实现1 / 部分29 / 缺失8）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：完整通知类型/目标模型、分页、单条/全部已读回读、暂停与计数；按站点机制接入实时更新；系统通知权限、订阅/注销/拒绝状态和后台行为。
- [ ] 验收出口：回帖/提及/徽章/Boost/私信/解答/活动等分别取样；前台/后台/断线重连无漏重，点击精确目标，跨端已读和计数一致。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`NOTIFY-001`, `NOTIFY-002`, `NOTIFY-003`, `NOTIFY-004`, `NOTIFY-005`, `NOTIFY-006`, `NOTIFY-007`, `NOTIFY-008`, `NOTIFY-009`, `NOTIFY-010`, `NOTIFY-011`, `NOTIFY-012`, `NOTIFY-013`, `NOTIFY-014`, `NOTIFY-015`, `NOTIFY-016`, `NOTIFY-017`, `NOTIFY-018`, `NOTIFY-019`, `NOTIFY-020`, `NOTIFY-021`, `NOTIFY-022`, `NOTIFY-023`, `NOTIFY-024`, `NOTIFY-025`, `NOTIFY-026`, `NOTIFY-027`, `NOTIFY-028`, `NOTIFY-029`, `NOTIFY-030`, `SETNOT-017`, `SETNOT-018`, `SETNOT-019`, `STATE-026`, `STATE-033`, `STATE-034`, `STATE-035`, `STATE-037`

<a id="t12"></a>

### T12 · 长期私信与即时聊天

- 优先级：P2；复杂度：L；依赖：T02；T05；T09；T11。
- 主归属：72项（已实现9 / 部分55 / 缺失8）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：私信新未读/归档/参与者/群组/退出/联系权限和编辑器；聊天先完成Web闭环，按需原生化；验证线程、附件、成员、保留期与断线恢复。
- [ ] 验收出口：两套会话分别验收；多人、群组、附件、屏蔽联系与失权样本完整；聊天Web的返回和未读状态通过。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`PM-001`, `PM-002`, `PM-003`, `PM-004`, `PM-005`, `PM-006`, `PM-007`, `PM-008`, `PM-009`, `PM-010`, `PM-011`, `PM-012`, `PM-013`, `PM-014`, `PM-015`, `PM-016`, `PM-017`, `PM-018`, `PM-019`, `PM-020`, `PM-021`, `PM-022`, `PM-023`, `PM-024`, `CHAT-001`, `CHAT-002`, `CHAT-003`, `CHAT-004`, `CHAT-005`, `CHAT-006`, `CHAT-007`, `CHAT-008`, `CHAT-009`, `CHAT-010`, `CHAT-011`, `CHAT-012`, `CHAT-013`, `CHAT-014`, `CHAT-015`, `CHAT-016`, `CHAT-017`, `CHAT-018`, `CHAT-019`, `CHAT-020`, `CHAT-021`, `CHAT-022`, `CHAT-023`, `CHAT-024`, `CHAT-025`, `CHAT-026`, `CHAT-027`, `CHAT-028`, `CHAT-029`, `CHAT-030`, `CHAT-031`, `CHAT-032`, `CHAT-033`, `CHAT-034`, `CHAT-035`, `CHAT-036`, `CHAT-037`, `CHAT-038`, `CHAT-039`, `CHAT-040`, `CHAT-041`, `CHAT-042`, `CHAT-043`, `CHAT-044`, `CHAT-045`, `RULE-030`, `RULE-031`, `STATE-036`

<a id="t13"></a>

### T13 · 完整个人主页、关注与徽章积分

- 优先级：P2；复杂度：M；依赖：T03；T20；T09。
- 主归属：76项（已实现14 / 部分45 / 缺失17）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：补活动所有分类/分页、公开完整主页与资料字段、关注操作和设置、个人徽章/选择、社区积分口径和刷新；保留实时Connect与累计统计区别。
- [ ] 验收出口：本人/他人/受限资料对比一致；关注失败不假成功，头衔仅可选允许项；Credit余额、社区底板分及CDK分数不混用。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`PROFILE-001`, `PROFILE-002`, `PROFILE-003`, `PROFILE-004`, `PROFILE-005`, `PROFILE-006`, `PROFILE-007`, `PROFILE-008`, `PROFILE-009`, `PROFILE-010`, `PROFILE-011`, `PROFILE-012`, `PROFILE-013`, `PROFILE-014`, `PROFILE-015`, `PROFILE-016`, `PROFILE-017`, `PROFILE-018`, `PROFILE-019`, `PROFILE-020`, `PROFILE-021`, `PROFILE-022`, `PROFILE-023`, `PROFILE-024`, `PROFILE-025`, `PROFILE-026`, `PROFILE-027`, `PROFILE-028`, `PROFILE-029`, `PROFILE-030`, `PROFILE-031`, `PROFILE-032`, `PROFILE-033`, `PROFILE-034`, `PROFILE-035`, `PROFILE-036`, `PROFILE-037`, `PROFILE-038`, `PROFILE-039`, `PROFILE-040`, `PROFILE-041`, `FOLLOW-001`, `FOLLOW-002`, `FOLLOW-003`, `FOLLOW-004`, `FOLLOW-005`, `FOLLOW-006`, `FOLLOW-007`, `FOLLOW-008`, `FOLLOW-009`, `FOLLOW-010`, `FOLLOW-011`, `FOLLOW-012`, `FOLLOW-013`, `TRUST-001`, `TRUST-002`, `TRUST-003`, `TRUST-004`, `TRUST-005`, `TRUST-006`, `TRUST-007`, `TRUST-008`, `TRUST-009`, `TRUST-010`, `TRUST-011`, `TRUST-012`, `TRUST-013`, `TRUST-014`, `TRUST-015`, `TRUST-016`, `TRUST-017`, `TRUST-018`, `TRUST-019`, `TRUST-020`, `TRUST-021`, `STATE-031`

<a id="t14"></a>

### T14 · 十二组偏好设置

- 优先级：P2；复杂度：L；依赖：T01；T02；T20。
- 主归属：177项（已实现3 / 部分173 / 缺失1）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：按清单逐设置页建立直达路径或原生表单；加载当前值、保存结果与回读；系统字号/深浅/通知采用平台等价行为，保留跨端设置与本地设置边界。
- [ ] 验收出口：每个适用设置均做值变化→重启→Web回读；上传/下载/WebAuthn等依赖项先解决，不因设置页能打开就批量标完成。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`CAT-009`, `CAT-010`, `CAT-011`, `CAT-012`, `CAT-013`, `CAT-014`, `CAT-015`, `CAT-016`, `SETACC-001`, `SETACC-002`, `SETACC-003`, `SETACC-004`, `SETACC-005`, `SETACC-006`, `SETACC-007`, `SETACC-008`, `SETACC-009`, `SETACC-010`, `SETACC-011`, `SETACC-012`, `SETACC-014`, `SETACC-015`, `SETACC-016`, `SETACC-017`, `SETACC-018`, `SETACC-019`, `SETACC-020`, `SETACC-021`, `SETSEC-001`, `SETSEC-002`, `SETSEC-005`, `SETSEC-006`, `SETSEC-007`, `SETSEC-008`, `SETSEC-009`, `SETSEC-010`, `SETSEC-011`, `SETSEC-012`, `SETSEC-013`, `SETSEC-014`, `SETSEC-020`, `SETSEC-021`, `SETSEC-022`, `SETPRO-001`, `SETPRO-002`, `SETPRO-003`, `SETPRO-004`, `SETPRO-005`, `SETPRO-007`, `SETPRO-009`, `SETPRO-010`, `SETPRO-011`, `SETPRO-012`, `SETPRO-013`, `SETPRO-014`, `SETPRO-015`, `SETPRO-016`, `SETPRO-017`, `SETPRO-018`, `SETMAIL-001`, `SETMAIL-002`, `SETMAIL-003`, `SETMAIL-004`, `SETMAIL-005`, `SETMAIL-006`, `SETMAIL-007`, `SETMAIL-008`, `SETNOT-001`, `SETNOT-002`, `SETNOT-003`, `SETNOT-004`, `SETNOT-005`, `SETNOT-006`, `SETNOT-007`, `SETNOT-008`, `SETNOT-009`, `SETNOT-010`, `SETNOT-011`, `SETNOT-012`, `SETNOT-013`, `SETNOT-014`, `SETNOT-015`, `SETNOT-016`, `SETTRACK-001`, `SETTRACK-002`, `SETTRACK-003`, `SETTRACK-004`, `SETTRACK-005`, `SETTRACK-006`, `SETTRACK-007`, `SETTRACK-008`, `SETTRACK-009`, `SETTRACK-010`, `SETTRACK-011`, `SETTRACK-012`, `SETTRACK-013`, `SETTRACK-014`, `SETUSER-001`, `SETUSER-002`, `SETUSER-003`, `SETUSER-004`, `SETUSER-005`, `SETUSER-006`, `SETUSER-007`, `SETUSER-008`, `SETUSER-009`, `SETUSER-010`, `SETUSER-011`, `SETUSER-012`, `SETUSER-013`, `SETUSER-014`, `SETUSER-015`, `SETUSER-016`, `SETUI-001`, `SETUI-002`, `SETUI-003`, `SETUI-004`, `SETUI-005`, `SETUI-006`, `SETUI-007`, `SETUI-008`, `SETUI-009`, `SETUI-010`, `SETUI-011`, `SETUI-012`, `SETUI-013`, `SETUI-014`, `SETUI-015`, `SETUI-016`, `SETUI-017`, `SETUI-018`, `SETUI-019`, `SETUI-020`, `SETUI-021`, `SETUI-022`, `SETUI-023`, `SETUI-024`, `SETUI-025`, `SETUI-026`, `SETUI-027`, `SETUI-028`, `SETUI-029`, `SETUI-030`, `SETUI-031`, `SETUI-032`, `SETUI-033`, `SETNAV-001`, `SETNAV-002`, `SETNAV-003`, `SETCAL-001`, `SETCAL-002`, `SETCAL-003`, `SETCAL-004`, `SETCAL-005`, `SETCAL-006`, `SETCAL-007`, `SETCAL-008`, `SETSEARCH-001`, `SETSEARCH-002`, `SETSEARCH-003`, `SETSEARCH-004`, `SETSEARCH-005`, `SETSEARCH-006`, `SETSEARCH-007`, `SETSEARCH-008`, `SETSEARCH-009`, `SETSEARCH-010`, `SETSEARCH-011`, `SETCHAT-001`, `SETCHAT-002`, `SETCHAT-003`, `SETCHAT-004`, `SETCHAT-005`, `SETCHAT-006`, `SETCHAT-007`, `SETCHAT-008`, `STATE-032`

<a id="t15"></a>

### T15 · 群组虫洞、自我管理、指定与看板

- 优先级：P3；复杂度：L；依赖：T00；T02；T03；T01。
- 主归属：55项（已实现1 / 部分37 / 缺失17）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：按实际角色接入申请/加入/退出、动态目录、指定/慢速能力、看板卡片管理；优先验证Web闭环。
- [ ] 验收出口：普通/群组成员/获批自管理账号分别验；退出群组后目录与内容失效，管理动作按对象权限显示。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`GROUP-001`, `GROUP-002`, `GROUP-003`, `GROUP-004`, `GROUP-005`, `GROUP-006`, `GROUP-007`, `GROUP-008`, `GROUP-009`, `GROUP-010`, `GROUP-011`, `GROUP-012`, `GROUP-013`, `GROUP-014`, `GROUP-015`, `GROUP-016`, `GROUP-017`, `GROUP-018`, `GROUP-019`, `GROUP-020`, `GROUP-021`, `SELF-001`, `SELF-002`, `SELF-003`, `SELF-004`, `SELF-005`, `SELF-006`, `SELF-007`, `SELF-008`, `SELF-009`, `SELF-010`, `SELF-011`, `BOARD-001`, `BOARD-002`, `BOARD-003`, `BOARD-004`, `BOARD-005`, `BOARD-006`, `BOARD-007`, `BOARD-008`, `BOARD-009`, `BOARD-010`, `BOARD-011`, `BOARD-012`, `BOARD-013`, `BOARD-014`, `BOARD-015`, `BOARD-016`, `BOARD-017`, `BOARD-018`, `BOARD-019`, `BOARD-020`, `BOARD-021`, `BOARD-022`, `BOARD-023`

<a id="t16"></a>

### T16 · 活动、AI、微应用及开发者

- 优先级：P3；复杂度：L；依赖：T00；T02；T11。
- 主归属：107项（已实现2 / 部分99 / 缺失6）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：活动参与/提醒/时区/订阅，AI附件/历史/分享/总结翻译，微应用运行权限/存储/故障与开发上传/审核/回滚；先网页后按需原生。
- [ ] 验收出口：真实权限账号逐业务链验证；app.zip/iframe/声音/全屏/剪贴板/OAuth可控，过期/锁定/额度不足与退出清理正确。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`EVENT-001`, `EVENT-002`, `EVENT-003`, `EVENT-004`, `EVENT-005`, `EVENT-006`, `EVENT-007`, `EVENT-008`, `EVENT-009`, `EVENT-010`, `EVENT-011`, `EVENT-012`, `EVENT-013`, `EVENT-014`, `EVENT-015`, `EVENT-016`, `EVENT-017`, `EVENT-018`, `EVENT-019`, `EVENT-020`, `EVENT-021`, `EVENT-022`, `EVENT-023`, `EVENT-024`, `EVENT-025`, `EVENT-026`, `EVENT-027`, `AI-001`, `AI-002`, `AI-003`, `AI-004`, `AI-005`, `AI-006`, `AI-007`, `AI-008`, `AI-009`, `AI-010`, `AI-011`, `AI-012`, `AI-013`, `AI-014`, `AI-015`, `AI-016`, `AI-017`, `AI-018`, `AI-019`, `AI-020`, `AI-021`, `AI-022`, `APP-001`, `APP-002`, `APP-003`, `APP-004`, `APP-005`, `APP-006`, `APP-007`, `APP-008`, `APP-009`, `APP-010`, `APP-011`, `APP-012`, `APP-013`, `APP-014`, `APP-015`, `APP-016`, `APP-017`, `APP-018`, `APP-019`, `APP-020`, `APP-021`, `APP-022`, `APP-023`, `APP-024`, `APP-026`, `APP-027`, `APP-031`, `APP-032`, `APP-033`, `APP-034`, `APP-035`, `APP-036`, `APPDEV-001`, `APPDEV-002`, `APPDEV-003`, `APPDEV-004`, `APPDEV-005`, `APPDEV-006`, `APPDEV-007`, `APPDEV-009`, `APPDEV-010`, `APPDEV-011`, `APPDEV-012`, `APPDEV-013`, `APPDEV-014`, `APPDEV-015`, `APPDEV-016`, `APPDEV-017`, `APPDEV-018`, `APPDEV-019`, `APPDEV-020`, `APPDEV-021`, `APPDEV-022`, `APPDEV-023`, `APPDEV-024`, `APPDEV-025`, `APPDEV-026`, `APPDEV-027`

<a id="t17"></a>

### T17 · Connect和独立生态

- 优先级：P3；复杂度：L；依赖：T00；T02；T01。
- 主归属：99项（已实现10 / 部分89 / 缺失0）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：保留现有原生Connect进度；补邮箱/DeepLX/授权日志/邀请与应用接入；Credit/CDK/Lottery/Hub分别验证账户/资格/写入/返回，修正计分口径。
- [ ] 验收出口：每站完成登录/取消/授权失效/一个适用业务链，分值/余额按站点定义；历史停用功能有证据，不伪造资格。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`RULE-017`, `RULE-022`, `RULE-023`, `CONNECT-001`, `CONNECT-002`, `CONNECT-003`, `CONNECT-004`, `CONNECT-005`, `CONNECT-006`, `CONNECT-007`, `CONNECT-008`, `CONNECT-009`, `CONNECT-010`, `CONNECT-011`, `CONNECT-012`, `CONNECT-013`, `CONNECT-014`, `CONNECT-015`, `CONNECT-020`, `CONNECT-021`, `CONNECT-022`, `CONNECT-023`, `CONNECT-024`, `CONNECT-025`, `CONNECT-026`, `CONNECT-027`, `CONNECT-028`, `CONNECT-029`, `CONNECT-030`, `CREDIT-001`, `CREDIT-002`, `CREDIT-003`, `CREDIT-004`, `CREDIT-005`, `CREDIT-006`, `CREDIT-007`, `CREDIT-008`, `CREDIT-009`, `CREDIT-010`, `CREDIT-011`, `CREDIT-012`, `CREDIT-013`, `CREDIT-014`, `CREDIT-015`, `CREDIT-016`, `CREDIT-017`, `CREDIT-018`, `CREDIT-019`, `CREDIT-020`, `CREDIT-021`, `CREDIT-022`, `CREDIT-023`, `CREDIT-024`, `CREDIT-025`, `CREDIT-026`, `CREDIT-027`, `CREDIT-028`, `CREDIT-029`, `CREDIT-030`, `CDK-001`, `CDK-002`, `CDK-003`, `CDK-004`, `CDK-005`, `CDK-006`, `CDK-007`, `CDK-008`, `CDK-009`, `CDK-010`, `CDK-011`, `CDK-012`, `CDK-013`, `CDK-014`, `CDK-015`, `LOTTERY-001`, `LOTTERY-002`, `LOTTERY-003`, `LOTTERY-004`, `LOTTERY-005`, `LOTTERY-006`, `LOTTERY-007`, `LOTTERY-008`, `LOTTERY-009`, `LOTTERY-010`, `LOTTERY-011`, `ECO-001`, `ECO-002`, `ECO-003`, `ECO-004`, `ECO-005`, `ECO-006`, `ECO-007`, `ECO-008`, `ECO-009`, `ECO-010`, `ECO-011`, `ECO-012`, `ECO-013`, `ECO-014`

<a id="t18"></a>

### T18 · 订阅与结算

- 优先级：P3；复杂度：M；依赖：T00；T01；T02。
- 主归属：12项（已实现0 / 部分1 / 缺失11）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：确认当前Web真实入口与开放权益，再补正确结算路径、订单/续订状态和权益刷新；条件不开放则记录不适用。
- [ ] 验收出口：结算仅在适用账号和允许测试条件下验；取消/失败/生效延迟不误报；不能以账号设置入口代替订阅。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`BILLING-001`, `BILLING-002`, `BILLING-003`, `BILLING-004`, `BILLING-005`, `BILLING-006`, `BILLING-007`, `BILLING-008`, `BILLING-009`, `BILLING-010`, `BILLING-011`, `BILLING-012`

<a id="t19"></a>

### T19 · 版主和管理员条件能力

- 优先级：P3；复杂度：L；依赖：T00；T01；T02。
- 主归属：82项（已实现0 / 部分0 / 缺失82）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：先持相应角色盘点当前后台；按对象范围提供受保护网页入口或原生审核/管理流程，补权限撤回与审计反馈。
- [ ] 验收出口：未获得资格前保持待核，不算普通用户缺陷；授权范围内逐项取证，82个候选目标不能当完整后台字段清单。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`MOD-001`, `MOD-002`, `MOD-003`, `MOD-004`, `MOD-005`, `MOD-006`, `MOD-007`, `MOD-008`, `MOD-009`, `MOD-010`, `MOD-011`, `MOD-012`, `MOD-013`, `MOD-014`, `MOD-015`, `MOD-016`, `MOD-017`, `MOD-018`, `MOD-019`, `MOD-020`, `MOD-021`, `MOD-022`, `MOD-023`, `MOD-024`, `MOD-025`, `MOD-026`, `MOD-027`, `MOD-028`, `MOD-029`, `MOD-030`, `MOD-031`, `ADMIN-001`, `ADMIN-002`, `ADMIN-003`, `ADMIN-004`, `ADMIN-005`, `ADMIN-006`, `ADMIN-007`, `ADMIN-008`, `ADMIN-009`, `ADMIN-010`, `ADMIN-011`, `ADMIN-012`, `ADMIN-013`, `ADMIN-014`, `ADMIN-015`, `ADMIN-016`, `ADMIN-017`, `ADMIN-018`, `ADMIN-019`, `ADMIN-020`, `ADMIN-021`, `ADMIN-022`, `ADMIN-023`, `ADMIN-024`, `ADMIN-025`, `ADMIN-026`, `ADMIN-027`, `ADMIN-028`, `ADMIN-029`, `ADMIN-030`, `ADMIN-031`, `ADMIN-032`, `ADMIN-033`, `ADMIN-034`, `ADMIN-035`, `ADMIN-036`, `ADMIN-037`, `ADMIN-038`, `ADMIN-039`, `ADMIN-040`, `ADMIN-041`, `ADMIN-042`, `ADMIN-043`, `ADMIN-044`, `ADMIN-045`, `ADMIN-046`, `ADMIN-047`, `ADMIN-048`, `ADMIN-049`, `ADMIN-050`, `ADMIN-051`

<a id="t20"></a>

### T20 · 统一结果、缓存与状态基础

- 优先级：P0；复杂度：M；依赖：T00。
- 主归属：17项（已实现0 / 部分8 / 缺失9）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：读/写统一结构化结果：身份/权限/只读/关闭/审核/限流/结果不明；失败不假成功，写后回读；缓存与待发队列按账号隔离，领域状态驱动UI。
- [ ] 验收出口：书签、表态、通知已读、资料、草稿删除等失败注入均不显示成功；429可解释；换号/失权不见旧账号受限内容。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`GROUP-022`, `STATE-003`, `STATE-005`, `STATE-006`, `STATE-007`, `STATE-008`, `STATE-009`, `STATE-010`, `STATE-011`, `STATE-012`, `STATE-013`, `STATE-014`, `STATE-015`, `STATE-016`, `STATE-018`, `STATE-038`, `STATE-039`

<a id="t21"></a>

### T21 · 逐项实测、角色矩阵与发布回归

- 优先级：持续/收口；复杂度：L；依赖：每个工作包完成后执行；最终依赖全部适用项。
- 主归属：15项（已实现2 / 部分13 / 缺失0）；已实现项主要做回归，其他项先过适用性核验。
- [ ] 工作：按ID记录Web路径/资格/实际结果/安卓版本/方式/服务端回读/证据；补大字/TalkBack/系统返回/重启/离线/多端；本审计维护为活清单。
- [ ] 验收出口：适用项全部达到“原生已验收”或“Web兜底已验收”再勾选；待核/不适用单独说明；单元测试通过不能替代业务验收。

关联ID（主归属，每个清单ID恰好分到一个工作包；其他交叉依赖见审计备注）：

`STATE-047`, `STATE-048`, `CLOSE-001`, `CLOSE-002`, `CLOSE-003`, `CLOSE-004`, `CLOSE-005`, `CLOSE-006`, `CLOSE-007`, `CLOSE-008`, `CLOSE-009`, `CLOSE-010`, `CLOSE-011`, `CLOSE-012`, `CLOSE-013`

## 统一逐项验收记录

```markdown
- [ ] <清单ID> <功能描述>
  - Web可用性：开放/条件开放/停用/仍待核；观察日期=；来源=
  - 账号资格：信任等级=；群组=；青春版/Premium/开发者/版主管理员=（仅记录所需字段）
  - Web路径与实际结果=；Android版本/commit=；实现方式=原生/Web
  - Android操作与结果=；失败场景=；返回导航=
  - 服务端回读/另一端验证=；证据路径=；测试日期=
  - 结论=原生已验收/Web兜底已验收/失败/不适用（原因与复核条件）
```

Web兜底每项至少验证：保留登录→直达/可找到具体功能→必要系统能力可用→提交成功且可回读→取消/失败不误报→返回原生位置→涉及数据刷新。

源码“已实现”尚未满足上述验收出口，继续保留未勾选。线上扣分、发信、修改安全设置、付款和管理操作需要真实业务授权与可控样本；本次没有执行这些动作。

## 维护与完整性

- 清单保留全部原ID，不因暂不可用而删除；对未开放功能记录不适用及证据。新增Web功能追加新ID。
- 结构化数据以 [audit-2026-11-06.json](audit-2026-11-06.json) 为本次审计快照，后续状态变化应同时更新清单、统计与计划。
- [evidence-2026-11-06.json](evidence-2026-11-06.json) 保存源码哈希、未调用API检索和本轮测试结果。
- 本轮未验证全部插件版本或所有后台配置字段，因此“全清单关闭”仍须配合当前Web入口盘点与角色覆盖，不能承诺未来站点变化下永久不漏。
