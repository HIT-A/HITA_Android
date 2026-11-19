# 首页（今日页）改造交接

日期：2026-11-19
分支：`feat/ios-parity-migration`（PR #94，未合并）
工作区：`D:\Project\HITA_Android-ios-parity`（该分支的 worktree；主仓 `HITA_Android-main` 仍停在 `main`）

---

## 一、环境与操作要点

- **JDK**：必须用 JDK 21。系统默认 `JAVA_HOME` 指向 JDK 17，直接跑会失败。
  `C:\Users\JiXing\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2`
- **装机**：debug 包的 `applicationId` 带 `.diag` 后缀，应用名是「HITA 诊断版」，与正式版 3.2.0 并存，数据互不影响。`adb install -r` 覆盖安装即可。
- **adb 两道坑**：手机锁屏时安装会报 `INSTALL_FAILED_ABORTED: User rejected permissions`，先 `input keyevent KEYCODE_WAKEUP` 再装；vivo 每次安装都会弹确认框，需要人点。
- **仪器测试在这台真机上不可用**：`am instrument` 直接 `Process crashed`，是设备限制不是用例失败。验证改用 JVM 单测 + 真机截图。
- **截图命令**：`adb exec-out screencap -p > xxx.png`。截图存在 `.impeccable/screens/`（未跟踪目录）。

---

## 二、工作规范

1. **先调查再动手**。改任何界面或逻辑前，先读实际代码、量实际高度、看真实截图。本次「六节课一屏」的容量估算一开始算错过一次，是画出示意后才纠正的。
2. **不擅自扩大范围**。用户点名删哪个块就只删那个块；想顺手加装饰、补字段、改文案，先问。之前擅自给顶栏日期串加间隔号「·」被指出过，之后一律按字面执行。
3. **提交按功能拆分**，conventional commits（`feat(today):` / `fix(today):`）。提交前说明改了什么、哪些验证还没做。
4. **push 单独确认**。commit 授权不含 push，本次全程未推送。
5. **验证链**：`assembleDebug` + `assembleDebugAndroidTest` 编译通过 → `:app:testDebugUnitTest --tests "cn.limpu.hita.ui.main.timeline.*"` 全绿 → 真机安装截图。删改界面时同步更新对应测试，不留编译不过的测试源。
6. **文案约定**：首页不使用间隔号「·」；日期与周次、时间与节次之间只留空格。硬编码中文进 `strings.xml`。
7. **留记录**：设计取舍写进提交信息与本文档，不靠口头。

---

## 三、已完成的工作

### 提交清单（按时间顺序）

| 提交 | 内容 |
|---|---|
| `e865e09d` | 顶栏去掉「今日」，改为日期 + 第 N 周；晚间问候改文案；移除「今日安排」标题行与课表切换入口；移除明日准备卡片与投影；移除提示卡与 `HintUtils` |
| `8f1ca309` | 顶栏周次改到星期之前；「今天已结束 N 项安排」去掉「今天」 |
| `040bcb76` | 顶栏去掉间隔号；问候区（当时还在）改成主题色卡片 |
| `5d3f3953` | 删除问候块（标题 + 副标题 + 卡片），清理问候/焦点投影与测试，只保留 `TodayClock`；摘要卡改成列表形态 |
| `81739f2e` | 课程卡整体压小，改成按屏高切换单行条（后在 `15bb2b8a` 中撤回） |
| `15bb2b8a` | 课程卡恢复原始尺寸；考试卡去掉「N 场待参加」改成逐条「时间 科目」，最多三行，空态「暂无考试通知」；待办卡去掉「N 项」改成逐条标题，最多三行，空态「暂无待办」 |
| `c327cf3b` | 顶栏分主次（日期 22sp 粗体为主，周次星期 12sp 次级）；两张摘要卡改成上下排列各占整行；摘要区高度按两张卡重算（固定 220dp / 紧凑 140dp），布局阈值同步 |
| `b352c13e` | 考试行去掉事件名里的「[考试]」前缀并补地点；待办行标题后补科目；彻底删除底部「正在上课/即将开始」课程条（界面、状态、解析器、定时刷新、单测一并移除） |
| `546e262e` | 首页待办只取「截止时间不早于现在」的作业，与待办页「Grader 待完成」同口径；连带去掉整卡标红 |

### 首页现在的结构（从上到下）

1. 顶栏：`11月19日`（主）+ `第12周 周四`（次）+ 右侧账号头像
2. 课程卡列表：原始尺寸（课名 19sp、时间、教师、右侧地点胶囊、进行中 3dp 进度条）
3. 考试卡（整行）：最多三行 `MM-dd HH:mm 科目 地点`
4. 待办卡（整行）：最多三行 `标题 科目`，只显示未过期作业

已删除的旧元素：问候块（标题/副标题/卡片）、「今日安排」标题与课表切换 chip、明日准备、提示卡、底部课程提示条。

### 验证记录

- 单测：`:app:testDebugUnitTest --tests "cn.limpu.hita.ui.main.timeline.*"` 多轮全绿（最近一次 9 个测试类，0 失败）
- 真机：vivo PD2425（360×800dp、560dpi）多轮安装截图，存 `.impeccable/screens/`：
  - `home-homework-upcoming.png`（最终态：顶栏主次、考试行含地点、待办三项与待办页一致）
  - `home-exam-homework-detail.png`、`home-header-hierarchy-stacked.png`、`timetable-no-mini-bar.png`
- 首页评审快照：`HITA_Android-main/.impeccable/critique/2026-11-18T14-54-46Z__hita-ui-main-timeline-fragmenttimeline-kt-0561ec05.md`（改造前基线，Nielsen 20/40）

---

## 四、未决事项与风险

1. **六节课一屏的保证已失效**。卡片恢复原始尺寸后不再自动切换单行条，五六节课需要滚动。相关代码成了死代码：`TodayDenseRow`、`todayAgendaItems` 的 `dense` 参数、`TodayLayoutPolicy.agendaRowsAreDense/denseAgendaHeight`。建议要么清理，要么恢复阈值策略，别留着两套半成品。
2. **待办与考试都只显示三条**，超出的既看不到也没有「另有 N 项」提示。行数上限没和用户确认过。
3. **首页不再提示逾期**。待办页仍保留逾期语义，首页口径与之不同，交接后需要产品确认这是否接受。
4. **摘要区 220dp 挤压课程列表**：360×800 的机器上列表只剩约 384dp，大概三张完整卡。若嫌挤，可把摘要卡降成各显示两行（省约 40dp）。
5. **分支未推送**，PR #94 未合并；`main` 落后远端 9 个提交。
6. **设计文档与实现不一致**：`docs/superpowers/specs/2026-11-18-today-visual-redesign.md`、`2026-11-18-today-fixed-summary-layout-design.md` 仍描述着已删除的问候块、课表切换、明日准备与计数式摘要卡，需要同步或标注作废。
7. **未跟踪目录** `.impeccable/`（示意与截图）未提交，按需保留或清理。

---

## 五、建议的下一步

1. 清理死代码（单行条相关），或恢复「六节课不滚动」的策略并补回阈值测试。
2. 明确摘要卡的行数上限与超量提示方式，统一考试与待办。
3. 同步设计文档，把本文档的「当前结构」一节并入正式方案文档。
4. 真机补测四种状态：六节课、无考试、无待办、读取失败。
