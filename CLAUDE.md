# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

HITA Agent is an Android app for Harbin Institute of Technology (HIT) students across three campuses (深圳/本部/威海). It provides timetable management, grade queries, course resources, and an AI assistant based on ReAct framework. Package name: `cn.limpu.hita`.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew testDebugUnitTest      # Unit tests
./gradlew connectedAndroidTest   # Instrumentation tests (requires device/emulator)
./gradlew testClasses            # Alias for testDebugUnitTest
```

**Environment**: JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.2.21; target SDK 35 and minimum SDK 26. Compile SDK is 36 for `app` and 35 for `hitauser`, `component`, and `style`; install Android SDK Platforms 35 and 36. Set `JAVA_HOME` locally; do not commit a machine-specific JDK path. The maintained setup and build instructions are in [README_DEV.md](README_DEV.md).

**Maven mirrors**: All repos use Chinese mirrors (Aliyun, Tencent, JitPack) — see `build.gradle`.

## Architecture

### Multi-Module Structure

```
HITA_Agent/
├── app/          # Main application — UI, business logic, agent system, data layer
├── component/    # Shared base classes: DataState, Result, BaseWebSource, ApiResponse
├── hitauser/     # User module — auth, profile, login, isolated Room DB (UserDatabase)
├── style/        # Base UI classes (BaseActivity, BaseFragment, BaseListAdapter), widgets, theme tools
├── sync/         # Data sync (legacy, not actively included in settings.gradle)
```

`settings.gradle` includes: `app`, `hitauser`, `component`, `style`.

### Dependency Injection: Hilt

- `app/di/` contains `AgentModule`, `DatabaseModule`, `RepositoryModule`
- `hitauser/di/HitaUserModule` provides user-related dependencies
- `@Singleton` scope via `SingletonComponent`
- Fragment/Activity inject via `@AndroidEntryPoint`

### Data Layer Pattern (MVVM + Repository)

```
data/
├── model/           # Data classes (course, score, timetable, chat, resource, etc.)
├── repository/      # Business logic & data coordination
├── source/
│   ├── dao/         # Room DAOs (ChatDao, SubjectDao, TimetableDao, EventItemDao)
│   ├── preference/  # SharedPreferences wrappers
│   └── web/         # Retrofit services + HTML parsers (Jsoup)
│       ├── eas/     # EAS (教务系统) parsers: BenbuEASWebSource, WeihaiEASWebSource
│       └── service/ # Retrofit service interfaces
└── work/            # WorkManager workers (CourseReminder, ScoreReminder)
```

Room databases: `AppDatabase` (app module), `UserDatabase` (hitauser module), and the independent `UsageQueueDatabase` analytics queue. Keep historical exported schemas for migrations. See [analytics v2](docs/analytics-v2.md).

### Agent / AI System (ReAct Framework)

The AI assistant lives under `app/.../agent/`:

```
agent/
├── core/        # AgentEngine, AgentOrchestrator, AgentTool (interface), AgentToolRegistry
├── llm/         # LlmClient, LlmChatService, ReactPromptBuilder (configured model providers)
├── remote/      # AgentBackendClient (agent-backend HTTP), PrServerClient (course resource HTTP)
├── tools/       # Chat ReActTool interface, ReActToolRegistry, and concrete tools
├── subject/     # Subject README agent (course detail fetcher)
├── timetable/   # Timetable agent (local timetable queries + calendar add)
└── document/    # File parsers: PDF, DOCX, XLSX, PPTX, TXT — Strategy pattern via FileParserDispatcher
```

**Chat ReAct path**: `LlmChatService.chat()` calls `localReAct()` to run the reasoning/tool loop on Android. Chat tools implement `ReActTool`, are registered in `ReActToolRegistry`, receive `ReActToolInput`, and return textual observations. Model requests go through `LlmClient` and the configured provider. The chat entry point currently does not use the backend ReAct loop.

**Typed agent path**: `TimetableAgentEngine` and `SubjectReadmeAgentEngine` implement `AgentEngine`. Their tools implement `AgentTool<I, O>` and are registered in `AgentToolRegistry`. `AgentOrchestrator` delegates execution to the engine; `AgentToolExecutor` handles timeouts, retries, and result callbacks. These interfaces are distinct from the chat `ReActTool` interface.

**Tool data sources**: Local timetable capabilities use the Android data layer, and academic queries use `EASRepository`. Course resource queries use `PrServerClient`; search, RAG, and crawling use `AgentBackendClient`.

### Network Clients

- **PrServerClient**: GitHub HOA repo interactions (course resources, README, PR submission)
- **AgentBackendClient**: AI tool backend (Bocha search, RAG, web crawling)
- **EASWebSource**: 教务系统 HTML scraping with Jsoup (BenbuEASWebSource for 本部, WeihaiEASWebSource for 威海)
- Retrofit services defined in `data/source/web/service/`

### UI Layer

- Compose + ViewBinding hybrid: UI is primarily Compose (Material3, 50+ files) hosted in Fragment shells via `ComposeViewBinding`
- Fragments extend `BaseFragment` / `BaseFragmentClassic` from style module
- Adapters extend `BaseListAdapter` / `BaseListAdapterClassic`
- Markdown rendering via Markwon library

### Key BuildConfig Fields

Defined in `app/build.gradle`:
- `HOA_BASE_URL` — course resource server
- `AGENT_BACKEND_BASE_URL` — AI agent backend
- `HOA_API_KEY` — API key (empty in code, injected at build)

## Coding Conventions

- Kotlin style: `kotlin.code.style=official`
- Package prefix: `cn.limpu.hita` (app), `com.limpu.component`, `com.limpu.hitauser`, `com.limpu.style`
- Commit messages follow conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `perf:`, `test:`, `chore:`
- Null safety: prefer `?.` and `?.let{}` over `!!`
- Coroutines: use `viewModelScope.launch`, never `GlobalScope`
- Room schema exports to `app/schemas/`
- ProGuard enabled for release builds (`minifyEnabled true`, `shrinkResources false`)

### Repository and PDF checks

`./project_health_check.sh` validates required project files and repository hygiene. `./code_consistency_check.sh` runs the same hygiene gate; it does not assign arbitrary scores to names or constants. CI also runs JVM tests and Debug assembly. Local Markdown file links, runtime databases, APKs and stale build artifacts are checked by `python3 scripts/check_repository.py`; Room JSON schemas and source fonts are retained.

PDF resources come from the pinned PDFBox Android dependency. Initialize `PDFBoxResourceLoader` in `HApplication` before parsing; do not reflect into internal CMap caches or re-copy library assets. Chinese PDF regression tests use predefined GB1/CNS1 CMaps without embedded ToUnicode shortcuts; run `PdfResourceInstrumentedTest` on a device after changing PDF dependencies or packaging.

## Development Workflow

### 每次会话开始时 — Claude 必须执行

先拉取上游：

```bash
git fetch upstream
git log HEAD..upstream/$(git branch --show-current) --oneline
```

有差异 → 报告用户，等用户决定 merge/rebase 后再继续。

### 每次会话开始时 — 用户提供
告诉 Claude：
1. 要做什么功能（一句话目标）
2. 涉及哪些文件/模块（如果知道的话）
3. 是先出方案再写代码，还是直接动手

### 功能开发流程
```
明确需求 → [出方案] → 写代码 → Review → 构建验证 → Commit
```

| 步骤 | 做什么 | 谁的职责 |
|------|--------|----------|
| 1. 明确需求 | 描述功能：数据来源、展示形式、边界情况 | 你 |
| 2. 出方案 | 列出实现路径和取舍，等你确认 | Claude（可选，大功能建议走） |
| 3. 写代码 | 按方案实现，修改最少文件 | Claude |
| 4. Review | 让 Claude 检查自己写的代码有没有问题 | Claude |
| 5. 构建验证 | `./gradlew assembleDebug` 确保能编译 | 你或 Claude |
| 6. Commit | 一个功能一个 commit，conventional commits 格式 | 你确认后 Claude 执行 |

### 分支规范
```
feat/xxx        # 新功能
fix/xxx         # bug 修复
refactor/xxx    # 重构（不改行为）
```
一个分支只做一个功能。合完就删。不要直接在 main 上改。

### 提交与推送纪律

- **Commit 前必须先问用户确认**，不得自动 commit
- **Push 前必须先问用户确认**（包括 origin 和 upstream），不得自动 push
- 即使用户说 "修好了就提交"，也应该在修改完成后展示 diff 摘要，等用户说 "commit" 再执行

### 提交前检查
- [ ] `./gradlew assembleDebug` 通过
- [ ] 改动限定在功能范围内，没有顺手改无关文件
- [ ] 删掉调试用的 log / Toast
- [ ] 硬编码的字符串优先放 `strings.xml`
- [ ] Commit message 说明「做了什么、为什么」

### Release 发布

**触发条件**：用户明确说 "发 release" 时才做。

**流程**：
1. 更新 `app/build.gradle` 中的 `appVersionCodeValue` 和 `appVersionNameValue`，并同步 `README.md` 的版本徽标与「最新版本」文字
2. `git commit` + `git push origin` + `git push upstream`
3. 两侧创建 release + 上传 APK（注意加版本化文件名）：
```bash
gh release create v<version> --repo Chami537/HITA_Agent --target <branch> --title "v<version>" --notes "..."
gh release create v<version> --repo HIT-A/HITA_Android --target <branch> --title "v<version>" --notes "..."
gh release upload v<version> app/build/outputs/apk/release/app-release.apk#HITA_v<version>.apk --repo Chami537/HITA_Agent
gh release upload v<version> app/build/outputs/apk/release/app-release.apk#HITA_v<version>.apk --repo HIT-A/HITA_Android
```

**Release notes 模板**：
```
## 新增
- xxx

## 修复
- xxx

## 优化
- xxx
```
简洁为上，每项一行，说明做了什么即可。不写技术实现细节。

### 常见陷阱
- **EAS HTML 解析**：三校区（深圳/本部/威海）的 HTML 结构不同，改 EAS 相关代码要同时确认三校区
- **数据库迁移**：Room schema 变更需要升 `@Database` version + 写 migration，否则老用户安装会 crash
- **主线程网络请求**：所有 EAS/Retrofit 调用必须在协程里，不要在主线程做网络 IO
- **Hardcoded 中文**：UI 文字放 `strings.xml`（当前仅中文 `values/`，无英文资源）
