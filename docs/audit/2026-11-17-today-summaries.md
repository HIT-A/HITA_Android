# 今日页优化：有来源边界的作业与考试摘要

日期：2026-11-17；分支：`feat/ios-parity-migration`；实施前基线：`a5fca01b`。

对应[实施计划](../superpowers/plans/2026-11-16-today-page-optimization.md)的 Task 3–4。摘要数据链路已实现，90 项 JVM 与 67 项设备回归通过，新今日布局尚未接入。

## 实现范围

### 作业

- Grader 新增不含凭据的当前账号摘要，保留已发布作业的逾期、无期限、已完成与远端已提交状态；原作业页的待办过滤不变。
- 摘要与账号、任务列表在同次状态发布中更新；退出、换号和被取消的连接操作沿用原 generation 保护。
- Repository 持有共享本地读取协调器，同一可见期/连接代次的调用合并，失败缓存到显式重试或新可见期；并发显式重试也合并。作业页继续按原条件主动刷新网络，今日页后续只调用本地加载。
- 今日作业投影区分本地与 Grader，不按同名合并；未知来源的计数为 null，已知空来源才为零。来源失败仍可保留标记明确的本地统计。
- 手写作业按设备当地日期计算，旧日期无时间按 23:59；格式异常单列。Grader 按绝对时间及 Asia/Shanghai 日期归类，已完成和已提交项不计入截止/逾期。

### 考试

- 新增进程内、当前会话的有界读取状态，最多保留 16 个学期查询键、每个结果 256 行、每字段 512 字符；截断和上游警告各有标记，不能据此宣称全量覆盖。
- 存储不可变记录，不保存个人备忘或凭据，不新增 Room 表或落盘缓存。重启后未知允许存在；从未查询、加载、成功空结果和失败分别保留。
- 沿用 EasQuerySession 检查，并通过学期请求序号拒绝同账号旧响应。取消的请求和已完成票据均不能再次发布；换账号或会话代次变化会清空结果。
- 独立考试页结果没有足够证据绑定当前课表，作为单列教务来源保留，不混入当前课表计数。不能仅凭学期 code 或课程同名推断账号/目标一致。
- 自动刷新在考试本地事务完整提交、会话仍有效、目标仍存在后才发布目标绑定；失败/取消不会发布成功绑定。缺少时间的原始记录可保留，但不生成虚假日程。
- 今日考试投影只合并当前账号、会话、学期和目标可核对的记录，使用现有 ExamEventMapper.identityKey 对完整原始考试及已导入 EAS 考试去重，个人考试保持独立 ID。
- 未知日期不算今天，未知时间单列待确认，已结束考试不算待参加；无绑定/其他目标的记录和失败保留数据均有明确来源信息。
- 原 LiveData 考试兼容入口复用可取消请求，不新增永久 observeForever。Mapper 支持显式时区，默认仍使用系统时区，与既有导入保持一致。

## 验证

- JVM：16 个相关测试类，共 90 项通过，0 失败、0 错误、0 跳过；包含前一批日期/时间投影回归、本批摘要与协调器、Grader 存储/取消及考试请求/映射。
- 构建：`:app:assembleDebug :app:assembleDebugAndroidTest` 通过。最终结果以 `hita-today-summary-verified-build.log` 及 `jvm-results/` 为准，中间构建不作为验收结果。
- 仓库检查：`python3 scripts/check_repository.py --base-ref origin/main` 与 `git diff --check` 通过。
- 设备回归：PJZ110（Android 17）重新连接后，使用 `adb install -r` 覆盖诊断版与测试包，未清空应用数据。`TodaySummaryRegressionSuite.Foreground` 中 7 个类、67 项测试全部通过（26.193 秒）；包含新 Repository/会话接线 2 项、自动考试事务 3 项、考试导入 13 项、三校区受控考试查询契约 47 项和既有作业界面 2 项。
- 首次后台运行进度不稳定，在第 30 项处主动停止，未作为通过结果。改用已有隔离 Debug 前台宿主完整复跑；显式嵌套 suite 避免默认测试发现时重复执行。测试包名中嵌套类的 `$` 需要同时避免主机和设备 shell 展开。

相关测试使用隔离偏好、内存 Room、受控响应及 MockWebServer，不作为真实教务或 Grader 账号验证。前一批的 26 项设备结果未计入本批 67 项。

设备复跑命令（先安装 Debug 与 AndroidTest APK）：

```bash
adb shell am instrument -w -r -e class 'cn.limpu.hita.ui.main.timeline.TodaySummaryRegressionSuite\$Foreground' cn.limpu.hita.diag.test/androidx.test.runner.AndroidJUnitRunner
```

日志目录：`/home/rinat/projects/HITA-artifacts/today-summaries-2026-11-17/`。

## 后续

Task 5–7 尚未完成：今日 ViewModel 状态聚合、RESUMED 时钟、新 Compose 信息层级、展开/滚动保持、明日卡和导航。手机上的旧今日页布局继续保留，本批不能当作整期功能已可见或完整界面验收通过。
