# 修复回归证据

`results.json` 是创作恢复、删除确认和归档确认的最终结果。截图中的数据均为本地 fixture；测试没有操作真实帖子或私信。

`ComposerRegressionPreview.kt` 仅用于隔离 checkout，不包含在业务 APK。复现时将它复制到临时 checkout 的 commonMain UI 目录，在 MainActivity 现有 debuggable preview 分支接入 `composer-` 前缀场景，调用 `LinDoComposerRegressionPreview(debugPreview, closePreview)`，然后运行 `composer-ui-test.py`。场景包括 `composer-edit`、`composer-reply`、`composer-new`、`composer-reader`、`composer-messages` 和 `composer-drafts`。不要将 fixture 接入正式产品。

`build-validation.json` 保留单元测试、Lint 和构建状态。`global-ui-results.json` 为业务 APK 的欢迎页、大字号、品牌状态、关闭动效与富文本回归结果。
