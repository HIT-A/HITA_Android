# AGENTS.md

This file provides guidance to Codex (OpenAI Codex CLI) and other agents that follow the AGENTS.md convention when working with code in this repository.

> 本文件是 [CLAUDE.md](CLAUDE.md) 的 Codex 适配副本，内容与 CLAUDE.md 一致，仅将助手称谓改为 Codex。修改项目说明时请同步更新两份文件。Codex 读本文件；Claude Code 以 CLAUDE.md 为准；OMP 同深度会发现二者之一，内容相同，读哪份均可。

## Project Overview

HITA Agent is an Android app for Harbin Institute of Technology (HIT) students across three campuses (深圳/本部/威海). It provides timetable management, grade queries, course resources, and an AI assistant based on ReAct framework. Package name: `cn.limpu.hita`.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew testDebugUnitTest      # Unit tests
./gradlew connectedAndroidTest   # Instrumentation tests (requires device/emulator)
./gradlew testClasses            # Alias for testDebugUnitTest
```

**Environment**: JDK 21, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.2.21; target SDK 35; minimum SDK 26 for `app`, 23 for `hitauser` (Room 2.8.4), and 21 for `component`/`style`. Compile SDK is 36 for `app` and 35 for `hitauser`, `component`, and `style`; install Android SDK Platforms 35 and 36. Set `JAVA_HOME` locally; do not commit a machine-specific JDK path. The maintained setup and build instructions are in [README_DEV.md](README_DEV.md).

**Dependencies**: Plugin and library versions are maintained in [gradle/libs.versions.toml](gradle/libs.versions.toml), preserving module-specific versions and dependency scopes. [settings.gradle](settings.gradle) configures the remote repositories (Aliyun, Tencent, JitPack, and Grails). Maven local is disabled by default for both plugins and libraries; opt in with `-Phita.useMavenLocal=true` to put it first in both repository lists. CI uses the default remote-only configuration; keep personal opt-in settings in `~/.gradle/gradle.properties`.

## Architecture

### Multi-Module Structure

```
HITA_Agent/
├── app/          # Main application — UI, business logic, agent system, data layer
├── component/    # Shared base classes: DataState, Result, BaseWebSource, ApiResponse
├── hitauser/     # User module — auth, profile, login, isolated Room DB (UserDatabase)
├── style/        # Base UI classes (BaseActivity, BaseFragment, BaseListAdapter), widgets, theme tools
└── timetable-core/ # JVM-only sharing protocol and timetable comparison domain
```

`settings.gradle` includes: `app`, `hitauser`, `component`, `style`, `timetable-core`. The app consumes the core JAR; Room entity adapters remain in `app/data/repository/`. Core production classpaths reject Android artifacts and dependencies back to application modules.

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
│   ├── dao/         # Room DAOs (TimetableDao, TimetableGroupDao, TimetableShareDao, FriendTimetableDao, SubjectDao, EventItemDao, ChatSessionDao, etc.)
│   ├── preference/  # SharedPreferences wrappers
│   └── web/         # Retrofit services + HTML parsers (Jsoup)
│       ├── eas/     # EAS (教务系统) parsers: BenbuEASWebSource, WeihaiEASWebSource
│       └── service/ # Retrofit service interfaces
└── work/            # WorkManager workers (CourseReminder, ScoreReminder)
```

Room databases: `AppDatabase` (app module, currently version 17 — v15 added timetable sharing tables `timetable_share_identity`/`friend_timetable`, v16 added timetable groups via `timetable_group` + `timetable.groupId`, v17 grouped legacy manual course batches), `UserDatabase` (hitauser module), and the independent `UsageQueueDatabase` analytics queue. Keep historical exported schemas for migrations. See [analytics v2](docs/analytics-v2.md).

### Agent / AI System (ReAct Framework)

The AI assistant lives under `app/.../agent/`:

```
agent/
├── core/        # AgentEngine, AgentOrchestrator, AgentTool (interface), AgentToolRegistry
├── llm/         # LlmClient, LlmChatService, ReactPromptBuilder (configured model providers)
├── remote/      # AgentBackendClient (agent-backend HTTP), PrServerClient (course resource HTTP)
├── tools/       # Chat ReActTool interface, ReActToolRegistry, and concrete tools
├── subject/     # Subject README agent (course detail fetcher)
└── timetable/   # Timetable agent (local timetable queries + calendar add)
```

**Chat attachments**: PDF, DOCX, XLSX, PPTX and TXT attachments are currently copied with a byte cap (`cacheChatAttachment` in `ChatAttachmentFile.kt`) and parsed inside `AgentChatViewModel` (`ui/main/agent/`); there is no `agent/document` package or `FileParserDispatcher`. Extracting a dedicated attachment processor is a planned refactor, not current structure.

**Chat ReAct path**: `LlmChatService.chat()` calls `localReAct()` to run the reasoning/tool loop on Android. Chat tools implement `ReActTool`, are registered in `ReActToolRegistry`, receive `ReActToolInput`, and return textual observations. Model requests go through `LlmClient` and the configured provider. The chat entry point currently does not use the backend ReAct loop.

**Typed agent path**: `TimetableAgentEngine` and `SubjectReadmeAgentEngine` implement `AgentEngine`. Their tools implement `AgentTool<I, O>` and are registered in `AgentToolRegistry`. `AgentOrchestrator` delegates execution to the engine; `AgentToolExecutor` handles timeouts, retries, and result callbacks. These interfaces are distinct from the chat `ReActTool` interface.

