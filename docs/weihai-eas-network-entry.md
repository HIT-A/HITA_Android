# 威海校区教务入口：WebVPN 的必要性与校园网可达性

调研日期：2026-11-10。结论基于公网 DNS 查询、直接抓取与仓库代码核对；**未在威海校园网环境实测**，
这一点在文末「未验证部分」单列。

## 结论摘要

1. HITA 目前**威海本科与研究生教务全部走 `webvpn.hitwh.edu.cn`**，无直连分支。
2. 威海教务系统（`jwts` / `yjsgl`）是**内网私有地址**，公网无法直达，因此 WebVPN 对校外用户是**必需的**。
3. 但 `webvpn.hitwh.edu.cn` 在威海网信办的官方定位是「**数据库VPN**」，与「**学校VPN**」
   （`ivpn.hitwh.edu.cn`）并列，两者用途不同。把教务登录塞进「数据库VPN」属于用途错配。
4. 校园网内直连教务（`172.26.64.16`）理论可行，但**当前代码没有直连分支**，也没有网络探测；
   用户反馈的「校园网登不进去」与「强制走 VPN 引入第二套 CAS 认证」是自洽的。

## 一、DNS 事实（2026-11-10 实测，阿里 DoH 与腾讯 DoH 双源一致）

| 域名 | 公网解析结果 | 性质 |
|---|---|---|
| `webvpn.hitwh.edu.cn` | `222.194.10.2` | 公网 IP |
| `ivpn.hitwh.edu.cn` | `222.194.3.10` | 公网 IP |
| `jwts.hitwh.edu.cn`（本科教务） | **`172.26.64.16`** | **内网私有地址（RFC1918）** |
| `yjsgl.hitwh.edu.cn`（研究生教务） | **NXDOMAIN**（公网无记录） | **纯内网域名** |
| `jwc.hitwh.edu.cn`（教务处主页） | `222.194.14.2`（CNAME `edunet.hitwh.edu.cn`） | 公网可达 |
| `yjs.hitwh.edu.cn`（研究生院） | `222.194.14.2`（CNAME `edunet.hitwh.edu.cn`） | 公网可达，**非**教务系统 |
| `authserver.hitwh.edu.cn`（猜测的认证域） | NXDOMAIN | 不存在，认证走 `ids.hit.edu.cn` |
| `vpn.hitwh.edu.cn` | `221.2.164.60` | 存在，但**公网 443/80 均超时**，疑似校内/特定线路 |
| `webvpn2.hitwh.edu.cn` | `222.194.3.122`（与 `ivpn` 同网段） | 第二套 WebVPN，302 → `/fingerprint` |

含义：教务系统本身只在内网暴露。校外必须经 WebVPN 反代，**这条路径是对的**；
但校园网内用户本就在内网，直连 `172.26.64.16` 是更短、更少认证环节的路径。

## 一之二、四个入口的实测性质（2026-11-10，公网侧）

| 入口 | 证书 | 实测行为 | 能否用浏览器访问教务 |
|---|---|---|---|
| `webvpn.hitwh.edu.cn` | `*.hitwh.edu.cn`，TrustAsia，**校验通过** | 302 → `/login` → CAS；加密路径可反代内网 | **能**（当前 HITA 用的就是它） |
| `webvpn2.hitwh.edu.cn` | 同上（同网段） | 302 → `/login` → `/fingerprint`（设备指纹） | 未知，未实测其反代能力 |
| `ivpn.hitwh.edu.cn` | **自签名** `CN=sslvpn`（Sangfor 默认） | `/` 返回 `Your browser does not support JavaScript`；仅 `portal` 入口 | **不能**：这是需专用客户端（aTrust）的 SSL VPN，浏览器无法借用 |
| `vpn.hitwh.edu.cn` | 未取到（连接超时） | 公网 443/80 均超时 | 公网不可达 |

**关键否定结论**：「学校VPN」（`ivpn`）在威海**不是**可用的教务入口。它只提供
`portal` 客户端入口，浏览器打开只有一句 JS 提示，没有 Web 反代能力。本部的
`vpn.hit.edu.cn` 之所以能当教务网关，是因为它同时提供了**反代域名**
（如 `jwts-hit-edu-cn.vpn.hit.edu.cn`），威海侧没有对应的反代域名
（`jwts-hitwh-edu-cn.webvpn.hitwh.edu.cn`、`jwts-vpn.hitwh.edu.cn` 均 NXDOMAIN）。

## 一之三、`webvpn2` 实测（2026-11-10，公网侧）

`webvpn2.hitwh.edu.cn`（`222.194.3.122`）是**与 `webvpn` 并行的第二套 WebVPN**，能正常访问，
且与 `webvpn` 有三处实质差异：

