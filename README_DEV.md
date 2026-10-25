# HITA Agent 开发指南

## 项目简介
HITA Agent 是一款面向哈工大三校区的 Android 校园助手 App，集成课表管理、成绩查询、课程资源、AI 助手等功能。

## 技术栈
- **开发语言**：Kotlin
- **最低 SDK**：26 (Android 8.0)
- **目标 SDK**：35 (Android 15)
- **编译 SDK**：`app` 为 36 (Android 16)，`hitauser` / `component` / `style` 为 35
- **构建工具**：Gradle 8.11.1, AGP 8.10.1, Kotlin 2.2.21
- **JDK**：21（AndroidLiquidGlass/backdrop 为 Java 21 字节码，运行单元测试需要 JDK 21；JDK 17 只能编译）
- **架构**：MVVM + Repository，Hilt 依赖注入，Room 数据库，WorkManager 定时任务
- **UI**：Jetpack Compose（Material3）与 ViewBinding 混合，Markwon 渲染 Markdown
- **网络**：Retrofit + Jsoup（教务系统 HTML 解析），OkHttp

## 环境要求
- Android Studio 2025.3.2 或更高
- JDK 21
- Android SDK Platform 35 和 36（应用最低支持 API 26）

## 项目结构

```
HITA_Agent/
├── app/           # 主应用模块 — UI、业务逻辑、数据层、Agent 系统
├── component/     # 共享基础组件 — DataState、Result、SharedPreferenceLiveData
├── hitauser/      # 用户模块 — 认证、个人信息、独立 Room DB
├── style/         # UI 基础 — BaseActivity/Fragment、自定义 Widget、主题工具
└── sync/          # 数据同步（暂未编译进主工程）
```

详见 `CLAUDE.md` 了解完整架构。

## Agent 调用路径

- **聊天助手**：`LlmChatService.chat()` 调用 `localReAct()`，在 Android 端执行推理与工具调用循环；模型请求通过 `LlmClient` 发往配置的提供商。聊天工具实现 `ReActTool`，由 `ReActToolRegistry` 注册，接收 `ReActToolInput` 并返回文本观察结果。
- **课表与课程资料智能体**：`TimetableAgentEngine` / `SubjectReadmeAgentEngine` 实现 `AgentEngine`，工具实现泛型 `AgentTool<I, O>`，由 `AgentToolRegistry` 注册；`AgentOrchestrator` 将调用委托给引擎，`AgentToolExecutor` 处理超时、重试和结果回调。这套接口与聊天用 `ReActTool` 不同。
- **数据来源**：课表等本地能力使用 Android 数据层，教务查询使用 `EASRepository`；课程资源使用 `PrServerClient`，搜索、RAG 和网页抓取使用 `AgentBackendClient`。聊天入口当前未启用后端 ReAct 循环。

## 构建

```bash
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK
./gradlew testDebugUnitTest      # 单元测试
./gradlew connectedAndroidTest   # 仪器测试（需连接设备/模拟器）
./project_health_check.sh        # 项目文件与仓库卫生检查
```

- 所有 Maven 仓库走国内镜像（阿里云 / 腾讯 / JitPack），见 `build.gradle`
- Debug 包使用独立 applicationId `cn.limpu.hita.diag`（应用名"HITA 诊断版"），可与正式版共存安装
- Release 签名需要本地配置 `keystore.properties`（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）及对应的 `.jks` 文件，二者均被 gitignore，不要提交

## 关键 BuildConfig

在 `app/build.gradle` 中定义：
- `HOA_BASE_URL` — 课程资源后端
- `AGENT_BACKEND_BASE_URL` — AI Agent 后端
- `HOA_API_KEY` — 课程资源 API 密钥（代码中为空，构建时注入）
- `UPDATE_URL` / `UPDATE_VERSION_NAME` / `UPDATE_VERSION_CODE` / `UPDATE_LOG` / `UPDATE_ALLOW_PRERELEASE` — 应用内检查更新
- `DEEPSEEK_API_KEY` / `ZHIPU_API_KEY` — LLM 提供商密钥（本地构建留空时走默认配置）

## 权限

