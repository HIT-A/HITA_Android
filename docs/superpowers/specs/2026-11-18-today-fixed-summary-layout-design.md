# 今日页固定摘要与课表切换布局设计

日期：2026-11-18；分支：`feat/ios-parity-migration`；Android 基线：`32449de6`；状态：三项取舍已确认，代码已实施，主要真机回归已补；剩余边界见验证记录。

实施进展（同日）：已按本文接入固定/紧凑摘要、独立课程窗口、历史折叠和共享课表选择，详见[实施记录](../../audit/2026-11-18-today-fixed-layout.md)。用户先要求代码修正，随后连接设备完成 47 项真机测试，详见[真机验证记录](../../audit/2026-11-18-today-device-regression.md)；第 12 节保留设计文档初次交付时的历史范围。

依据：设计交付包 `/home/rinat/projects/HITA-artifacts/today-layout-handoff-2026-11-18.zip`（HTML 原型与示例数据，不是真机验收），以及[视觉重做方案](2026-11-18-today-visual-redesign.md)和它的[实施记录](../../audit/2026-11-18-today-redesign.md)。标注“实测”的尺寸来自实施记录里的真机布局导出 `/home/rinat/projects/HITA-artifacts/today-redesign-2026-11-18/main-final.xml`（PJZ110，360×792dp，手势导航，字号 1.0）；其余尺寸按代码常量估算。

## 1. 目标与已定决策

交付包 README 列出的六条方向中，三条布局方向全部采纳：

1. 课程区是页面里唯一常驻的纵向滚动区域。
2. 考试、作业摘要固定在底栏上方，滚动课程时位置不变。
3. 点标题行里的课表名，用底部面板选择单课表或课表组。选中范围与课表页共用。

另外三条（保留日期、“今日”和账号入口及居中问候；24dp 课程卡、色条、地点胶囊和细进度条；短状态文案）与现有实现一致，本方案只修正它们的行高和进度条细节。

已确认的取舍：

| 问题 | 决定 |
|---|---|
| 考试卡统计范围 | 11 月 18 日真机反馈修订：统计当前所选课表的全部有效考试，显示待参加数量、最近考试日期和已结束数量；不随今日课程窗口截断 |
| 列表默认显示 | 进行中和之后的全部安排；已结束的折叠成一行“已结束 N 项” |
| 紧凑摘要 | 本轮实现。矮屏和大字号先换紧凑摘要，还放不下才退回整页滚动 |

本方案实施后，以下旧规则以本文为准：

- [11-16 设计](2026-11-16-today-page-optimization-design.md) §4.2 的“默认只显示进行中和下一组”。下一组仍用于问候区文案。
- [11-18 视觉方案](2026-11-18-today-visual-redesign.md)里标题旁的“全部 N 项 / 收起”，以及“摘要排在安排之后、随页面滚动”。卡片比例、配色和短文案原则不变。
- 作业卡内的“重新读取”按钮移到作业页（作业页已有重试入口），见 §3.4。

## 2. 现状问题

| # | 问题 | 依据 |
|---|---|---|
| 1 | 首屏摘要被底栏挡住 | 实测：摘要卡 611–773dp，悬浮底栏顶边 716dp，底部约 57dp 被盖住，“待查询”“Grader：未连接”看不到 |
| 2 | 小字行高偏大 | 约 14 处文字只设了 11–14sp 字号，沿用 `bodyLarge` 的 24sp 行高，实测这些行都正好 24dp。162dp 的摘要卡里有 72dp 是这类行 |
| 3 | 标题行太挤 | 实测“正在进行”78dp、“全部 4 项”93dp、课表名 79dp。课表名改成带箭头的胶囊约 128dp，加上间距约 315dp，可用宽度只有 320dp，名称稍长或字号 ≥1.1 就会换行 |
| 4 | 底部留白写死 | `TodayScreen` 用 `dockBarChromePadding().calculateBottomPadding() + 128.dp`，不区分悬浮底栏和胶囊底栏，也不随字号变化 |
| 5 | 切换课表后可能回不到原滚动位置（按代码推断，未实测） | 切换时课表标识先变、课程数据后到。中间这段时间列表只有问候、加载占位和摘要几项，恢复出来的 `LazyListState` 位置会被压到末项，数据到了也回不去 |
| 6 | 摘要高度随数据变化 | 作业来源读取失败时，每个失败来源在卡内追加一个约 48dp 的“重新读取”按钮；来源说明行也可能换成多行 |