**Tool data sources**: Local timetable capabilities use the Android data layer, and academic queries use `EASRepository`. Course resource queries use `PrServerClient`; search, RAG, and crawling use `AgentBackendClient`.

### Timetable Sharing & Comparison

- **Protocol**: `timetable-core/src/main/kotlin/cn/limpu/hita/feature/timetableshare/protocol/` — offline HITA1 token codec (Base64URL + GZIP + compact JSON), `StrictShareJson` bounded lexical validation, `ShareLimits` resource caps, FULL/BUSY privacy modes. No backend, no encryption or signing.
- **Comparison**: `timetable-core/src/main/kotlin/cn/limpu/hita/feature/timetablecompare/` — pure-Kotlin `TimetableComparisonEngine` (common free time / same-slot conflicts); standalone UI under `ui/main/timetable/compare/`.
- **Repositories**: `TimetableSharingRepository` (snapshot build), `FriendTimetableRepository` (transactional friend import/confirm), `TimetableExportRepository` (ICS/token export; oversized multi-token share falls back to a `.txt` attachment), `SharedTimetableImporter` (bounded friend token/ICS → entities, 8 MiB cap), `TimetableComparisonRepository` (transactional reads), `DisplayScopeResolver` (display target — single timetable or group — driving main grid, timeline, widgets, reminders and Agent queries), `TimetableSnapshotStore`/`TimetableSourceApplier` (per-account EAS refresh snapshots and adoption preserving local rename/group).
- Feature docs: [timetable-sharing](docs/timetable-sharing.md), [timetable-comparison](docs/timetable-comparison.md), [timetable-display-management](docs/timetable-display-management.md).

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

`./project_health_check.sh` validates required project files and repository hygiene. `./code_consistency_check.sh` runs the same hygiene gate; it does not assign arbitrary scores to names or constants. CI schedules JVM tests for all four Android modules and `:timetable-core:test :timetable-core:jar`, builds the app Debug APK, and runs all four modules' instrumentation tests serially on an API 35 emulator. JVM XML and device XML retain their module paths; `style` currently has real device tests and no JVM test sources, so its `NO-SOURCE` JVM task is not test evidence. CI explicitly runs `:style:verifyReleaseResources :style:assembleRelease` with `--rerun-tasks --no-build-cache`; keep Release resource verification enabled. This library build does not require app Release signing. Local Markdown file links, runtime databases, APKs and stale build artifacts are checked by `python3 scripts/check_repository.py`; Room JSON schemas and source fonts are retained.

The same checker rejects textual `cn.limpu.hita.ui.` references in Kotlin/Java sources under `app/src/main/java/cn/limpu/hita/data/` and `timetable-core/src/main/`; the original app protocol/comparison paths remain guarded if files are reintroduced there. Historical exceptions in `scripts/layer_allowlist.txt` match an exact source path plus reference; remove the entry when its reference disappears. PR CI compares exceptions with the PR base commit and rejects additions or replacements. Run `python3 scripts/check_repository.py --base-ref origin/main` locally after fetching; see [README_DEV.md](README_DEV.md) for scope and baseline initialization.

PDF resources come from the pinned PDFBox Android dependency. Initialize `PDFBoxResourceLoader` in `HApplication` before parsing; do not reflect into internal CMap caches or re-copy library assets. Chinese PDF regression tests use predefined GB1/CNS1 CMaps without embedded ToUnicode shortcuts; run `PdfResourceInstrumentedTest` on a device after changing PDF dependencies or packaging.

## Development Workflow

### 每次会话开始时 — Codex 必须执行

先拉取远端（`origin` 即 `HIT-A/HITA_Android`）：

```bash
git fetch origin
git log HEAD..origin/main --oneline
```

有差异 → 报告用户，等用户决定 merge/rebase 后再继续。

### 每次会话开始时 — 用户提供

告诉 Codex：
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
| 2. 出方案 | 列出实现路径和取舍，等你确认 | Codex（可选，大功能建议走） |
| 3. 写代码 | 按方案实现，修改最少文件 | Codex |
| 4. Review | 让 Codex 检查自己写的代码有没有问题 | Codex |
| 5. 构建验证 | `./gradlew assembleDebug` 确保能编译 | 你或 Codex |
| 6. Commit | 一个功能一个 commit，conventional commits 格式 | 你确认后 Codex 执行 |

### 分支规范
```
feat/xxx        # 新功能
fix/xxx         # bug 修复
refactor/xxx    # 重构（不改行为）
```
一个分支只做一个功能。合完就删。不要直接在 main 上改。

### 提交与推送纪律

- **Commit 前必须先问用户确认**，不得自动 commit
- **Push 前必须先问用户确认**（包括 origin 和 fork），不得自动 push
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
   - 若本版升了 `AppDatabase.VERSION`，把新版本号加入 `AppDatabaseUpgradePathTest.SHIPPED_VERSIONS`（升级测试覆盖所有已发布旧版本）
2. `git commit` + `git push origin` + `git push fork`（`fork` 远端为 `Chami537/HITA_Agent`，本地未配置时先 `git remote add fork git@github.com:Chami537/HITA_Agent.git`）
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
- **数据库迁移**：Room schema 变更需要升 `AppDatabase.VERSION` + 在 `ALL_MIGRATIONS` 中注册 migration，否则老用户安装会 crash（没有破坏性回退；`AppDatabaseMigrationChainTest` 会拦下迁移链缺口）
- **主线程网络请求**：所有 EAS/Retrofit 调用必须在协程里，不要在主线程做网络 IO
- **Hardcoded 中文**：UI 文字放 `strings.xml`（当前仅中文 `values/`，无英文资源）
