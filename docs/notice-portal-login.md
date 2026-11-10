# 深圳校园通知门户登录

## 网址与 scheme

深圳校园通知门户为 **`http://info.hitsz.edu.cn/`**（`CampusNoticeParser.PORTAL_URL`）。

必须用 http，不能改成 https：

| 端点 | 响应方 | 行为 |
|---|---|---|
| `http://info.hitsz.edu.cn/` | `VAppServer/6.0.0` | 302 → `/system/resource/code/auth/caslogin.jsp` |
| `https://info.hitsz.edu.cn/` | 另一前置（`Server` 头不同） | 同样 302，但登录回跳的 service 仍是 http |

CAS 的 `service` 参数被服务端固定成
`http%3A%2F%2Finfo.hitsz.edu.cn%2Fsystem%2F...%2Fcaslogin.jsp`，因此登录后的
`JSESSIONID` 落在 **http 域**上；用 `https://` 取 cookie 取不到，通知同步会一直
判成"未登录"。`network_security_config.xml` 已为 `info.hitsz.edu.cn` 放行明文。

## 登录方式：复用本部统一身份认证

未登录访问任意 `list.jsp` 都会被 302 到
`https://ids.hit.edu.cn/authserver/login?service=...`，这是**本部**统一身份认证，
与本部教务登录是同一套表单：

```
username / password / lt / execution / _eventId / rememberMe
```

因此门户登录直接复用本部凭据（`InfoPortalLoginPolicy.credentialCampus = BENBU`），
并且与网页教务共用同一套 `EasWebCredentialPolicy`：

CAS 表单页的处理由 `InfoPortalLoginPolicy.casFormAction` 决定，分三种情形：

| 情形 | 动作 |
|---|---|
| 有已保存凭据 | 勾选「N 天免登录」→ 自动填充 → **自动提交一次** |
| 无已保存凭据、交互模式 | **只勾选「N 天免登录」**，页面留在前台等用户输入 |
| 无已保存凭据、静默模式 | 立即 `RESULT_CANCELED`，交回宿主弹手动登录 |

1. 勾选「N 天免登录」（`rememberMeScript`），让 `CASTGC` 成为持久票据而不是会话 cookie；
2. 有凭据时才自动填充并自动提交（`autofillAndSubmitScript`）；
3. 同一张表单只提交一次，单次登录最多两张，避免密码失效时反复提交触发风控；
4. 登录成功（`CAS` 回跳、门户 `JSESSIONID` 就绪）后才把用户手输的账号密码写入
   `EasCredentialStore`，避免把输错的密码存成"可用凭据"。

**首次登录不得自动收场**：没有已保存凭据时，交互模式只勾选免登录就停下，
不填充、不提交、不结束 Activity。历史上这里无条件「先勾选、再无凭据就结束」，
导致用户刚看到登录页就被弹出，必须点第二次才能登录。

## 登录态判定

`InfoPortalLoginPolicy.isLoginComplete` 要求两个条件同时成立：

- 当前 URL 是门户的 `list.jsp`；
- 门户域上存在非空的 `JSESSIONID`。

只看 `CASTGC` 不算登录：它属于 `ids.hit.edu.cn`，单独存在不代表门户已放行。
`/caslogin` 中转页一律视为未登录（`isLoginTransitPage`）。

## 无感重登

`CampusNoticeRepository` 在同步失败时返回 `NEED_LOGIN`，`BlogFragment` 的
`CampusNoticeScreen` 收到后**先自动拉起一次静默登录**（`EXTRA_SILENT_MODE=true`）：

- **启动静默登录前先检查是否存有凭据**（`EasCredentialStore.get(campus)`）：
  没有凭据时直接走交互登录，避免"打开→勾选→自动退出"徒劳一趟；
- 静默模式复用已保存凭据，自动完成勾选、填充、提交，成功后直接刷新通知；
- 没有可用凭据、或自动提交失败时立即返回 `RESULT_CANCELED`，不空等超时；
- 每次进入该校区只自动尝试一次，失败后露出「登录信息门户」按钮由用户手动完成，
  不会反复弹窗。

## 关闭登录弹窗后

登录成功后 `InfoPortalLoginActivity` 会 `CookieManager.flush()`，把门户会话与
CAS 票据写盘，冷启动后仍可用，无需重新登录。