## 3. 页面结构

```text
TodayScreen（按可用高度和字号选模式，见 §4）
├─ 问候区     固定。固定模式下不显示“今天还有 N 场考试 / N 项作业待截止”（和摘要重复）
├─ 标题行     「今日安排」 ………… [深圳 2026秋季 ⌄] → 课表选择面板（§6）
├─ 课程窗口   weight(1f)，页面唯一的滚动区，上下边缘渐隐
│   ├─ 读取失败提示（有保留数据时）
│   ├─ 已结束 N 项 ⌄ / 收起
│   ├─ （展开时）已结束的安排
│   ├─ 进行中、未开始、时间待确认的安排
│   ├─ 完成卡 / 明日准备（20:30 门槛不变）
│   └─ 提示卡（TAG）
├─ 固定摘要   完整或紧凑两种；高度只由模式和字号决定
└─ 底部避让   底栏实际占用高度 + 12dp（§5.2）
```

完整模式在实测机上的纵向分配（dp，按 §4.2 公式）：

```text
118  TodayScreen 顶边（上面是状态栏和“今日”顶栏）
118–232  问候区 114
244–292  标题行 48
304–554  课程窗口 250（唯一滚动区）
566–704  固定摘要 138
716  底栏顶边；792 屏幕底边
```

### 3.1 问候区

- 完整模式沿用现有尺寸：标题 34sp/42，副文案 14sp/20，上 24dp、下 20dp，行距 8dp。
- 紧凑模式：标题 28sp/34，副文案 13sp/18，上下各 12dp，行距 6dp。
- 标题只占 1 行。副文案最多 2 行（大字号时允许换行，多出的高度由课程窗口吸收）。
- 整页滚动模式保留紧急提醒行，因为这时摘要可能不在屏幕内。
- 不做“滚动时折叠问候区”。它违反交付包验收第 1 条（滚动时问候、选择器、摘要都不动），还要处理嵌套滚动。

### 3.2 标题行

- 左侧固定显示“今日安排”（19sp/24 粗体），不再按展开状态切换成“正在进行”或“接下来”，问候区已经说明当前状态。
- 右侧是课表胶囊：课表名（13sp/18，单行，过长省略）加下拉箭头。容器沿用现在“全部 N 项”胶囊的颜色和形状，触控区不小于 48dp，整行高 48dp。
- 胶囊的无障碍名称为“切换课表，当前：{课表名}”。
- 课表还在读取或没有课表时不显示胶囊，行高仍保持 48dp，避免页面跳动。
- 课表名在胶囊里只占一行，完整名称在面板里换行显示。

### 3.3 课程窗口

- 默认显示进行中、未开始和时间无效的安排，按开始时间、结束时间、ID 排序。已结束的折叠。
- 有已结束安排时，列表第一项是折叠行“已结束 N 项 ⌄”；展开后文字变成“收起”，已结束的安排按时间顺序排在它下面、当前安排之前。折叠行整行可点，高 48dp。
- 当天全部结束（现有判断 `allFinished && invalidEvents.isEmpty()`）时不显示折叠行。完成卡第一行“今天已结束 N 项安排”加一个箭头，负责展开和收起；展开后的历史接在完成卡后面（与现在一致），明日部分仍然只出现一次。
- 有效安排都已结束、但还有时间无效的安排，并且满足明日准备条件时：显示折叠行和无效安排；明日卡不再重复“今天已结束 N 项安排”那一行。
- 读取中、无课表、读取失败且没有保留数据时，课程窗口只显示对应的占位卡，不创建列表（原因见 §7）。
- 列表横向铺满，左右 20dp 用 `contentPadding` 实现，不给外层加 padding，否则赛博、玻璃等风格卡片的光晕和描边会被左右裁掉。上下内容边距 4dp。
- 边缘渐隐：只在还能向上或向下滚动时，在对应边缘绘制 16dp 渐隐。用离屏图层加 `BlendMode.DstIn` 透明度遮罩实现，不用叠背景色的做法，因为渐变背景下颜色对不上。
- 课程窗口底边离摘要顶边 12dp。滚到底时最后一项完整可见、可以点击。

### 3.4 固定摘要

两张卡并排，整张卡可点，沿用现有路由：考试打开 `ExamActivity`，作业走 `openTodayHomework`。卡内不再放按钮。

