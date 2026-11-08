# 交付与回滚方案

## 提交计划（本地、refactor/agent-05-testing 分支，未 push）

按逻辑分组提交，便于单独回滚：

| 序 | 提交内容 | 涉及文件 |
|---|---|---|
| 1 | docs: 测试体系阶段一文档 | `docs/testing/*.md` |
| 2 | test: 补齐后台任务测试（Worker/Widget/直播课） | `app/src/androidTest/.../data/work/*.kt`、`.../feature/livecourse/scheduler/*.kt` |
| 3 | test: 模拟机 E2E（UI Automator） | `app/src/androidTest/.../e2e/*.kt` |
| 4 | build: 测试依赖增补与死依赖清理 | `gradle/libs.versions.toml`、`app/build.gradle` |
| 5 | chore: 测试集运行器与报告聚合 | `scripts/test-suites.sh`、`scripts/test-report.py`、`scripts/e2e-emulator.sh` |

## 回滚方案

### 整体回滚

```bash
git reset --hard 5992ba381a9d6e87deb7526885f3d957b8a6d09b
```

基线 commit 即任务书指定基线，回到它可完全恢复。

### 按提交粒度回滚

```bash
git revert <commit-hash>     # 保留历史，安全
git reset --hard <commit-hash>^   # 丢弃该提交及其后（仅在本地未共享时使用）
```

### 按文件粒度回滚（最常见）

```bash
# 仅撤销依赖变更
git checkout 5992ba3 -- gradle/libs.versions.toml app/build.gradle

# 仅删除新增的 E2E 测试
rm -rf app/src/androidTest/java/cn/limpu/hita/e2e

# 仅删除新增的后台任务测试
rm app/src/androidTest/java/cn/limpu/hita/data/work/CourseReminderWorkerInstrumentedTest.kt \
   app/src/androidTest/java/cn/limpu/hita/data/work/WidgetRefreshWorkerInstrumentedTest.kt \
   app/src/androidTest/java/cn/limpu/hita/feature/livecourse/scheduler/LiveCourseSchedulerInstrumentedTest.kt

# 仅删除脚本
rm scripts/test-suites.sh scripts/test-report.py scripts/e2e-emulator.sh
```

### 回滚后必须验证

```bash
./gradlew :app:testDebugUnitTest :timetable-core:test
python3 scripts/check_repository.py
```

## 风险与不可回滚点

| 项 | 评估 |
|---|---|
| 生产代码 | **未修改**，无回滚需求 |
| 数据库 | **未修改 schema/version**，无迁移风险 |
| CI | **未修改** |
| 唯一"删除" | `room-testing` 依赖；回滚只需恢复两行（`libs.versions.toml` 条目 + `app/build.gradle` 一行） |
| 私有 AVD `hita_agent05` | 仓库外产物，不影响仓库；如需清理：`avdmanager delete avd -n hita_agent05` |

## 未推送说明

按项目规范与本次授权，**未执行 `git push`**，所有提交仅存在于本地分支。推送需用户确认。
