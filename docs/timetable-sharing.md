# 离线课表分享交付说明

## 入口与操作

个人课表详情中的分享入口打开分享页。选择个人课表、填写昵称并选择完整或忙闲模式，生成后复制口令或通过系统分享发送。完整模式显示课程数和安排数；忙闲模式只显示合并后的占用时段数，切换模式会重新读取对应统计。修改表单会使旧结果失效，需要重新生成。分享的是生成时的快照，之后个人课表变化不会自动同步。

课表管理页的好友课表入口提供导入、列表、备注和删除。粘贴包含一个口令的整段聊天文本，先检查昵称、学期、模式与安排预览，再确认导入。相同分享身份且内容相同直接打开已有快照；内容变化需确认替换，保留本地备注。如果预览后数据库状态已变化，重新显示确认预览。清空备注恢复昵称。删除只删除好友快照。好友详情只读，按 Asia/Shanghai 显示日期、周次和课程时间。

## 协议与存储

v1 文本为 `HITA1:` 加无 padding 的 Base64URL(GZIP(UTF-8 紧凑 JSON))。仅接受一个口令、一个完整 GZIP member；损坏、未知版本及非法字段会报错，不截断或部分导入。口令没有加密或签名，接收者能解码，请按分享内容选择接收人。功能不增加后端、实时同步或撤回。

JSON 使用固定键和数组位置：

| 键 | v1 含义 |
| --- | --- |
| v | 整数版本 1 |
| id | UUID 分享身份；本地同一课表和学期复用 |
| n | 分享昵称 |
| m | `full` 或 `busy` |
| t | `[课表名, 学期名, 学期开始毫秒, 学期结束毫秒, Asia/Shanghai]` |
| p | 作息数组，每项 `[开始分钟, 结束分钟]` |
| c | FULL 课程名称字典；BUSY 不含此键 |
| e | FULL 每项 `[课程索引, 开始毫秒, 结束毫秒, 地点]`；BUSY 每项 `[开始毫秒, 结束毫秒]` |

FULL 保留实际出现的每条安排，不按周次推断。BUSY 去除课程和地点，并合并开始日期属于同一校园日、合并后不超过 24 小时的重叠及相邻时间段；因此 BUSY 的安排数可能少于 FULL。App 生成的 BUSY 快照将原课表名替换为固定“忙闲课表”；仍含昵称、学期信息、作息和时间，不是匿名数据。协议 t[0] 仍为课表名称字段，解码器保留合法输入值。字典与安排规范排序后生成 SHA-256 内容摘要，用于判断重复和更新。

AppDatabase 从 14 升至 15，新增 `timetable_share_identity`（课表与学期联合主键、shareId 唯一索引）及 `friend_timetable`（shareId 主键、昵称、本地备注、学期范围、模式、快照 JSON、摘要和导入/更新时间）。保留历史 Room schema 和迁移链。分享 Repository 仅读取个人课表/安排，并写入独立身份表；好友 Repository 只访问好友表。编解码、数据库和快照构建使用 IO 协程。现有个人课表、提醒和编辑数据不通过好友导入写入。

## 限制

整段文本最多 131072 字符，单口令最多 65536 字符（含前缀），解压 JSON 最多 1048576 字节，JSON 深度最多 8。课程字典最多 512 项，安排最多 4096 项，作息最多 48 项；空课表不可生成。昵称为 1–40 个 Unicode 码点，课表名/学期名最多 80，课程名/地点最多 120。作息分钟范围 0–1440，开始小于结束、有序且不重叠。时间范围为校园时区 2000-01-01 至 2100-01-01（不含），学期不超过 370 天，单次安排不超过 24 小时。

## 合成长度测量（2026-10-09）

以下均来自现有 `fullFixture()` 派生的合成数据，不代表三校区真实课表。16 周样本每门课每周出现一次，安排互不重叠；密集样本每天一条共 100 条。所有样本编码后再解码，断言课程数与安排数完整保留。BUSY 样本检查 JSON 不含课程字典、合成课程名和地点。日志只输出计数与长度，不输出口令或 JSON 内容。

