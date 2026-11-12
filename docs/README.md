# 文档索引

本目录只放**当前有效**的说明：功能行为、协议约定、维护方式与现行约束。
已完成的一次性审计与重构过程记录在 [`archive/`](archive/)。

## 功能与行为

| 文档 | 内容 |
|---|---|
| [academic-calendar.md](academic-calendar.md) | 校历解析与课表标题的周次口径 |
| [analytics-v2.md](analytics-v2.md) | 使用统计 v2：事件、队列与隐私边界 |
| [app-update.md](app-update.md) | 应用内更新的检查、下载与安装 |
| [timetable-sharing.md](timetable-sharing.md) | 离线课表分享（HITA1 token / ICS） |
| [timetable-comparison.md](timetable-comparison.md) | 课表双人对比与共同空闲时间 |
| [timetable-display-management.md](timetable-display-management.md) | 课表显示目标与课表管理 |
| [useful-links.md](useful-links.md) | 实用网址的维护方式 |
| [pdf-resources.md](pdf-resources.md) | PDF 资源与中文解析验证 |

## 登录与网络

| 文档 | 内容 |
|---|---|
| [login-session-persistence.md](login-session-persistence.md) | 网页登录态持久化与静默续期 |
| [notice-portal-login.md](notice-portal-login.md) | 深圳校园通知门户登录 |
| [weihai-eas-network-entry.md](weihai-eas-network-entry.md) | 威海教务入口：校园网直连与 WebVPN 回落 |

## 测试与发布

| 目录 | 内容 |
|---|---|
| [testing/](testing/) | 四层测试设计、测试台账、映射与现行硬约束 |
| [releases/](releases/) | 各版本更新日志 |
| [live-course/](live-course/) | 实时课程的厂商兼容性实测记录 |
| [superpowers/](superpowers/) | 功能设计稿（specs）与实施计划（plans） |

## 归档

[archive/](archive/) — 阶段审计、重构台账、专项审计与测试体系建设期的执行记录。
归档内容不再随代码更新；与当前代码冲突时以代码为准。

## 维护约定

- 新增功能说明放在本目录，并在上表登记。
- 过程性记录（审计、执行台账、一次性验证）完成后归入 `archive/` 对应子目录。
- 移动文档后必须跑 `python3 scripts/check_repository.py`：它校验本地 Markdown 链接，会拦下断链。
