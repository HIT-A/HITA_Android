# B6/B7/B8 UI、扩展栏与协议 实施计划

**Goal:** 补齐小课程条、小组件深链与配置、「扩展」栏与四项校园扩展、培养方案三校区、高校课表协议导入。
**Architecture:** UI 层全部复用 `HitaTheme` 令牌与 `NavigationGroup`/`NavigationRow`；扩展功能分层为「可无凭据验证的纯函数」与「需真实门户的抓取」；协议导入只做 JSON。
**Spec:** [2026-11-15-b6-b7-b8-ui-extensions-protocols-spec.md](../specs/2026-11-15-b6-b7-b8-ui-extensions-protocols-spec.md)

---

## Task 1: mini 课程条解析器（A-02）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/ui/main/MiniCourseResolver.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/main/MiniCourseResolverTest.kt`

- [ ] 测试：进行中（含进度）；即将开始（15 分钟内）；无课；已结束；两事件相邻边界
- [ ] 运行 `--tests *MiniCourseResolverTest` → FAIL
- [ ] 实现 `resolve(events, now)` 返回 `MiniCourseState(phase, title, place, timeText, progress)`
- [ ] 运行 → PASS

## Task 2: mini 课程条 UI（A-02）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/ui/main/MiniCourseBar.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/MainScreen.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/MainViewModel.kt`

- [ ] `MainViewModel` 产出 `MiniCourseState`（订阅既有课表数据）
- [ ] `MainScreen` 在 Dock 上方渲染；`Phase.NONE` 不渲染
- [ ] 点击打开课程详情（复用既有入口）
- [ ] 键盘弹出时隐藏

## Task 3: 小组件深链（A-04）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/widgets/today/TodayUtils.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/MainActivity.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/MainViewModel.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/widgets/WidgetDeepLinkTest.kt`

- [ ] 测试：extra 解析为 `(eventId, date)`；缺失/非法返回 null
- [ ] 运行 → FAIL
- [ ] `fillIn` 携带 extra；`MainActivity` 在 `onCreate`/`onNewIntent` 解析并转给 VM
- [ ] 运行 → PASS

## Task 4: 小组件配置页（A-04）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/ui/widgets/today/WidgetConfigureActivity.kt`
- Create: `app/src/main/java/cn/limpu/hita/ui/widgets/today/WidgetPrefs.kt`
- Modify: `app/src/main/res/xml/today_widget_info.xml`、`today_slim_widget_info.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/cn/limpu/hita/ui/widgets/WidgetPrefsTest.kt`

- [ ] 测试：按 `appWidgetId` 读写与删除互不干扰
- [ ] 实现配置页（显示范围复用 `DisplayScopeResolver`；背景两档）
- [ ] 启用 `android:configure`；`onDeleted` 清理
- [ ] 运行 → PASS

## Task 5: 扩展可用性纯函数（B7 核心）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/ui/main/navigation/CampusExtension.kt`
- Test: `app/src/test/java/cn/limpu/hita/ui/main/navigation/CampusExtensionTest.kt`

- [ ] 测试：未登录→空；本部登录→空；威海登录→空；深圳未登录→空；深圳登录→四项
- [ ] 运行 → FAIL
- [ ] 实现 `available(campus, loggedIn)`
- [ ] 运行 → PASS

## Task 6: 扩展栏挂载（B7）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/navigation/NavigationFragment.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] 在 `ShortcutCard` 之后、`NavigationGroup(navi_jw_title)` 之前插入
- [ ] `if (entries.isNotEmpty())` 才渲染整栏
- [ ] 复用 `NavigationGroup`/`NavigationRow`，零硬编码色

## Task 7: 图书馆签名与解析（E-03）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/source/web/campus/SmartWallRequestSigner.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/repository/UniversityTownLibraryRepository.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/model/campus/UniversityTownLibrarySnapshot.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/source/web/campus/SmartWallRequestSignerTest.kt`

