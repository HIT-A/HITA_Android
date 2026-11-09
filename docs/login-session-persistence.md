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

0. **登录页自动勾选「N 天免登录」**（`EasWebCredentialPolicy.rememberMeScript` +
   `WebViewLoginActivity.prepareLoginPageCredentials`）。这是整条链路的前提：
   CAS 只有在 `rememberMe` 勾选时才下发**持久** `CASTGC`，不勾选时 `CASTGC` 为
   `is_persistent=0` 的会话 cookie，进程被杀即失效——此前手机实测正是因此拿到
   会话级票据（见下方取证），冷启动必然需要重新输密码。脚本同时兼容原生
   `input[type=checkbox]` 与自定义 label 两种控件，只写 `checked=true` 且已勾选时
   直接返回，多次注入不会取消勾选；`onPageFinished` 后按 0/400/1200/2500ms 四轮重试，
   覆盖页面延迟渲染的情况。
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

手机实测（2026-11-08，Android 16 / PJW110，深圳账号）：
- 冷启动 ×3 全部免登录成功，课表直接加载，`0` 条「课表更新失败 / 教务登录已过期」通知。
- 未勾选免登录时导出 cookie 库，`ids-hit-edu-cn-s.hitsz.edu.cn` 的 `CASTGC` 为
  `is_persistent=0`（会话级）；`MULTIFACTOR_BROWSER_FINGERPRINT` 为 399.4 天；
  `grader.hitsz.edu.cn` 的 `sessionid` 为 7.4 天。即持久化能力完全取决于
  `rememberMe` 是否勾选，这是本次补上自动勾选的原因。

`app/src/debug` 的 `EasSessionDebugReceiver`（仅 debug 包，发布包不包含）可导出 cookie jar
当前持有的 cookie 名：

```
adb shell am broadcast -a cn.limpu.hita.action.DUMP_EAS_SESSION
adb logcat -d | grep eas-cookie-jar
```

cookie 库本身可整份取出核对过期时间戳（WebView 的库不受应用数据加密保护）：

```
adb exec-out run-as <pkg> cat app_webview/Default/Cookies > ck.db
# cookies 表：expires_utc 为 1601 起的微秒数，0 表示会话级；is_persistent=1 才是持久 cookie
```

## aTrust 门户的两种状态（2026-11-09 复核）

深圳教务入口 `https://jw-hitsz-edu-cn.hitsz.edu.cn/authentication/main` 在**没有有效 aTrust
会话**时，会被网关 302 到信任门户：

```
/authentication/main
  → trust.hitsz.edu.cn/controller/v1/public/verify?t=<jwt>
  → trust.hitsz.edu.cn/portal/shortcut.html?dest=%23%21%2Flogin&appUrl=<教务入口>
  → trust.hitsz.edu.cn/portal/?redirectid=… #/login      ← 统一身份认证表单
```

`/portal` 因此有两种完全不同的含义：

| 形态 | URL 特征 | 含义 |
|---|---|---|
| 认证/引导页 | `shortcut.html`、`#/login`、`#/down_client_*`、`#/app_center/auth` | **还没连上**，需要认证 |
| 已连接门户 | 其余 `#/…` 路由（服务页、用户信息等） | 已放行，可续跳教务 |

判定见 `WebLoginSuccessPolicy.isShenzhenTrustPortalAuthPage` /
`isShenzhenTrustPortalConnected`。把认证页当成"已连接"并回跳教务入口，会形成
`门户 → 教务 → verify → 门户` 的无限重定向，WebView 永远停在 `shortcut.html` 的
`Loading...`。`resumeAcademicLoginAfterVpn` 因此在三校区统一保留一次性续跳防护。

登录完成的落点：aTrust 放行后 jw 代理会回跳到 `https://jw-hitsz-edu-cn.hitsz.edu.cn/cas`，
页面内容就是教务首页，因此 `/cas` 也计入深圳的已登录落点。

## 无感登录（三校区）

1. aTrust 门户登录页（`#/login`）自动勾选《用户协议》并点「登录」
   （`ShenzhenTrustPortalAutoLogin`）。
2. 到达统一身份认证表单后，用已保存的账号密码自动填充并**自动提交**一次
   （`EasWebCredentialPolicy.autofillAndSubmitScript`）。同一张登录页只提交一次，
   单次登录最多提交 2 张页面，避免密码失效时触发风控。
3. 账号密码来源：用户手动登录成功后由 `EasWebCredentialCaptureState` 捕获并写入
   `EasCredentialStore`（EncryptedSharedPreferences）。三校区现在都在
   `finishWithCookies` 统一提交，不再只有深圳的 API 登录路径会保存。
4. 会话过期时 `MainViewModel` 的静默续期用同一套逻辑重走链路；失败才提示用户。

依赖 Web 会话的页面（可选课程、成绩分析、培养方案、课表导入）点「连接 web 教务」时
由 `EASActivity.launchDirectWebLogin` 直接打开 `WebViewLoginActivity`，不再弹校区/
账号选择弹窗。

## 静默续期与 CAS 自动提交（2026-11-09 修正）

静默续期（`EXTRA_SILENT_MODE=true`）原本在遇到统一身份认证页时立即
`finishWithCancelledResult()`，导致 `prepareLoginPageCredentials` 调度的自动填充/提交
根本来不及执行，无感登录在会话过期路径上完全失效。现在的规则：

| 到达页面 | 有已保存账号密码 | 没有凭据 |
|---|---|---|
| CAS `/authserver/login`（三校区） | 不中止，自动填充并提交一次 | 取消，交回宿主提示手动登录 |
| aTrust 门户认证页（`#/login`、`shortcut.html`） | 不中止，自动勾选协议并点登录 | 取消 |
| MFA / 滑块等交互页 | 取消 | 取消 |
| 提交脚本返回 `submitted:false` | 立即取消，不空等到 18 s 超时 | — |

静默窗口不会置 `leftForExternalVpn`：它不会真正外跳 aTrust 客户端，若置位会让回到
前台时再触发一次续跳，抢在自动填充之前打断流程。