| | 完整摘要 | 紧凑摘要 |
|---|---|---|
| 第 1 行 | 图标 + 来源标签（12sp/16） | 来源标签（12sp/16，过长时截断）+ 主值（20sp/24 粗体，不截断），不显示单位 |
| 第 2 行 | 主值 27sp/34 + 单位 12sp/16 | 状态（11sp/16，单行） |
| 第 3 行 | 说明（12sp/16，单行） | — |
| 第 4 行 | 来源状态（11sp/16，单行） | — |
| 内边距 | 16dp，行距 8dp | 横向 14dp、纵向 12dp，行距 4dp |
| 高度（字号 1.0） | 138dp | 68dp |

- 文字内容沿用现在的完整卡（标签、主值、说明、来源）。紧凑卡第 2 行取完整卡的“说明”。作业来源没有全部就绪时，改为显示第一个未就绪来源的状态，例如“Grader 未连接”。
- 固定模式下每行只占一行，超出部分省略。Compose 文本的无障碍文字不受省略影响，读屏仍能读到完整内容。
- 只知道一个作业来源时，标签用来源名（“手写作业”或“Grader”），继续遵守“部分来源未知时不显示总数”的规则。
- 读取失败时只显示“读取失败”，点卡片进入作业页，用作业页现有的重试：本地作业是 `HomeworkViewModel.reload`，Grader 是作业页的刷新和重试。今日页的 `TodayAction.Retry` 只保留 `AGENDA`（课程读取失败卡）这一处入口；`HOMEWORK`/`GRADER` 分支如果因此没有调用方，就按实际情况清理。
- 占位规则：课程数据未就绪（读取中，或读取失败且没有保留数据）时，考试卡照常显示，主值为“—”，说明为“正在读取…”或“读取失败”。作业摘要为空时也显示同样的占位。无课表时只显示作业卡，占满一行。这样切换课表时摘要不会消失，宽度也不变。
- 整页滚动模式下，摘要作为列表末尾的普通项，允许换行，沿用现在“宽度 < 320dp 或字号 > 1.25 时改成单列”的规则。

### 3.5 底部避让

页面根布局底部留出“底栏顶边到窗口底边的距离 + 12dp”。这个距离由主界面和今日页共用的函数计算（§5.2），不再写死 128dp。

## 4. 布局模式

### 4.1 三种模式

| 模式 | 适用情况 | 布局 |
|---|---|---|
| `FIXED_FULL` | 普通手机、字号 ≤ 1.25 | 问候区、标题行、完整摘要固定，课程窗口滚动 |
| `FIXED_COMPACT` | 矮屏、三键导航、字号 1.25–1.6 | 紧凑问候区和紧凑摘要固定，课程窗口滚动 |
| `PAGE_SCROLL` | 横屏、分屏、字号 > 1.6，或以上两种都放不下 | 整页一个列表，即现在的布局，但包含本方案对行高、折叠行和课表胶囊的修改 |

依次尝试完整、紧凑；课程窗口达不到下限就用整页滚动。模式只取决于窗口宽高、底栏占用高度和字号，**不看数据**。读取失败、切换课表、分钟刷新都不会让页面换模式。

### 4.2 判断函数

新增纯函数 `TodayLayoutPolicy.choose(widthDp, heightDp, bottomClearanceDp, fontScale): TodayLayoutMode`，用 JVM 单测覆盖。`heightDp` 是 `TodayScreen` 通过 `BoxWithConstraints` 拿到的可用高度，已经扣掉顶栏。

下表单位都是 dp，`f` 是 `Density.fontScale`，文字行高按 `f` 线性放大。Android 14 起大字号是非线性放大，实际高度会比线性估算小，所以判断结果偏保守。

| 项 | 完整 | 紧凑 | 来源 |
|---|---|---|---|
| 问候区 | 52 + 62f | 30 + 52f | §3.1 的边距和行高 |
| 标题行 | 48 | 48 | 触控高度；标题行高 24f，f ≤ 2 时不超过 48 |
| 摘要 | 56 + 82f | 28 + 40f | §3.4 |
| 区块间距 | 4 × 12 = 48 | 48 | 问候区、标题行、课程窗口、摘要、底栏之间 |
| 横向条件 | f ≤ 1.25，且内容宽 ≥ 320 | f ≤ 1.6，且内容宽 ≥ 280 | 内容宽 = 宽 − 40，保证双列摘要放得下 |