| 维度 | `webvpn` | `webvpn2` |
|---|---|---|
| 认证方式 | **仅 CAS**：`/login` 直接 302 到 `/https/.../authserver/login?service=...` | **local + cas 双通道**：登录页有 `data-type="local"` 与 `data-type="cas"` 两个 tab |
| 登录实现 | 跳转到统一身份认证表单 | 自建表单，POST `/do-confirm-login`（`auth_type=local`） |
| 设备指纹 | 无 | **强制 FingerprintJS**：`/fingerprint` 采集 `visitorId` 后跳 `/set-fingerprint` |
| 票据 cookie | `wengine_vpn_ticketwebvpn_hitwh_edu_cn` | `wengine_vpn_ticketwebvpn2_hitwh_edu_cn`（**独立票据**） |
| 加密路径 | `/http/7772...`、`/https/7772...` | **相同格式同样被接受**（未认证一律 302 回 `/login`） |
| 二次认证 | CAS 侧决定 | 有 `/do-second-login` 接口 |

实测 `local` 通道可用：带 `_csrf`、`auth_type=local`、`username`、`password`、`remember_cookie`
POST 到 `/do-confirm-login`，服务端返回结构化 `401 {"success":false}`（而非拒绝该通道），
说明该认证方式对外网开放。当前 `needCaptcha=false`。

**对 HITA 的意义**：`webvpn2` 的 `local` 通道意味着登录页**不必跳 CAS**，理论上可在 WebView 内
用同一份账号密码完成认证，少一次跨域跳转与多因子机会。但它引入两项新成本：
强制指纹采集（WebView 的 `visitorId` 稳定性和可识别性未验证），以及独立票据 cookie
（`EASRepository.WEB_SESSION_COOKIE_TARGETS` 与 `WebLoginSuccessPolicy` 都需扩展）。

**未验证**：使用真实账号在 `webvpn2` 完成 local 登录后，加密路径是否真能反代到
`jwts` / `yjsgl`；以及校内网访问 `webvpn2` 的表现（与 `webvpn` 是否一致）。

## 二、WebVPN 的机制与官方定位

### 2.1 加密路径机制

`webvpn.hitwh.edu.cn/http/77726476706e69737468656265737421fae0558f693861446900c7a99c406d3667`

该十六进制串的前 16 字节解码为 ASCII `wrdvpnisthebest!` —— 深信服（Sangfor）WebVPN 的固定
AES 密钥前缀。后续字节是**目标内网地址的加密串**。也就是说，URL 路径本身就是「要去哪个内网目标」
的凭据，这也是为什么 HITA 里两个不同业务（本科教务、研究生教务）对应两段不同密文。

### 2.2 官方定位

威海网信办首页（`http://nic.hitwh.edu.cn/`）把两者并列，命名区分明确：

- **数据库VPN** → `http://webvpn.hitwh.edu.cn`
- **学校VPN** → `https://ivpn.hitwh.edu.cn`

「数据库VPN」的语义是**访问学术数据库/文献资源**，不是「教务系统代理」。HITA 把教务登录放在
这里，是能跑通（反代本身不区分业务）但与官方定位不符的用法。

### 2.3 WebVPN 自身也会走 CAS

实测重定向链：

```
GET https://webvpn.hitwh.edu.cn/           -> 302 /login
GET https://webvpn.hitwh.edu.cn/login      -> 302 /https/.../authserver/login?service=https://webvpn.hitwh.edu.cn/login?cas_login=true
```

即：**WebVPN 需要一次独立的统一身份认证**。未认证访问任何加密路径一律 302 回 `/login`。

所以「校园网 + 走 WebVPN」意味着：用户在校园网内本已具备直连条件，却还要额外完成一次
CAS 认证（且可能触发多因子），失败面明显更大。这与用户反馈吻合。

## 三、HITA 现状（代码核对）

| 位置 | 事实 |
|---|---|
| `WebViewLoginActivity.CampusUrls` | `WEIHAI_BASE = "https://webvpn.hitwh.edu.cn"`；`WEIHAI_LOGIN = "$WEIHAI_BASE/"`；`WEIHAI_JWTS = "$WEIHAI_EAS_PREFIX/loginCAS"` |
| `WeihaiEASWebSource.hostName` | 硬编码 `https://webvpn.hitwh.edu.cn/http/7772...`，无直连分支 |
| `WeihaiGraduateSessionPolicy.BASE_URL` | `https://webvpn.hitwh.edu.cn/https/7772...`，研究生同样走 VPN |
| `EASRepository.WEB_SESSION_COOKIE_TARGETS` | 威海只认 `https://webvpn.hitwh.edu.cn` 一个宿主（`JSESSIONID`、`route`） |
| `WebLoginSuccessPolicy.isWeihaiAuthenticatedPage` | 成功判定依赖 `wengine_vpn_ticket*` cookie |
| `WebViewSecurityPolicy` | 白名单按 `.hitwh.edu.cn` 后缀放行，**直连域名已天然允许** |
| `WeihaiEASWebSource` | 存在 `termsHostOverride` / `timetableHostOverride` 等注入点，但业务代码从未传入 |

