# 威海研究生登录与 Grader 待办设计

## 目标与限制

在新分支 `feat/weihai-graduate-grader-todos` 修复威海研究生登录状态，并在现有待办页接入 Grader。保留现有 Kotlin、Compose、MVVM、Hilt、Repository 和偏好存储约定，不重构其他功能，不修改签名或发布配置。

`HITA-Aura`、`HITA_NEXT` 只读。保留工作区已有的登录页、主页导航和公告等修改，最终提交只包含本任务内容。不执行 TDD，不构建任何 APK，不推送；最多五轮修改与验证。遇到必须人工完成的验证码、无法确认的关键接口、越界修改或破坏性操作时暂停。完成本地验证与新分支提交后等待用户安排。

## 已查证的现状

### 威海登录

Android 的威海入口及资料校验目前固定使用本科教务。`WebLoginSuccessPolicy.isWeihaiAuthenticatedPage` 要求 VPN ticket 和浏览器可见的 JSESSIONID；威海研究生参考实现只要求网关 ticket，并通过独立资料接口验证身份。因此不能仅修改“未登录”文字，也不能让任意 VPN cookie 直接成为登录成功依据。

协议依据：

- `HITA-Aura/Packages/HITAAcademicKernel/Sources/HITAAcademicConnectors/AcademicWebAuthentication.swift` 的 `AcademicWeihaiGraduateWebVPNEndpoints`。
- 同目录 `WeihaiGraduateWebVPNAuthentication.swift` 的身份解析及登录、会话恢复契约。
- Harmony 的威海链路仍是本科实现，不能当作研究生协议依据。

研究生代理根为 `https://webvpn.hitwh.edu.cn/https/77726476706e69737468656265737421e9fd529b2b7e60596a1f81e29d51367b6311`；入口 `/cas`，成功页 `/authentication/main`、`/student_index`。`POST /user/me?vpn-12-o2-yjsgl.hitwh.edu.cn` 返回顶层 JSON；`yhdm` 或 `username` 必须非空，`pylx` 必须为 `"2"`，`sfxs` 必须为 `"1"`，`rolecode` 必须包含 `"02"`。`xm` 可选。

尚未使用真实威海研究生账号核验，不能将参考协议的离线验证表述为真实登录已通过。

### Grader

参考 iOS 仓库只找到作业提交方式及手动链接；Harmony 只有站点入口，没有找到可直接移植的自动同步实现。已进一步读取 Grader 当前官方前端脚本，并通过用户手动登录后的会话只读核验：

- 登录入口：`https://grader.hitsz.edu.cn/api/login/sso`。
- `GET /api/auth/current_user/`：身份对象，字段包含 `id`、`username`。
- `GET /api/courses/?all=true`：该账号课程数组。
- `GET /api/assignments/?course=<id>`：课程完整作业数组，包含 `id`、`title`、`course`、`published`、`start_time`、`due_time` 等。
- 截止时间是带时区的 ISO 时间，例如 `2026-10-08T15:59:00Z`。
- 会话使用安全 HttpOnly `sessionid` cookie。只读 GET 不需要写入接口的 CSRF token。
- 本次读取 12 门课程、66 项作业，核验当时有 15 项已发布且未过截止时间。数量随时间及网站数据变化，不作为固定验收值。

上述请求均返回 200；没有提交作业、修改成绩或改动远端数据。账号密码、会话 cookie 和真实用户信息不进入源码、文档、测试样例或提交。

官方来源：站点 `/home` 引用的 `static/login-BV8Kriud.js`、`static/user-WCzMIJnU.js`、`static/course-CU4lgi7Q.js`、`static/assignments-DtqybcYD.js`。资源文件名可能随站点发布变化，产品不依赖这些哈希文件名。

## 用户可见行为

### 威海研究生

威海登录提供本科生、研究生选择；会话恢复沿用已保存的培养层次。研究生进入独立教务入口，正确落点及非空网关 ticket 只是候选会话，必须通过研究生资料校验才保存并发布已登录状态。保存真实账号、可选姓名、校区和研究生类型；失败不保存伪登录状态，不继承本科或其他账号的身份。