- 课程窗口高度 = 可用高度 − 底栏占用 − 问候区 − 标题行 − 摘要 − 48。
- 课程窗口下限 = 97 + 60f，即一张单行标题的进行中课卡（57 + 60f，修正行高后在字号 1.0 下为 117dp）再露出 40dp，提示下面还能滚动。

### 4.3 各机型估算

实测机的可用高度 674dp、悬浮底栏占用 76dp 来自真机导出。其他机型按状态栏 40dp（640dp 高的机型按 24dp）、三键导航栏 48dp 推算。表内数值单位为 dp，未注明的字号为 1.0。

| 屏幕 | 完整：课程窗口 | 紧凑：课程窗口 | 下限 | 结果 |
|---|---|---|---|---|
| 360×792 手势（实测机） | 250 | 352 | 157 | 完整 |
| 同上，胶囊底栏（占用 64） | 262 | — | 157 | 完整 |
| 390×844 手势 | 302 | — | 157 | 完整 |
| 360×740 手势 | 198 | — | 157 | 完整 |
| 360×640 三键（占用 102） | 88 | 190 | 157 | 紧凑 |
| 360×792，字号 1.3 | 不满足横向条件 | 321 | 175 | 紧凑 |
| 360×792，字号 1.6 | 不满足横向条件 | 291 | 193 | 紧凑 |
| 360×640 三键，字号 1.3 | 不满足横向条件 | 159 | 175 | 整页滚动 |
| 横屏（高约 360） | 负值 | 负值 | — | 整页滚动 |

参考：修正行高后，单行标题课卡约 117dp，两行标题约 143dp，已结束卡约 90dp。实测机在完整模式下的 250dp 能放下一张课卡加第二张的大半；有折叠行时还要再减 60dp。

### 4.4 约束

- 估算按常见内容计算。副文案换行这类少见情况多出的高度，由课程窗口（`weight(1f)`）吸收，不重新选择模式，也不会产生遮挡。
- 旋转屏幕、分屏或修改字号导致换模式时，列表回到顶部，因为两种布局的列表项不同（§7）。
- 第 3 步只开放 `FIXED_FULL` 和 `PAGE_SCROLL`；第 6 步实现紧凑摘要后再开放 `FIXED_COMPACT`。

## 5. 排版与底部高度

### 5.1 行高与进度条（第 1 步）

- 在 `TodayVisualStyle.kt` 新增四组字号/行高成对的样式：11/16、12/16、13/18、14/20（sp），建议命名为 `todayCaption()`、`todayLabel()`、`todayMeta()`、`todayBody()`。样式在 `LocalTextStyle.current` 上合并，保留主题字体和 `LineHeightStyle`（居中、不裁剪）。用它们替换今日页没设行高的约 14 处 `Text`，分布在 `TodayAgenda`、`TodaySummaryCards`、`TomorrowPreparationCard` 三个文件。
- 修改后，摘要卡从 162dp 降到 138dp，进行中课卡从 131dp 降到 117dp。
- 进行中的进度条改为 `LinearProgressIndicator(gapSize = 0.dp, drawStopIndicator = {})`，去掉 Material3 默认的间隙和末端圆点。当前 Compose BOM 2025.09.01 已支持这两个参数。
- 顶栏“今日”：`MainTopBar` 的两行标题区固定 64dp 高（日期 12/16 加标题 28/34），两行合计 50sp，按线性放大计算，字号超过 1.2 后就会超出约 60dp 的内容高度。需要在真机上用字号 1.3 确认。如果确实被裁切，只在 TIMELINE 分支把 `height(64.dp)` 改成 `heightIn(min = 64.dp)`。今日页的可用高度会随之变化，模式判断能自动适应。

### 5.2 统一底部高度（第 2 步）

新增 `ui/main/MainBottomBarClearance.kt`：

```kotlin
// 示意契约，不是已有代码。
internal fun pillBarBottomPadding(navBottom: Dp): Dp            // 三键导航（≥ 32dp）为 nav + 8，否则 12
internal fun pillBarHeight(fontScale: Float): Dp                 // 8 + max(44, 29 + 10f)，对应 MainPillTabBar 的结构
internal fun mainBottomBarClearance(dock: Boolean, navBottom: Dp, fontScale: Float): Dp
    // 悬浮底栏：max(21, nav) + dockBarHeight(10f)
    // 胶囊底栏：pillBarBottomPadding(nav) + pillBarHeight(f)
@Composable internal fun rememberMainBottomBarClearance(): Dp   // 风格取 HitaTheme.preferenceStyle，再经 usesDockTabBar 判断
```