| 样本 | 模式 | 字典课程数 | 安排数 | JSON 字节 | GZIP 字节 | 口令字符 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 16 周 / 4 门 | FULL | 4 | 64 | 3384 | 854 | 1145 |
| 16 周 / 4 门 | BUSY | 0 | 64 | 2098 | 656 | 881 |
| 16 周 / 8 门 | FULL | 8 | 128 | 6584 | 1452 | 1942 |
| 16 周 / 8 门 | BUSY | 0 | 128 | 4018 | 1053 | 1410 |
| 16 周 / 16 门 | FULL | 16 | 256 | 13087 | 2588 | 3457 |
| 16 周 / 16 门 | BUSY | 0 | 256 | 7858 | 1702 | 2276 |
| 密集 100 天 | FULL | 1 | 100 | 4099 | 947 | 1269 |
| 密集 100 天 | BUSY | 0 | 100 | 3178 | 891 | 1194 |

## 本次验证及 APK

在隔离 worktree 中完成最终忙闲统计修复后执行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`，实际退出码 0，BUILD SUCCESSFUL，耗时 51 秒。JVM XML 汇总 485 项，0 失败、0 错误、1 跳过，即 484 项执行通过；跳过项为既有外部仓库兼容测试。统计回归覆盖两门相邻课程在 FULL 中为两门课程、两项安排，在 BUSY 中为一段占用时间，以及切换模式拒绝旧统计；统计构建与表单状态的针对性 9 项测试均通过。此前 ShareTokenSizeTest 两项及单独合成长度测量也通过。AndroidTest APK 构建成功只证明设备测试可编译打包。

本次使用 README_DEV 允许的本地 JDK21，SDK Platform 35/36 已存在。内存控制仅传入临时构建进程：C1、1536m、CodeCache 256m、CPU2、workers1，以及临时测试 init 文件；未提交机器路径。最终验证日志在本地忽略目录 `.superpowers/sdd/2026-10-09-timetable-sharing/` 的 `final-fix1-red.log`、`final-fix1-green.log`、`final-fix1-full.log` 及对应 `.exit` 文件，合成长度测量保留在 `task-7-measure.log`。最新完整构建没有输出 warning；早先构建的既有警告不在此任务顺带修改。

Debug APK 相对仓库路径：`app/build/outputs/apk/debug/app-debug.apk`。本次绝对路径：`C:/Users/Rinat/.codex/worktrees/timetable-sharing/HITA_Agent/app/build/outputs/apk/debug/app-debug.apk`。

- SHA-256：`48b12c8133d45726253566b5d7ee76a2843d1603e311101f86076678c23cc098`
- aapt 只读检查：包名 `cn.limpu.hita.diag`，versionName `3.0.3`，versionCode `2026093005`，最低 SDK26，目标 SDK35，编译 SDK36。
- AndroidTest APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

Python `scripts/check_repository.py`、Git Bash `project_health_check.sh` 均退出 0。静态搜索新 feature/UI/两个分享 Repository 未发现口令日志、网络调用、GlobalScope 或 EventsUtils；个人 DAO 读取限定于分享 Repository，好友写入限定于好友 DAO。新增界面中文使用字符串资源。`git diff --check` 及新增文本的尾部空白检查通过；Git 的 CRLF 提示是行尾转换提示。

## 尚未验证的验收项目

本次 `adb devices` 列表为空，无设备或模拟器，未运行 connectedDebugAndroidTest、未安装 APK。Room 14→15 迁移和个人数据隔离已有设备测试源码并已编译，运行结果尚未验证。没有真实课表来源，深圳/本部/威海及手动课表的真实 FULL/BUSY 课程数、安排数、JSON/GZIP 字节和口令长度均尚未测量。

仍需在授权测试设备上验收：两台设备通过微信发送整段消息、断网导入、更新确认、备注保留、重复导入和删除隔离；系统时区改为 New York 后仍按校园时间展示；旋转后的状态保留、导航、滚动、密集课表布局和长文案。构建与 JVM 测试不能代替上述设备验收。未发布 release，全部变更保留未提交供审阅。
