# iOS → Android 功能差异审计（2026-11-14）

本目录是 iOS 端 `HITA-Aura` 与 Android 端 `HITA_Android` 的功能差异审计产物。**业务代码零改动**，只包含分析与可选方案。

## 文件

| 文件 | 内容 |
|---|---|
| [2026-11-14-ios-android-feature-diff.md](2026-11-14-ios-android-feature-diff.md) | 审计报告：86 项差异（A–J 十域 + K 补漏），每项含双端 `路径:行号`、类型、影响、难度、建议；另含 Android 独有能力清单、覆盖说明、优先级、基线构建验证 |
| [2026-11-14-migration-options.md](2026-11-14-migration-options.md) | 迁移方案：每项按方案 A/B/C/D 给出取舍，附依赖图与 86 项决策表 |
| [2026-11-15-migration-execution.md](2026-11-15-migration-execution.md) | **执行记录**：26 项迁移的实际落地范围、按批进度、阻塞项（E-01/E-02/E-03）、与规格的偏离、已知缺陷、以及构建/测试/门禁的实测结果 |
| [verify_citations.py](verify_citations.py) | 引用校验器：把报告里的 `路径:行号` 与真实仓库文件、行数核对 |

## 复核命令

```bash
python docs/audit/verify_citations.py     # 236 条引用全部有效
python scripts/check_repository.py        # 仓库门禁
```

## 口径

- 纯静态审计：未编译 iOS、未请求校园服务；Android 侧只跑了基线构建与单元测试。
- 判「缺失」前对 Android 全仓做 2–4 组不同关键词 grep，命中一律回看上下文；仅 `strings.xml` 有文案或 UI 未接线的不算实现。
- 待核实项已全部结案（见报告第六节）。

## 基线构建（迁移对比用）

| 项 | 结果 |
|---|---|
| `:app:assembleDebug`（JDK 21） | BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | 1071 项，0 失败，0 错误，1 跳过 |
| `timetable-core` / `component` / `hitauser` | 57 / 7 / 4 项，0 失败 |

## 状态

审计与方案已完成。实施需用户按决策表指定范围；未获批准前不改业务代码。
