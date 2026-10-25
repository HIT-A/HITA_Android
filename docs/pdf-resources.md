# PDF 资源与中文解析验证

PDFBox Android `2.0.27.0` 的 AAR 已包含所需字体、glyph list 和 CMap。`HApplication.onCreate` 在启动时调用 `PDFBoxResourceLoader.init(this)`；其资源加载器从 Android assets 读取依赖自带的文件。不能改为异步反射写入库内缓存，也不需要把资源再次复制到应用的 `assets` 或 Java `resources` 目录。

本次整理先运行真实 PDFBox 调用路径（`PDDocument.load` + `PDFTextStripper`，与 `AgentChatViewModel.parsePdfFile` 一致）发现旧初始化缺失导致默认字体加载失败。补全初始化后，在尚未删除任何资源时，简体 GB1、繁体 CNS1 的中文课程文字测试均通过；随后逐文件比对依赖 AAR，移除 95 对共 190 份相同内容的手工副本。原手工 CMap 路径在 `pdfbox/resources/cmap`，库实际使用 `fontbox/resources/cmap`，依赖路径才是当前运行依据。内容为 `404: Not Found` 的根目录压缩包占位也已移除。早期版本由 `PdfFileParser` 驱动该验证，随 `agent/document` 死代码包整体删除，测试改为直接调用库 API。

## 回归命令

设备启动并解锁后执行：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cn.limpu.hita.agent.document.PdfResourceInstrumentedTest
```

[测试](../app/src/androidTest/java/cn/limpu/hita/agent/document/PdfResourceInstrumentedTest.kt) 动态生成原创一页 PDF，使用预定义 Adobe GB1/CNS1 CMap，不嵌入字体或 ToUnicode 映射，核对完整简体/繁体课程字符串与页数。测试直接驱动 PDFBox API（`PDDocument.load` + `PDFTextStripper`，与 `AgentChatViewModel.parsePdfFile` 同一路径），依赖应用真实初始化；不会在测试中偷偷补初始化。

Android CI 的 device-tests 任务在 API 35 模拟器执行全部设备测试（含该测试），本机在独立 API 36 模拟器验证。更换 PDF 库版本、初始化代码或资源打包规则后必须重跑。测试不覆盖扫描件 OCR、所有中文字体或任意复杂排版，不能将这些样例通过解释为所有 PDF 格式都受支持。
