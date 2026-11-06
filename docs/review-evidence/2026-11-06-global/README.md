# 全局审查复核资料

对应提交 `0ee99bb`，完整说明见 [审查报告](../../review-global-2026-11-06.md)。所有 fixture 使用本地示例数据，不包含真实凭证。

`validation.json` 汇总测试结果，`audit-probes.xml` 是针对性测试的原始 JUnit 输出。6 项测试中 5 项失败是本轮发现的复现证据，不是已修复结果。复测时从仓库根目录使用 JDK 21：

```sh
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew \
  --init-script docs/review-evidence/2026-11-06-global/review-probes.init.gradle \
  :composeApp:desktopTest --tests do_.linux.lindo.LinDoAuditProbeTest
```

该 init script 仅为本次调用添加测试源码，正常测试配置不受影响。`draftJsonPreservesTabs` 通过仅说明 Kotlin 解析往返成功，没有验证服务器的 JSON 接受规则。

`ui-fixture.patch` 只应应用在 `0ee99bb` 的临时隔离 checkout。它为 Debug 预览添加 `audit-reply`、`audit-reply-dark`、`audit-create`、`audit-create-dark`、`audit-reader`，复用业务 composable，使用 fake transport。旧帖原文请求延迟 6 秒，用于重现输入被覆盖。不应把该 patch 合入产品或发布包。例：

```sh
adb -s emulator-5560 shell am start -S -n do_.linux.lindo/.MainActivity \
  --es lindo.preview audit-reader
```

截图中的 `audit_fixture`、`auditUserTyped` 都是构造数据。原有 UI 脚本的原始结果保留在 `ui-smoke-results.json`，其三项失败的解释见完整报告。
