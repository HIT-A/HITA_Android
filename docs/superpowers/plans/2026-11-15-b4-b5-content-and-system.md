# B4/B5 内容层与系统能力 实施计划

**Goal:** 补齐资源预览与复制、作业字段、快照管理、软隐藏与编辑历史、系统日历同步、一键重置六项。
**Architecture:** 内容层全部走既有偏好/文件存储；只有 C-02 需要 Room 18→19；系统能力通过新增 Repository 隔离平台 API。
**Spec:** [2026-11-15-b4-b5-content-and-system-spec.md](../specs/2026-11-15-b4-b5-content-and-system-spec.md)

---

## Task 1: 作业字段模型（H-02）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/data/model/homework/HomeworkItem.kt`
- Modify: `app/src/main/java/cn/limpu/hita/data/repository/homework/HomeworkStore.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/repository/homework/HomeworkItemCompatTest.kt`

- [ ] 测试：旧快照（只有 title/id/createdAt）能读取，新字段取默认值；新字段往返一致；非法枚举回退
- [ ] 运行 `--tests *HomeworkItemCompatTest` → FAIL
- [ ] 实现 `subject/submissionMethod/submissionDetail/submissionFormat` 与两个枚举
- [ ] 放宽 `load()` 对新字段的校验为「非法回退默认」，保留原硬约束
- [ ] 运行 → PASS

## Task 2: 作业 UI 四个新输入（H-02）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/homework/HomeworkScreen.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/homework/HomeworkViewModel.kt`
- Modify: `app/src/main/java/cn/limpu/hita/data/repository/homework/HomeworkRepository.kt`

- [ ] 添加/编辑对话框加入科目、提交方式（下拉）、提交信息、提交格式（下拉）
- [ ] `add()`/`edit()` 接受新字段并透传到 store

## Task 3: 快照备注与保留策略（C-03）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/data/repository/TimetableSnapshotStore.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/repository/SnapshotRetentionTest.kt`

- [ ] 测试：保留上限裁剪保留最新 N 条；备注写入读取；删除单条不影响其他；旧 `v1.json` 无 note 字段可读
- [ ] 运行 → FAIL
- [ ] 给 `TimetableVersionSnapshot` 加 `note: String = ""`；实现裁剪
- [ ] 运行 → PASS

## Task 4: 快照管理 UI（C-03）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/eas/imp/ImportTimetableActivity.kt`

- [ ] 快照列表项显示「课程数 · 课次数 · 时间」
- [ ] 长按菜单：编辑备注 / 删除
- [ ] 保留上限选择（5..100）

## Task 5: Room 18→19 迁移（C-02）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/data/AppDatabase.kt`
- Create: `app/schemas/cn.limpu.hita.data.AppDatabase/19.json`（由构建导出）
- Create: `app/src/main/java/cn/limpu/hita/data/source/dao/EventEditHistoryDao.kt`
- Create: `app/src/main/java/cn/limpu/hita/data/model/timetable/EventEditHistory.kt`
- Modify: `app/src/main/java/cn/limpu/hita/data/model/timetable/EventItem.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/AppDatabaseMigrationChainTest.kt`（既有，追加 18→19）
- Test: `app/src/androidTest/.../TimetableMigration19Test.kt`

- [ ] 迁移测试：18→19 后既有数据 `hidden = 0`；新表存在
- [ ] 实现 `ALTER TABLE events ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0`
- [ ] 实现 `CREATE TABLE event_edit_history(...)` 与索引
- [ ] 注册 `MIGRATION_18_19` 到 `ALL_MIGRATIONS`，`VERSION = 19`
- [ ] 运行迁移链测试 → PASS

## Task 6: 读取查询加 hidden 过滤（C-02）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/data/source/dao/EventItemDao.kt`

- [ ] 逐条处理 40 处 `@Query`：读取类加 `AND hidden = 0`；写入/删除/计数按语义处理
- [ ] 删除/导出/搜索等入口逐个核对（清单见 spec 第四节）
- [ ] 运行全量既有课表测试 → 必须全绿，不改断言

## Task 7: 软隐藏与编辑历史（C-02）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/data/repository/TimetableRepository.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/timetable/TimetableBatchCoordinator.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/repository/SoftHideTest.kt`

- [ ] 测试：教务课删除后 `hidden=1` 且查询不可见；恢复后可见；手动课物理删除但留 history
- [ ] 实现按 `source` 分流；写 history
- [ ] 运行 → PASS

## Task 8: 编辑历史 UI（C-02）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/timetable/manager/TimetableManagerActivity.kt`

- [ ] 新增「编辑历史」入口，列表展示时间/动作/课程名
- [ ] 单条恢复按钮

## Task 9: 日历同步（C-08）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/repository/CalendarSyncRepository.kt`
- Modify: `app/src/main/AndroidManifest.xml`（加 READ_CALENDAR/WRITE_CALENDAR）
- Test: `app/src/test/java/cn/limpu/hita/data/repository/CalendarSyncPlanTest.kt`

- [ ] 测试：幂等键 `hita:<timetableId>:<eventId>:<startMs>` 稳定；同一键判定为 update；事件 → ContentValues 字段正确
- [ ] 实现 plan 计算与写入，含 `NoWritableCalendar`/`PermissionDenied`
- [ ] 运行 → PASS

## Task 10: 日历入口（C-08）

**Files:**
- Modify: `app/src/main/java/cn/limpu/hita/ui/timetable/manager/TimetableManagerActivity.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/eas/exam/ExamActivity.kt`

- [ ] 课表管理加「加入系统日历」
- [ ] 考试页加「考试加入日历」
- [ ] 无权限/无日历给出明确提示

## Task 11: 一键重置（I-07）

**Files:**
- Create: `app/src/main/java/cn/limpu/hita/data/repository/AppResetCoordinator.kt`
- Modify: `app/src/main/java/cn/limpu/hita/ui/main/navigation/NavigationFragment.kt`
- Test: `app/src/test/java/cn/limpu/hita/data/repository/AppResetCoordinatorTest.kt`

- [ ] 测试：某分量回读失败时其余仍执行，`failed` 正确
- [ ] 实现分量清理 + 回读
- [ ] UI：影响清单 + 二次确认 + 红色入口
- [ ] 运行 → PASS

## Task 12: 批次验收

- [ ] `gradlew.bat :app:assembleDebug` → 0
- [ ] `gradlew.bat :app:testDebugUnitTest` → failures=0, errors=0, tests ≥ 1071
- [ ] `python scripts/check_repository.py` → 0
- [ ] 迁移链测试与升级路径测试通过
