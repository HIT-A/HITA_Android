# 统一待办实施计划

**目标：** 让待办页复用应用背景，统一展示个人及两平台未提交记录，并发布本地诊断包。

**设计：** [统一待办与 GeniusTA 导入](../specs/2026-11-19-unified-todo-design.md)

1. 统一背景：在 `ui/main` 抽取公共背景，供 `MainScreen.kt` 和独立 `HomeworkActivity` 使用；验证内嵌与独立入口不覆盖背景，保留非背景组件的主题样式。
2. 平台数据：实现 `data/source/web/geniusta`、`data/repository/geniusta` 与独立加密偏好；覆盖登录会话配对、课程分组日期、提交状态过滤及本地完成持久化。分离 Grader 待办投影中的远端提交过滤与本地完成，不改首页摘要。
3. 统一体验：修改 `HomeworkViewModel.kt`、`HomeworkBoard.kt`、`HomeworkScreen.kt` 和平台控件。两种列表状态共用排序和卡片，完成回调绑定条目来源及账号，登录错误与刷新动作保持各自独立。更新已有待办单测和界面测试契约。
4. 验证交付：配置 `app/build.gradle` 版本 `3.2.2`，按批准范围审查改动，执行 `assembleDebug`、`testDebugUnitTest` 并校验诊断 APK 元数据；复制到根目录，局部提交，不运行模拟器或推送。
