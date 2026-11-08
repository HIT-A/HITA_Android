# 网页登录态持久化与静默续期

## 问题

本部、威海通过教务网页登录获取课表，深圳通过统一身份认证（aTrust + CAS）网页登录。
网页登录产生的**业务会话 cookie** 是服务端会话级（`JSESSIONID` / `SESSION` / `route` /
`wengine_vpn_ticket`），应用每次冷启动后携带旧 cookie 请求，服务端判定失效，于是每次重开
应用都会弹一次「教务登录已过期」（`eas_session_expired_message`：课表自动刷新失败）。

## 分层事实（2026-11-08 实测，深圳账号）

| cookie | 域 | 有效期 |
|---|---|---|
| `CASTGC`（CAS 票据授予票据） | `ids-hit-edu-cn-s.hitsz.edu.cn` | **14 天** |
| `MULTIFACTOR_BROWSER_FINGERPRINT` | 同上 | 400 天 |
| `sdp_user_token`（aTrust） | `.hitsz.edu.cn` | 会话级 |
| `SESSION`（jw 代理业务会话） | `jw-hitsz-edu-cn.hitsz.edu.cn` | 会话级 |
| `JSESSIONID` / `route`（CAS 会话） | `ids-hit-edu-cn-s.hitsz.edu.cn` | 会话级 |

结论：**身份层票据长期有效、业务会话短期有效**。只要身份层还在，重走一次登录链即可在
无凭据输入的情况下换回新的业务会话——这正是持久化的抓手。

## 实现

1. **登录成功即落盘**（`WebViewLoginActivity.finishWithCookies`）追加
   `CookieManager.flush()`，避免进程被杀时身份票据尚未写入磁盘。
2. **启动导入失败先静默续期**（`MainViewModel` + `MainActivity`）：
   - 自动导入返回 `NOT_LOGGED_IN` 且本进程尚未用过续期预算时，发
     `MainEffect.SilentRenewal`，由宿主以 `EXTRA_SILENT_MODE` 启动
     `WebViewLoginActivity`（透明主题、18s 超时）。
   - 该活动复用 CookieManager 中仍有效的身份票据走完 aTrust/CAS/VPN 链路，遇到
     需要输入密码/验证码/二次验证的页面即以 `RESULT_CANCELED` 退出。
   - `RESULT_OK` 后由 `EASRepository.loginWithWebSession` 校验并落库新会话，随后
     `MainStartupCoordinator.reloginSucceeded()` 重置导入门控并重排一次导入。
3. **续期失败仍照常通知**：改为 `RESULT_CANCELED`、校验失败，或该进程已用掉续期预算，
   都回落到原有的 `MainEffect.SessionExpired` 通知，不隐藏任何真实过期。
4. 三端走同一条链路：`loginWithWebSession` 的元数据映射
   （`EASRepository.webSessionLoginMetadata`）被交互登录与静默续期共用。

## 预期免登录窗口

客户端不做额外 TTL 截断，会话按服务端签发的时间存活；免登录窗口由最长的身份票据决定：

- 深圳：`CASTGC` 14 天 → 目标「一周只重新登录一次」成立，上限约 14 天。
- 本部 / 威海：`vpn.hit.edu.cn` 零信任网关票据与 `wengine_vpn_ticket` 的生命周期由服务端
  决定，未持有账号未实测，机制与深圳一致。

## 取证

`app/src/debug` 的 `EasSessionDebugReceiver`（仅 debug 包，发布包不包含）可导出 cookie jar
当前持有的 cookie 名：

```
adb shell am broadcast -a cn.limpu.hita.action.DUMP_EAS_SESSION
adb logcat -d | grep eas-cookie-jar
```
