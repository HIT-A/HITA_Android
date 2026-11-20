# 今日页固定布局真机回归

日期：2026-11-18；分支：`feat/ios-parity-migration`；接续 [本地实施记录](2026-11-18-today-fixed-layout.md)。设备验证补充及修正代码：`09893895`。

## 结果

- PJZ110，Android 17 / API 37，1440×3168px，640dpi（360×792dp），手势导航。
- 覆盖安装诊断版 `cn.limpu.hita.diag` 和测试包，使用 `adb install -r` 保留数据；正式版未覆盖。
- 最终 `TodayUiRegressionSuite.Foreground`：46 项全部通过，27.048 秒；含 ViewModel 6 项、页面 8 项、布局 6 项、选择面板 2 项、底栏几何 1 项、课表存储 23 项。
- 额外运行 `MainViewModelInstrumentedTest#scopeSelectionUsesIdentityAfterReorderingAndRejectsRemovedScopes`：1 项通过。合计 47 项设备测试通过。
- 修正后的 `:app:assembleDebug :app:assembleDebugAndroidTest` 和 56 项相关 JVM 测试通过（0 失败/错误/跳过）。本轮没有重跑全部 app JVM 测试；上一轮的全量结果仍见本地实施记录。
- `python3 scripts/check_repository.py --base-ref origin/main`、`git diff --check` 通过。

## 真机发现与修正

首轮 46 项中 4 项失败，经现场测量、无障碍节点导出和复跑定位：

1. 测量底栏的测试宿主继承了窗口最小高度，错误地把全屏高度当作底栏高度。用普通 Box 消除该约束后测量栏体。
2. Android 将当前选项的 Compose `selected` 语义导出为 `checkable/checked`；面板本身勾选正常。测试兼容该平台映射，并刷新虚拟节点缓存。
3. 人为将布局密度缩小到 0.75 会造成布局与平台无障碍坐标换算不一致；连续无障碍滚动动作还可能在动画中被拒绝。测试优先使用原生密度，并用真实滑动手势、事件静止等待、缓存刷新和完整可见项检查来验证滚动恢复及末项避让。允许位置比较中最多 1 个物理像素的取整误差。
4. 真实胶囊底栏在字号 1.6 下约为 56.5dp，而名义公式给出 53dp。中文字体回退的实际行高超过声明的 10sp。`rememberPillBarLabelHeight` 现在以同一主题文字样式测量标签，包含常规/粗体和所有导航标题；底栏与今日页避让共用测量值，栏体按同一高度布局。没有压缩标签来满足原公式，也没有放宽几何断言。

原纯函数保留名义行高作为默认参数，并增加实测标签高度参数，供运行时使用；新增 JVM 用例覆盖字体回退增高时的手势/三键导航避让。底栏设备用例验证两种栏体在字号 1.0/1.3/1.6 下与对应共用几何相差不超过 1dp。

## 实际主页面检查

最终安装包的完整 MainActivity 截图和 XML 已检查：

- 7 种主题 × 浅色/深色，共 14 个普通字号组合，摘要均在导航区域上方。采用应用当前真实课表记录，未添加测试课表到用户数据库。
- 原玻璃主题、字号 1.3/1.6：紧凑摘要生效，顶栏“今日”未见裁切。本轮无须修改顶栏高度。
- 横屏：采用整页滚动，滑到底可完整看到最后一行 `Grader：未连接`，没有被底栏遮挡。
- 实际历史展开后滑动课程：摘要边界保持 `[80,2268][1360,2820]`，即高 138dp；摘要不随课程滚动，末项可见。
- 课表面板在主界面打开并覆盖 Dock，当前项具有勾选状态，返回可关闭。设备现有范围只有一项；真实不同课表/课表组的往返切换没有现场验收，受控设备用例覆盖该状态恢复和按对象选择链路。
- 主题恢复到原有 `apple_glass / follow`；字号恢复 1.0，自动旋转与旋转方向恢复原值（均为 0），结束时诊断版主界面在前台。

## 证据

目录：`/home/rinat/projects/HITA-artifacts/today-fixed-layout-2026-11-18/`。

- 最终测试：`device-regression.log`、`device-main-scope.log`；初始失败：`device-initial-regression.log`；其他中间诊断日志保留在同目录。
- 最终构建与相关 JVM：`device-measured-label-build.log`、`device-jvm-results/`、`device-verification.json`；静态检查：`device-repository-check.log`。
- 完整主页面：`main-final.png/xml`、`main-history-expanded.png/xml`、`main-history-scrolled.png/xml`、`main-picker.png/xml`。
- 字号和横屏：`main-font-1.3.png/xml`、`main-font-1.6.png/xml`、`main-landscape.png/xml`、`main-landscape-end.png/xml`；`window-checks.json` 保存实测结果。
- 主题：`theme-<style>-<light|dark>.png/xml`、`theme-matrix.json`，以及本机复现脚本。

## 尚未验证

三键导航、分屏、所有主题与所有字号的交叉组合、真实多个课表/课表组与账号切换、真实作业读取失败后的服务端重试仍待补。没有进行帧率测量或完整性能验收。本轮未执行 push。
