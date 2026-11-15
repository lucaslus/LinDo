# 字号与 Cloudflare 验证排查（2026-11-15）

## 字号

首页紧凑列表标题基准为 16sp，经根 CompositionLocalProvider 的 LocalDensity 缩放，并非绕过设置的固定像素。之前“小”为 0.95，标准为 1.00，缩小仅 5%；最小/小/标准与大/最大步幅也不同。系统字号仍参与最终比例，不抹掉无障碍设置。

五档统一为 0.80/0.90/1.00/1.10/1.20。Android 和 desktop 使用同一枚举，v4 设置迁移按原档位名称映射，避免旧“最小”0.90 被当成新“小”。首页、正文、设置与共享弹层继续通过统一 Density 应用，基准样式无需逐个乘一遍缩放。

## 已确认的客户端问题

1. Cloudflare 冷却门禁主要限定 DesktopNetworkPolicy.enabled，Android 后续普通请求仍可能进入 WebView。改为共享恢复策略；验证窗口打开时持续暂停，而不是计时结束就恢复。Android 队列真正发送前再检查一次，避免排队中的请求继续冲击站点。
2. Android 手动恢复会主动清除挑战冷却。现在普通刷新保留冷却，仅明确完成站点验证才清除。
3. 验证流程必须先找到特定 csrf meta、再请求 latest.json；一旦该接口被挑战，又将整个 WebView 导航到 JSON URL，可能造成接口挑战/下载与网页状态判断互相卡住。改为识别实际 Discourse 文档（新版 JSON script 与旧 data-preloaded）、已渲染 JSON，未知页面才单飞探测一次；禁止自动导航 JSON、禁止循环网络探测、保留 Retry-After。
4. 验证过程中继续暂停资料同步和部分图片补全，避免旧后台响应干扰恢复。

真实论坛 HTML 证明当前导航已经放行，不代表站点每个 API 路径使用相同 WAF 规则；若原接口仍被挑战，仍会显示真实失败。不能把 `cf_clearance` 存在、按钮被点击或窗口关闭当成通过。

## 官方资料与边界

- Challenge loops：不稳定网络、浏览器环境或脚本兼容性、检测误判都可能触发；客户端截图无法确定服务端命中了哪条规则。
  https://developers.cloudflare.com/cloudflare-challenges/troubleshooting/challenge-solve-issues/
- Mobile implementation：要求 JavaScript、DOM storage、Cloudflare 域名访问及稳定 UA。现有可见 WebView 已启用 JavaScript、DOM storage 和 Cookie，保留原生 UA。本次不伪造指纹，也不自动点击真人验证。
  https://developers.cloudflare.com/turnstile/get-started/mobile-implementation/
- Challenges：发起挑战与提交验证来自不同 IP 会导致失败；VPN 自动切换、应用与验证域名分流是排查候选，不能据此断言用户网络就是根因。
  https://developers.cloudflare.com/cloudflare-challenges/concepts/how-challenges-work/
- Challenge passage：由站点配置有效期，默认 30 分钟（不等于 linux.do 当前配置）；不适用于 rate limiting。App 无法自行延长有效期或关闭站点规则。
  https://developers.cloudflare.com/cloudflare-challenges/challenge-types/challenge-pages/challenge-passage/
- 系统浏览器 Cookie 与 App WebView 不自动互通，不能保证换成系统浏览器验证就能给 App 放行。

## 尚需实机证据

本机没有已连接三星。测试使用模拟响应与受控网页验证返回逻辑，不自动操作生产验证码，不能证明真实验证频率已降低。若固定网络仍循环，需要收集 WebView 版本、失败时间、请求路径、cf-ray 和脱敏错误日志，由站点确认规则。当前 API bridge 使用本地同源引导文档，无法据此断言站点 JavaScript Detections 是否启用或是否因没有真实 HTML 访问而触发；不贸然把它改为隐藏加载完整论坛网页（会额外执行网站轮询与资源请求）。