- 同日真机修正：中文字体回退可能让标签实际高度大于 `10f`。运行时用 `rememberPillBarLabelHeight` 按当前主题样式测量标签，再将实测高度传给共用几何函数；底栏按同一高度布局。上面的 `29 + 10f` 为名义估算，不再作为运行时唯一依据。
- `MainScreen` 用 `pillBarBottomPadding` 替换内联的 `systemNavAvoidance`；悬浮底栏继续用 `dockBarChromePadding()`。底栏几何只保留一份。
- 导航栏高度取 Compose `WindowInsets.navigationBars` 和根视图 `ViewCompat.getRootWindowInsets(...)` 两者的较大值，并在全局布局变化时更新。`AgentChatFragment` 已经这样做，说明 Tab 页的 ComposeView 不一定收得到 inset。把这段逻辑提成共用函数；Agent 页本轮不迁移。
- `TodayScreen` 删除 `+128.dp`，并增加 `bottomClearance` 参数（默认取共用函数），测试宿主可以直接传值。整页滚动模式的列表底部内边距为“底栏占用 + 12dp”。
- 今日 Tab 不显示 mini 课程条（`MainScreen` 已经排除 TIMELINE），不用为它预留空间。

## 6. 课表选择

### 6.1 交互

1. 点标题行的课表胶囊，打开 `ModalBottomSheet`，直接完全展开（`skipPartiallyExpanded = true`）。
2. 面板标题为“选择课表”（`today_choose`）。列表按 `DisplayScopeResolver.options` 的顺序列出单课表和课表组：名称可以换行；课表组下面多一行“共 N 份课表”（`timetable_group_member_count`）；当前项同时用勾选图标、主色加粗文字和 `selected` 语义标示。列表过长时在面板内部滚动。
3. 面板底部有一行“课表管理”（`title_timetable_manager`），复用现有的 `TodayAction.SelectTimetable`，打开 `TimetableManagerActivity`。
4. 点某一项后立即发出选择动作并关闭面板；点当前项只关闭面板。返回键、点遮罩、拖动把手都能关闭。
5. 不加横向快捷切换入口（交付包方向第 2 条）。

### 6.2 数据与动作

- `TodayReadSources` 增加 `scopeOptions: Flow<List<ScopeOption>>`，默认返回空列表；`TimetableTodaySources` 用 `resolver.options.asFlow()` 实现。
- `TodayUiState` 增加 `scopeOptions: List<TodayScopeOption>`，其中 `TodayScopeOption(scope, name, memberCount, selected)`。`selected` 在 ViewModel 的同一次 `combine` 里根据当前解析结果算出，保证勾选项和页面正在显示的课表一致。
- 新增动作 `TodayAction.SelectScope(scope: DisplayScope)`。`FragmentTimeLine.handleAction` 处理它时不做 `accepts()` 检查（选择课表与当前快照无关），直接交给 `MainPageController.selectTodayScope(scope): Boolean`；返回 false 时调用现有的 `stale()` 提示。
- `MainActivity.selectTodayScope` 与课表页下拉菜单走同一条路径：`MainViewModel.selectScope(scope)` → `DisplayScopeSelector.select` → 返回目标周的周一 → `TimetableFragment.navigateToWeek`。
- `MainViewModel.selectScope` 改为按 `DisplayScope` 对象在当前 `scopeOptions` 中查找，找不到返回 null。`MainActions.selectTimetable` 的类型改为 `(DisplayScope) -> Unit`，`MainTopBar` 传 `option.scope`，并删除按下标选择的版本。这样面板打开期间选项列表即使变化，也不会选错课表。
- 不新增存储、数据表或偏好设置。选择结果的持久化和联动刷新仍由 `DisplayScopeSelector` 负责。

## 7. 状态保存与滚动恢复