本次只保证认证、身份和会话恢复。不把研究生会话送往威海本科资料接口；尚未支持的研究生教务查询明确提示不支持，不伪装成会话过期，也不擅自增加整套课表、成绩、考试接入。

### 截止时间

待办内容下方独立一行，以次要字号显示截止信息，不以星期或相对时间代替具体日期。Grader 使用完整 `yyyy-MM-dd HH:mm`，按中国标准时间（Asia/Shanghai）展示，正确处理 UTC 跨日。

手动待办保留原有按日期查看和编辑行为，增加可选截止时分。用户已确认：已有只有日期的待办按当天 23:59 截止；新待办未单独设置时分时也按 23:59。没有日期的历史待办明确表示未设置截止日期，不擅自赋予日期。

### Grader 接入

现有待办页提供独立 Grader 登录/重新登录、刷新、退出入口；使用应用内 WebView 走网站统一认证，不把网站密码保存为应用账号密码。网页落点不能单独证明登录，必须通过当前用户接口确认。

Grader 区域始终列出该账号所有未过期的已发布实验及作业，不受手动待办当前所选日期限制，避免未来作业被隐藏。Grader 网站通过统一 assignments 集合提供这些任务，不根据标题猜测任务类型，也不只取最近三项作业。

每项至少显示作业名称及完整截止时间；课程名可用于消除同名作业歧义。任务按截止时间排序，可勾选完成、撤销完成，已完成项可折叠。手动完成仅是本地待办状态，不代表向 Grader 提交作业，也不根据网站已有提交记录擅自替用户标记完成。

完成状态以已验证账号 ID 和作业 ID 隔离，刷新、页面重建及应用重启不能清除。重复刷新不产生重复任务；截止延期后仍沿用相同任务的完成状态。切换账号或退出后不得显示上一个账号的任务和完成状态。

过期项不再出现在 Grader 待办列表。截止时间等于当前时刻仍算未过期；使用可固定时钟的数据路径验证边界。页面恢复/时间推进时重算可见列表。

## 数据与失败契约

沿用已有偏好存储，手动待办可向后读取旧 JSON，不引入 Room schema 改动。Grader 会话采用项目已有的加密偏好模式，不降级到明文，不记录密码、cookie、完整身份响应或提交内容。缓存与完成状态按账号隔离。

网络及持久化在 IO 线程执行，UI 仅发起操作及观察状态。只有全量课程及对应作业读取成功后才替换该账号缓存，部分请求失败不得把剩余任务误删。刷新期间完成勾选不能被迟到快照覆盖。账号切换、退出或新一轮认证后，旧请求不能重新发布旧账号数据。

401/403 清楚提示会话已过期并允许重新登录；网络/服务端失败显示错误而不是空列表成功，保留同账号缓存及完成状态。持久化失败不向 UI 宣布保存成功。只访问固定 HTTPS Grader 域名下经查证的只读路径，不跟随响应内容向其他站点发送会话。

## 验收与证据

1. 不生成 APK 的 Kotlin 编译检查完成；运行现有相关 JVM 单元测试。
2. 本地数据验证覆盖威海研究生正确/错误落点、仅网关 ticket、资料身份及角色有效性、本科学段不受影响。
3. 数据验证覆盖 Grader 完整集合解析、未过期边界、时区跨日、重复同步、账号隔离、完成及撤销完成后重新加载。
4. 验证手动待办旧数据兼容、截止时分保存与重载、实际展示所用截止文本。
5. 复用已授权 Grader 会话进行只读核验；不向真实账号写入测试数据。
6. Android WebView 真机登录、设备界面及真实威海研究生登录仍等待用户后续安排，交付时明确此限制。
7. 记录实际命令及结果，提交本任务修改到新分支，不包含无关用户修改，不构建 APK、不推送。

## 实施验证记录（2026-11-07）