- `INTERNET` / `ACCESS_NETWORK_STATE`
- `POST_NOTIFICATIONS`（Android 13+）
- `REQUEST_INSTALL_PACKAGES`（应用内更新）

## 贡献

1. Fork 项目
2. 创建功能分支 (`git checkout -b feat/xxx`)
3. 提交 (`git commit -m 'feat: xxx'`)
4. 推送到分支并开启 PR

## 联系方式

- 问题反馈：[Issues](https://github.com/HIT-A/HITA_Android/issues)
- 邮箱：2720649216@qq.com、859635282@qq.com、2916118707@qq.com

## 常见陷阱

- **EAS HTML 解析**：三校区（深圳/本部/威海）页面结构不同，改动解析逻辑需同时验证三校区
- **数据库迁移**：Room schema 变更必须升 `@Database` version 并写 migration，否则老用户升级崩溃
- **主线程网络**：所有 EAS / Retrofit 调用必须在协程中执行
- **硬编码中文**：UI 文案放 `strings.xml`（当前仅中文 `values/`）

## 文档与构建产物

完整架构与开发约定见 `CLAUDE.md`（Codex 适配副本为 `AGENTS.md`，内容一致）。使用统计契约和验证范围见 [统计 v2](docs/analytics-v2.md)。课表分享、双人对比与课表管理重构的交付说明见 [docs/timetable-sharing.md](docs/timetable-sharing.md)、[docs/timetable-comparison.md](docs/timetable-comparison.md) 和 [docs/timetable-display-management.md](docs/timetable-display-management.md)。设计说明保留在 `docs/superpowers/specs/`；已完成的用户反馈实施清单已从当前树移除，未实施的字体计划保留。

APK 和构建元信息从 CI / Release 获取；源码树不保存旧 `app/debug/` 安装包。`app/schemas/` 和 `hitauser/schemas/` 是 Room 数据库历史结构，供迁移验证使用，不能作为旧生成数据删除。

运行 `./project_health_check.sh` 检查必要项目文件和仓库卫生，`./code_consistency_check.sh` 为兼容入口。检查器验证本地 Markdown 文件链接，并阻止运行数据库、APK 和旧可执行文件重新进入 Git；它不检查外部链接和标题锚点，不使用命名数量作为质量评分。JVM 测试、Debug 编译和全部设备测试（API 35 模拟器，含中文 PDF 回归）由 CI 执行。

PDF 依赖自带资源的约定、去重依据和回归命令见 [PDF 资源](docs/pdf-resources.md)。

### 分层依赖检查

同一检查器还禁止以下目录中的 `.kt` / `.java` 主源码引用 `cn.limpu.hita.ui.`，路径均相对 `app/src/main/java/cn/limpu/hita/`：

- `data/`
- `feature/timetableshare/protocol/`
- `feature/timetablecompare/`

这是源码文本检查，覆盖 import、别名 import、Java static import、通配符 import 和完全限定名，也检查注释及字符串中的完整引用；不解析 Kotlin/Java 语义或间接依赖。范围外源码和测试目录不受此规则限制。与其他仓库检查一致，扫描已跟踪文件及未被 Git 忽略的新文件。

历史依赖暂记在 [scripts/layer_allowlist.txt](scripts/layer_allowlist.txt)：每行是「仓库相对路径 + 空白 + 完整引用」，空行和以 `#` 开头的整行注释允许保留。匹配不绑定行号，也不按前缀或通配符放行。同文件新增其他 UI 引用仍然失败。清理引用或删除文件时必须同步删除例外；重复、无效、过期例外均会失败。

```bash
python3 scripts/test_check_repository.py
python3 scripts/check_repository.py                       # 检查当前文件与例外是否一致
git fetch origin
python3 scripts/check_repository.py --base-ref origin/main # 另外校验例外只能删减
./project_health_check.sh
./code_consistency_check.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

PR CI 会获取并使用该 PR 的 base SHA，要求当前例外集合是基线名单的子集，新增、替换例外均失败。首次引入名单时若基线尚无名单，仅允许基线源码中已存在的精确引用；空名单不会触发重新初始化。基线获取或读取失败会报错，不会跳过比较。普通检查命令和 push CI 仅检查当前状态，历史集合比较由 `--base-ref` 显式启用。
