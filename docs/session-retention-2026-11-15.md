# Build 51：登录恢复与向前分页

## 发现与修复

- Android 启动时，非空但不含 `_t` 的浏览器 Cookie 快照会使原来的 `ifBlank` 回退失效。现在仅补齐已保存的账号 Cookie，保留浏览器当前验证票据，不恢复旧 Cloudflare Cookie。
- CookieManager 写入是异步操作。请求等待主线程写入回调与读取确认后再发送，避免恢复尚未完成就查询登录态。
- 登录凭证轮换时及时保存；账号持久化提交完成后才返回。此处不人为延长服务端登录有效期。
- `/session/current.json` 连续失败后，使用同源论坛页面中的会话预加载数据独立确认；挑战页、网关错误、未知页面保留本地凭证。失败期间发生账号或 token 变化，旧请求不能注销新会话。API-key-only 会话不能仅凭 HTML 游客页面判定失效。
- “查看更早回复”放在标题信息与首条回复之间，居中、向上图标、轻量文字与加载状态。重复点击不并发加载；错误及验证恢复保持向前加载方向。仍属于原来的 header item，不改变楼层索引。

## 验证范围

新增会话恢复回归覆盖不完整 Cookie、凭证轮换、延迟写入、失败保护、真实游客失效、API key 会话和新旧 Discourse 预加载结构。配合原 OTP、账号切换与历史回归测试，并编译 Android。

没有连接真实三星设备，因此不能把这些检查描述成已完成真实覆盖安装、杀进程重开或服务器登录验证。旧版本已经删除的失效凭证仍需要重新登录一次。

## 参考

Discourse 官方 SessionController 的 current 操作：未登录返回空 404。
https://github.com/discourse/discourse/blob/main/app/controllers/session_controller.rb

官方会话 token 轮换及 Cookie 生命周期实现：
https://github.com/discourse/discourse/blob/main/lib/auth/default_current_user_provider.rb