结论：架构上留了「换宿主」的口子，但没有任何网络环境判断，行为是**一律走 VPN**。

## 四、可行的方向（未实施，供决策）

按「改动成本 / 用户收益」排序，均**未实现**，此处只作记录。

### 方案 A：先探测再选入口（推荐）

登录前并发探测两个目标，用结果选入口：

- 直连探测：`http://jwts.hitwh.edu.cn/`（或研究生对应地址）能否在短超时内建立连接；
- 可达则走直连，失败再回落 WebVPN。

优点：不依赖用户手动选择，校园网自动走短路径。
代价：需要补 `jwts`/`yjsgl` 的直连成功判定（无法依赖 `wengine_vpn_ticket`）、
`WEB_SESSION_COOKIE_TARGETS` 需按入口动态选择、`WeihaiEASWebSource` 需支持宿主注入。

#### 方案 A 的实施状态（2026-11-11）

已实现，核心是 `WeihaiEasEntryPolicy`（纯函数）+ `CampusReachabilityProbe`（可注入探测）：

| 关注点 | 落点 |
|---|---|
| 选入口 | `WeihaiEasEntryPolicy.choose(directReachable, vpnReachable)`，直连优先、否则回落 VPN |
| 探测 | `HttpCampusReachabilityProbe`：HEAD 优先、GET 复核，2.5s 超时，任何 HTTP 响应都算可达 |
| 登录页 | `WeihaiEasEntryPolicy.loginUrl(entry, graduate)` |
| 成功判定 | `WeihaiEasEntryPolicy.isAuthenticated(entry, url, cookies)`；直连不要求 `wengine_vpn_ticket` |
| 数据层一致性 | 登录把入口 base 写入 `web_base_url`，`WeihaiEASWebSource` 解析后决定请求 host |
| 凭据自动填充 | `EasWebCredentialPolicy` 为威海补上 `jwts`/`yjsgl` 与 `/xtgl/login` 路径 |

**超时取舍**：探测超时只有 2.5s，且只探测直连（VPN 是兜底，不需要探测）。
超时或异常一律判为不可达 → 回落 VPN，与旧版本行为一致，不会把用户挡在门外。

**仍需真机验证**：`jwts` 直连登录表单的实际提交行为与成功落点（公网无法访问内网地址，
只能在校园网内验证）。

### 方案 B：让用户选「网络环境」

登录页加一个「校园网/校外」开关，记忆上次选择。

优点：实现简单、行为可预期，无探测误判。
代价：把判断负担交给用户，选错仍会失败。

### 方案 C：失败后自动回落

仍默认 WebVPN，检测到「登录/认证异常」时提示并尝试直连。

优点：改动最小，只加一条兜底。
代价：首次失败体验差，且要区分「密码错」与「网络路径不可用」，否则会误导用户。

### 方案 D：维持现状，只改文案

明确告知「校外必须用 WebVPN；校园网内请连接校园网后重试」。

优点：零风险。
代价：没有解决校园网用户的实际问题。

## 五、未验证部分（勿当结论使用）

- **未在威海校园网内实测**：本机在公网，无法验证校内访问 `webvpn.hitwh.edu.cn` 的真实行为
  （是否报错、是否绕行、是否因回源内网而超时）。
- 用户反馈「走 vpn 校园网登不进去」的具体失败形态未知（白屏 / 认证失败 / 超时 / 证书错误），
  需要一份该场景的日志或录屏才能定位到具体环节。
- `yjsgl.hitwh.edu.cn` 在校园网内的解析结果未知（公网 NXDOMAIN 只说明它不对外）。

## 六、若要继续推进，需要的信息

1. 校园网环境下访问 `https://webvpn.hitwh.edu.cn/` 的实际表现（能否打开、跳转链、错误页文案）。
2. 校园网环境下 `jwts.hitwh.edu.cn` / `yjsgl.hitwh.edu.cn` 是否可直连（浏览器直接打开即可判断）。
3. HITA 在该场景下的失败表现（HITA 日志里 `outdated browser` / `isSuccessPage(weihai)` 等埋点输出）。

## 七、2026-11-13 修复：两个已报告的登录故障

代码级根因已定位并修复，全部落在纯函数层，可用单测锁死。
回归用例见 `app/src/test/java/cn/limpu/hita/data/source/web/eas/WeihaiGraduateSessionRegressionTest.kt`。