- [ ] 测试：固定 seed 的 `nonce` 与 `signature` 与 iOS 对照值一致（`UniversityTownLibraryTests.swift:6-83`）
- [ ] 运行 → FAIL
- [ ] 实现 MD5 签名器；JSON 解析在馆人数与空间数
- [ ] 运行 → PASS

## Task 8: 校园卡模型与解析（E-01）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/model/campus/CampusCardModels.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/source/web/campus/CampusCardParser.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/source/web/campus/CampusCardParserTest.kt`

- [ ] 测试：金额分转元；状态文案映射；流水分页字段；空/异常 JSON 返回 null
- [ ] 运行 → FAIL
- [ ] 实现解析器（字段严格来自 iOS 已知字段，不臆造）
- [ ] 运行 → PASS

## Task 9: 静音仓签名与时间解析（E-02）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/source/web/campus/PimaRequestSigner.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/model/campus/SilentPodModels.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/source/web/campus/PimaRequestSignerTest.kt`

- [ ] 测试：签名盐与字段顺序；时间区间解析与裁剪（08:00–22:00，10 分钟步进）
- [ ] 运行 → FAIL
- [ ] 实现
- [ ] 运行 → PASS

## Task 10: 实验中心解析（E-04）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/source/web/campus/LabCenterParser.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/source/web/campus/LabCenterParserTest.kt`

- [ ] 测试：二维数组行解析为场次；跳过含 `[理]` 与 `-paikao`；空数组返回空
- [ ] 运行 → FAIL
- [ ] 实现（字段索引严格对齐 iOS `CampusLabCenterClient.swift:162-177`）
- [ ] 运行 → PASS

## Task 11: 四个扩展页面（E-01/02/03/04）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/ui/campus/CampusCardActivity.kt`
- Create: `app/src/main/java/cn/limpu/hita/ui/campus/SilentPodActivity.kt`
- Create: `app/src/main/java/cn/limpu/hita/ui/campus/UniversityTownLibraryActivity.kt`
- Create: `app/src/main/java/cn/limpu/hita/ui/campus/LabCenterActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`、`strings.xml`

- [ ] 四页统一骨架：加载 / 空 / 失败（含原因）/ 成功
- [ ] 需门户的操作在无会话时置灰并提示，不伪造成功
- [ ] 全部颜色走令牌

## Task 12: 培养方案三校区（D-06）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/repository/TrainingPlanSource.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/credit/CreditStatsViewModel.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/repository/TrainingPlanSourceTest.kt`

- [ ] 测试：`forCampus` 分派正确；`Unavailable` 不产出假数据
- [ ] 运行 → FAIL
- [ ] 去掉深圳硬门；`Unavailable` 展示说明卡片
- [ ] 运行 → PASS

## Task 13: 协议 JSON 解析（C-09）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/model/timetable/TimetableProtocolModels.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/repository/TimetableProtocolParser.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/repository/TimetableProtocolParserTest.kt`

- [ ] 测试：合法样本往返一致；缺字段/非法时间/超长被拒且错误具体
- [ ] 运行 → FAIL
- [ ] 实现严格校验
- [ ] 运行 → PASS

## Task 14: 协议导入 UI（C-09）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/timetable/manager/TimetableManagerActivity.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/repository/TimetableProtocolImporter.kt`

- [ ] 「添加」面板新增「导入协议 JSON」
- [ ] 解析 → 预览 → 确认落库

## Task 15: 批次验收

- [ ] `gradlew.bat :app:assembleDebug` → 0
- [ ] `gradlew.bat :app:testDebugUnitTest` → failures=0, errors=0, tests ≥ 1071
- [ ] `gradlew.bat :app:testDebugUnitTest --tests *CampusExtensionTest` → 全绿
- [ ] `python scripts/check_repository.py` → 0
- [ ] 亮/暗两种模式下人工核对新增页面