- 继续用 `rememberSaveableStateHolder` 按“课表类型:ID:日期”保存展开状态和滚动位置。分钟刷新、切换 Tab、Activity 重建后都保留；跨日后换成新 key，回到默认状态（折叠、在顶部）。
- `LazyListState` 在状态容器内的最上层创建，固定模式和整页模式各一个，不放进条件分支。
- 课程数据未就绪时不组合列表，只显示占位；数据到了才组合 `LazyColumn`。没有挂到列表上的 `LazyListState` 不会被测量，保存的位置就不会被压到占位项的范围里。这样从 B 切回 A 时，能回到 A 原来的位置。
- 整页滚动模式同样处理：数据未就绪时用普通 `Column` 显示问候区、占位和摘要。
- 旧课表的迟到结果仍由现有的查询键和 `accepts` 检查拦截，本方案不改这部分。

## 8. 不照搬、不做

- 原型的外壳（模拟的状态栏和底栏）、固定的紫色、17MB 的 `chinese.ttf` 不放进仓库，使用应用自己的顶栏、底栏、主题色和字体。
- 原型白天也显示“明日准备”只是示例数据，保留 20:30 门槛。
- 空课表不加“可切换课表查看其他安排”这类解释文字。
- 不做滚动时折叠问候区，不加横向课表快捷入口。
- 考试摘要覆盖当前所选课表的已结束及未来考试；课程窗口仍只展示今日安排和明日准备。作业摘要标题使用“待办”。
- 不改 Room 表结构、隐藏规则、账号与来源隔离、明日准备规则以及问候文案逻辑；Repository 查询额外带回当前范围的全部考试，问候中的“今天还有 N 场考试”仍只计算今天。

## 9. 分步实施

每一步都要通过 `./gradlew :app:assembleDebug`、今日页相关 JVM 测试和设备上的 `TodayUiRegressionSuite.Foreground`，再按仓库约定提交。

### 第 1 步：`refactor(today): pair text sizes with line heights`

- 修改 `TodayVisualStyle.kt`（新增文字样式）、`TodayAgenda.kt`、`TodaySummaryCards.kt`、`TomorrowPreparationCard.kt`，调整进度条参数；根据真机结果决定是否修改 `MainTopBar.kt` 的 TIMELINE 分支。
- 不改结构和文案，可以单独先合入。
- 验证：现有测试保持通过；用真机布局导出核对摘要卡约 138dp、课卡约 117dp。

### 第 2 步：`refactor(main): share bottom bar clearance`

- 新增 `MainBottomBarClearance.kt`，修改 `MainScreen.kt` 和 `TodayScreen.kt`。
- 测试：JVM 新增 `MainBottomBarClearanceTest`，覆盖两种底栏 × 手势/三键导航 × 字号 1.0/1.3/2.0。设备新增 `MainBottomBarGeometryInstrumentedTest`：单独渲染 `DockTabBar` 和 `MainPillTabBar`，在字号 1.0/1.3/1.6 下，实测高度与函数结果相差不超过 1dp。

### 第 3 步：`feat(today): pin summaries above the tab bar`

- 新增 `TodayLayoutPolicy.kt`（此步只返回完整或整页）和 `TodayAgendaList.kt`（课程窗口、渐隐、占位）。
- 修改 `TodayScreen.kt`（按模式组织页面）、`TodayHeader.kt`（固定模式不显示紧急提醒）、`TodaySummaryCards.kt`（单行显示、去掉卡内重读按钮、考试卡占位）。
- 测试：JVM 新增 `TodayLayoutPolicyTest`，覆盖 §4.3 中结果为完整或整页的行。设备新增 `TodayLayoutInstrumentedTest`，并加入 `TodayUiRegressionSuite`，检查以下五项：
  1. 在 360×674dp 宿主里滚动课程，用无障碍节点边界比较，摘要位置不变；
  2. 滚到底后，最后一项的底边不超过摘要顶边；
  3. 横屏高度下使用整页滚动；
  4. 从读取中变为就绪时，摘要高度不变；
  5. A→B→A 切换（中间插入读取中状态）后，回到 A 原来显示的那一项。

### 第 4 步：`feat(today): collapse ended agenda items`

- 修改 `TodayUiState.kt` 和 `TodayProjection.kt`：`TodayAgendaState` 新增 `ended`、`remaining` 两个字段，删除 `defaultVisible`。
- 修改 `TodayAgenda.kt`（折叠行，标题固定为“今日安排”）和 `TomorrowPreparationCard.kt`（完成卡第一行负责展开）。
- `strings.xml` 新增“已结束 %1$d 项”；确认没有其他引用后删除 `today_expand`、`today_active`、`today_upcoming`。
- 测试：更新 `TodayProjectionTest`，覆盖 `ended`/`remaining`，以及时间无效的安排留在 `remaining`。`TodayScreenInstrumentedTest` 里有两条用例点击“全部 N 项”，改为点击折叠行和完成卡第一行，并断言“收起”状态在重建后保留、明日标题只出现一次。