### 7.1 校园网内报「浏览器版本过旧」

**成因**：`WeihaiGraduateSessionPolicy.needsDesktopUserAgentFor()` 只对
`webvpn.hitwh.edu.cn` 且路径以反代前缀开头才返回 `true`。校园网直连时登录页是
`https://yjsgl.hitwh.edu.cn/`，判定恒为 `false`，WebView 全程保持移动 UA，
被 yjsgl 服务端按 UA 判成过时浏览器并下发拦截页。

**修复**：把直连宿主 `yjsgl.hitwh.edu.cn` 纳入桌面 UA 判定（`/authserver/` 表单页仍保留移动 UA，
否则原生输入法与 HTML input 会错位）。同一个应用、同一套服务端 UA 判定，两条入口必须一致。

**证据边界**：服务端到底按 `Mobile` token、Chrome 大版本号还是 UA 白名单判定，属**未验证**。
已记录被拒的是 `Jsoup/*` 与 `Chrome Mobile 144`，已接受的是桌面 Chrome 134/146。
仓储内 iOS 端的实验记录显示 iOS Safari 18 移动 UA 可用，说明拒绝集不是「全部移动 UA」，
但对 Android WebView 统一用桌面 UA 是保守且与现有数据层一致的选择。

### 7.2 校外（WebVPN）走完验证码后报「会话无效」

**成因**：`5aa5fe6` 把 `finishWithCookies()` 写入 `web_base_url` 的值从「实际落地页 URL」
改成「入口根地址」（`WeihaiEasEntryPolicy.timetableBaseUrl`），而数据层
`WeihaiGraduateEASWebSource.login()` 仍把它当落地页传给
`WeihaiGraduateSessionPolicy.isAuthenticatedLanding()`。该函数要求 path 精确等于
`/authentication/main` 或 `/student_index`，入口根地址必然不匹配——于是**无论登录是否成功**
都恒定返回「研究生登录会话无效」。这与用户描述的「验证码之后才报会话无效」且与入口无关完全吻合。

**修复**：把「落地页判定」与「凭据判定」分开。

| 关注点 | 旧实现 | 新实现 |
|---|---|---|
| 凭据种类 | 恒要求 `wengine_vpn_ticket` | 按入口：反代看票据，直连看 `JSESSIONID` |
| 数据层校验 | 把 base 当落地页 | 只看入口对应凭据，再由 `/user/me` 实测 |
| 收尾闸门 | 恒用反代落点判据 | 按 `weihaiEntry` 选择落点判据 |
| 直连连通判定 | 精确路径白名单 | 非登录/认证页 + 有业务会话 |

新增 `WeihaiEasEntryPolicy.hasGraduateSessionCredential(baseUrl, cookies)` 承担入口相关的凭据判定；
`validate()` 不再写死反代票据。

### 7.3 顺带修掉的第三处缺陷

`approvedCredentialOrigins()` 只允许 `webvpn.hitwh.edu.cn` 与 `ids.hit.edu.cn` 的凭据桥消息，
而 `EasWebCredentialPolicy` 早已把 `yjsgl.hitwh.edu.cn` / `jwts.hitwh.edu.cn` 列为直连登录表单页。
两者不一致导致**直连场景下手动输入的账号密码永远捕获不到**，下次无感续期只能重新手输。已对齐。

### 7.4 回归测试的可判别性验证

成功标准要求断言「能在旧实现上失败」。因为修复同时引入了新符号，
直接回退整个文件会让测试类编译失败（已实测：`compileDebugUnitTestKotlin FAILED`），
无法区分「编译不过」与「断言失败」。因此采用**单点回退**，只移除一条判定、保留其余新 API：

| 探针 | 改动 | 结果 |
|---|---|---|
| bug 1 | 删除 `needsDesktopUserAgentFor()` 中的直连宿主分支 | `direct graduate entry requires desktop user agent` **FAILED** |
| bug 2 | `hasGraduateSessionCredential()` 入口判定恒真（等同旧口径恒看反代票据） | `session credential follows the entry` **FAILED** |

两次探针都在跑完后立即逐字节还原，`git status` 为空。

### 7.5 仍未验证（需要校内网/真机）

1. `yjsgl.hitwh.edu.cn` 在校园网内的实际解析地址与 443 可达性。
2. 直连研究生的登录表单究竟由 yjsgl 自己渲染（`/xtgl/login_slogin.html`）还是跳 `ids.hit.edu.cn` CAS。
   两条路径当前都能走：前者由 `EasWebCredentialPolicy` 自动填充，后者走既有 CAS 流程。
3. 直连是否需要额外 `route` cookie（当前只要求 `JSESSIONID`）。
4. 服务端 UA 拒绝集的确切边界（见 7.1）。