### 仓库扫描与审阅

覆盖 app 的 UI、data、agent、feature，以及 component、hitauser、style、timetable-core 全部模块和源码集，检查构建配置、脚本、CI、主要文档与资源目录；重点读取登录、身份持久化、待办、网络及存储链路。该覆盖是目录、模式及关键代码审阅，不是逐文件逐行审计。参考仓库始终只读。

独立进行 Standards 与 Spec 两轴审阅，修正了研究生入口初始化顺序、Grader 取消初始化后的同步状态收尾、设备测试存储隔离及成绩页未显示“不支持”提示的问题。剔除依赖用户未提交 VPN 恢复函数的暂存调用后，将本任务索引独立检出验证，避免用户原改动掩盖编译依赖。

### 实际运行

使用本机 Eclipse Adoptium JDK 21.0.10 运行 `gradle/wrapper/gradle-wrapper.jar` 的 `org.gradle.wrapper.GradleWrapperMain`。最终成功运行的 Gradle 参数为：

```text
--init-script <临时目录>/smoke.init.gradle
:app:compileDebugKotlin :app:compileDebugJavaWithJavac
:app:testDebugUnitTest :component:testDebugUnitTest :hitauser:testDebugUnitTest
:timetable-core:test :app:hitaDataSmoke
:timetable-core:verifyJvmDependencies :style:verifyReleaseResources
--console=plain --no-parallel --max-workers=1
```

`hitaDataSmoke` 是仅在临时目录注册的 JavaExec，使用实际生产类和固定时间样例；不是产品任务或新依赖。验证后移除临时源文件及初始化脚本。实时 Grader 会话通过进程环境变量传入，没有写入文件或日志。

|模块|测试数|失败/错误|跳过|
|---|---:|---:|---:|
|app|895|0|1|
|component|7|0|0|
|hitauser|4|0|0|
|timetable-core|57|0|0|

跳过项为现有 `HoaMultiProjectCompatibilityTest.all indexed multi project repositories support structured preview ops`，不计作通过。

生产数据冒烟实际通过：

- 手动待办旧日期按 23:59、显式时分、磁盘重新加载、完成及撤销、无日期保留、UTC 转北京时间跨日。
- 固定基点 `01dccf7` 的旧策略拒绝只有网关票据的研究生落点；当前策略接受正确落点及有效身份样例，拒绝错误角色、错误来源，并阻止本科会话继承。
- Grader 截止时刻相等/刚过期、未发布过滤、重复同步、磁盘重载、完成与撤销、账号切换隔离。
- 实际生产 Grader 客户端通过授权会话只读获取 66 项任务，核验时显示 15 项未过期；完成与撤销仅在进程内的本地存储适配器验证，未向网站写入。
- 新增的仓库取消回归用例在首次状态发布暂停时取消认证，确认同步状态释放，未触发存储写入。

在独立检出的本任务快照中运行 `python scripts/check_repository.py` 通过。没有生成 APK；未执行任何 assemble、package APK 或设备测试任务。

### 轮次、环境限制与待人工验收

共使用四轮修改—验证：第一轮修正 WebView 进度属性接收者的编译错误；第二轮主源码编译通过，但共享构建目录被另一进程写入，字节码转换失败；第三轮独立快照主源码编译通过，修正临时脚本使用了 Android 编译类路径不提供的 Java 11 Files 方法；第四轮上述编译、JVM 测试、资源检查和生产数据冒烟全部通过。

原工作区的完整仓库检查命中用户原有 `OptionalTabStore.kt` 的数据层到 UI 引用，未擅自修改。额外运行的 `python scripts/test_check_repository.py` 共 22 项，其中一项因 Windows 不具备符号链接创建权限而报 WinError 1314；没有提升权限或修改这项现有测试。

Android 实际界面、应用内 WebView 全程交互、真实威海研究生账号登录尚未验证，仍需用户后续安排。不得将离线身份样例或浏览器中的 Grader 登录验证描述为这些设备验收已完成。