### 第 5 步：`feat(today): switch display scope from the agenda header`

- 新增 `TodayScopePicker.kt`（胶囊和面板）。
- 修改 `TodayAgenda.kt`、`TodayAction.kt`、`TodayUiState.kt`、`TodayReadSources.kt`、`FragmentTimelineViewModel.kt`、`FragmentTimeLine.kt`、`MainActivity.kt`、`MainViewModel.kt`、`MainUiState.kt`、`MainTopBar.kt`、`strings.xml`（新增胶囊的无障碍文字）。
- 测试：
  - 新增 `TodayScopePickerInstrumentedTest`：能打开面板；当前项带 `selected` 语义；选择后发出 `SelectScope(对象)` 并关闭面板；“课表管理”发出 `SelectTimetable`；长名称会换行。
  - `MainViewModelInstrumentedTest` 增加按对象选择：选项重新排序后仍选中正确的对象；已删除的范围返回 null。
  - `TodayViewModelInstrumentedTest` 增加：`scopeOptions` 中的 `selected` 与当前范围一致。

### 第 6 步：`feat(today): compact summary for short screens and large text`

- 修改 `TodaySummaryCards.kt`（紧凑卡；`TodayCard` 增加行距参数）、`TodayHeader.kt`（紧凑问候区）、`TodayLayoutPolicy.kt`（开放紧凑模式）。挑选紧凑卡第 2 行文字的逻辑写成纯函数，放在 `TodayHomeworkCardModel.kt` 旁边。
- 测试：`TodayLayoutPolicyTest` 补全 §4.3 整张表；`TodayVisualPresentationTest` 覆盖紧凑状态行的选择（来源未知、逾期、已知为空）；`TodayLayoutInstrumentedTest` 增加 360×538dp 和字号 1.3 下的紧凑模式检查。

### 第 7 步：`docs(today): record fixed summary layout validation`

- 在 `docs/audit/` 记录构建、测试、真机截图和未验证项。真机安装用 `adb install -r` 保留数据，插桩测试沿用实施记录里的定向 `am instrument` 方式。

最终本地验证：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
python3 scripts/check_repository.py --base-ref origin/main
git diff --check
```

## 10. 验收

交付包 `DESIGN.md` §6 的五条验收分别对应：第 1 条对应 §3、§9 第 3 步；第 2 条对应 §6、§7；第 3 条对应 §3.3、§3.4 的状态规则；第 4 条对应下面的真机清单；第 5 条对应各步回归和最终 MainActivity 截图。

真机清单：

- [ ] 滚动课程前后，问候区、标题行、摘要和底栏的坐标不变；最后一项完整可见、可以点击。
- [ ] A→B→A 切换（包括课表组），能恢复滚动位置；课表页同步跳到对应周；面板勾选项正确。
- [ ] 字号 1.0、1.3、1.6，横屏，分屏，三键导航；悬浮底栏三种风格（经典、苹果玻璃、深空）和胶囊底栏；浅色和深色。
- [ ] 已结束项的折叠与展开、完成卡展开历史、20:30 后的明日准备、读取中、读取失败、无课表。
- [ ] 作业来源读取失败时，卡片显示“读取失败”，点进作业页可以重试。
- [ ] 各风格背景下边缘渐隐显示正常，滚动流畅。

## 11. 风险与需真机确认

- Tab 页 ComposeView 能否拿到导航栏 inset：在三键导航下核对今日页的底部避让是否等于底栏的实际占用。
- 非线性字号：Android 14 以上大字号的实际高度小于估算，模式选择偏保守，可以接受。
- 字号 1.3 下顶栏“今日”标题是否被裁切（§5.1）。
- 渐隐用的离屏图层在玻璃、赛博风格下的显示效果和滚动性能。
- `ModalBottomSheet` 从 Fragment 的 ComposeView 弹出：确认面板能盖住主界面底栏，深色模式和手势返回都正常。

## 12. 本次交付

本次只新增这份设计文档，并在视觉重做方案里加了一行指向本文。没有修改业务代码，也没有运行构建或设备测试。交付包的 `verification.json` 只验证了网页原型和示例数据，不代表 Android 已经实现。
