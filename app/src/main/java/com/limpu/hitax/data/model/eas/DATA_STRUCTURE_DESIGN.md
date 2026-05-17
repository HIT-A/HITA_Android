# EAS系统数据结构统一设计说明

## 概述

本文档说明HITAX EAS系统（本部、深圳、威海三个校区）的数据统一设计方案。

## 核心设计原则

### 1. 统一数据模型
所有校区使用相同的数据模型（TermItem、ExamItem等），在数据获取时进行格式转换。

### 2. 使用ID匹配
所有数据关联使用ID进行匹配，不使用可能不一致的显示名称。

### 3. 数据源头标准化
在数据获取时（EASWebSource、BenbuEASWebSource、WeihaiEASWebSource）就完成格式转换。

## 核心数据结构

### TermItem（学期）

```kotlin
class TermItem(
    var yearCode: String,  // 学年代码，如 "2025-2026" [统一格式]
    var yearName: String,  // 学年名称，如 "2025-2026" [统一格式]
    var termCode: String,  // 学期代码，如 "1", "2", "3" [统一格式]
    var termName: String   // 学期名称，如 "春季", "秋季", "夏季" [统一格式]
) {
    var id: String = "$yearCode-$termCode"  // 统一ID，如 "2025-2026-2"
    var name: String = "$yearName$termName" // 显示名称，如 "2025-2026春季"
}
```

**ID格式规范：**
- 格式：`yearCode-termCode`
- 示例：`2025-2026-2`
- 用途：所有学期匹配、数据筛选、缓存键等

**学期代码对应关系：**
- `1` = 秋季学期（上学期）
- `2` = 春季学期（下学期）
- `3` = 夏季学期（小学期）

### ExamItem（考试）

```kotlin
class ExamItem {
    var termId: String? = null   // 学期ID，如 "2025-2026-2" [用于匹配]
    var termName: String? = null // 学期名称，如 "2026春季" [仅用于显示]
    var courseName: String? = null
    var examDate: String? = null
    var examTime: String? = null
    var examLocation: String? = null
    var examType: String? = null
    var campusName: String? = null
}
```

**匹配规则：**
- 使用 `exam.termId == term.id` 进行精确匹配
- 不要使用 `termName` 进行匹配（各校区命名可能不一致）

## 数据流程

### 1. 学期数据获取流程

```
[校区API] → [解析为TermItem] → [设置id字段] → [返回统一格式]
```

**深圳校区：** `EASWebSource.getAllTerms()`
**本部：** `BenbuEASWebSource.getAllTerms()`
**威海：** `WeihaiEASWebSource.getAllTerms()`

### 2. 考试数据获取流程

```
[用户选择学期] → [使用TermItem.id查询] → [解析考试数据] → [设置exam.termId] → [返回统一格式]
```

**深圳校区：** `EASWebSource.getExamItems(term)`
**本部：** `BenbuEASWebSource.getExamItems(term)`
**威海：** 暂不支持

### 3. 学期匹配流程

```
[考试数据] → [使用exam.termId] → [与term.id精确匹配] → [筛选结果]
```

## 修改指南

### 修改TermItem数据结构时

需要同步修改的文件：
1. `EASWebSource.kt` - 深圳校区学期解析
2. `BenbuEASWebSource.kt` - 本部学期解析
3. `WeihaiEASWebSource.kt` - 威海校区学期解析
4. `ExamViewModel.kt` - 考试筛选逻辑
5. `ScoreViewModel.kt` - 成绩筛选逻辑
6. 其他使用TermItem的地方

### 修改ExamItem数据结构时

需要同步修改的文件：
1. `EASWebSource.kt` - 深圳校区考试解析
2. `BenbuEASWebSource.kt` - 本部考试解析
3. `ExamViewModel.kt` - 考试筛选逻辑

### 修改学期匹配逻辑时

需要同步修改的文件：
1. `ExamViewModel.matchTerm()` - 考试学期匹配
2. `ScoreViewModel.matchTerm()` - 成绩学期匹配（如果存在）
3. 所有使用学期筛选的地方

## 数据格式转换示例

### API返回的原始数据

```json
{
  "XNXQMC": "2026春季",  // 学年学期名称
  "XNXQMC_EN": "2026Spring",
  "XNXQ": "2025-2026-2"  // 如果有完整代码
}
```

### 转换为统一格式

```kotlin
val termItem = TermItem(
    yearCode = "2025-2026",  // 从XNXQ提取或根据XNXQMC推算
    yearName = "2025-2026",
    termCode = "2",          // 从XNXQ提取或根据月份推算
    termName = "春季"         // 从XNXQMC提取
)
// 自动生成 id = "2025-2026-2"
```

## 调试建议

### 检查学期匹配问题

1. 检查 `TermItem.id` 是否正确设置
2. 检查 `ExamItem.termId` 是否正确设置
3. 添加日志查看匹配过程：
   ```kotlin
   LogUtils.d("matchTerm: item.termId='${item.termId}', term.id='${term.id}'")
   ```

### 常见问题

**问题：** 考试数据显示为空
**原因：** termId设置不正确
**解决：** 检查考试数据解析时是否正确设置了termId

**问题：** 学期匹配失败
**原因：** 使用了termName进行匹配而不是ID
**解决：** 改用term.id和exam.termId进行匹配

## 未来扩展

### 其他功能的统一设计

此设计原则也可应用到：
- **成绩查询**：CourseScoreItem使用termId关联学期
- **课表查询**：CourseItem使用termId关联学期
- **教室查询**：ClassroomItem使用termId关联学期

### 入学年份过滤

可以在 `EASRepository.getAllTerms()` 中统一过滤：

```kotlin
val admissionYear = 2024  // 从学号提取
val filteredTerms = allTerms.filter { term ->
    val startYear = term.yearCode.split("-")[0].toInt()
    startYear >= admissionYear
}
```

## 联系方式

如有疑问或需要修改，请参考：
- 本文档：`DATA_STRUCTURE_DESIGN.md`
- TermItem实现：`TermItem.kt`
- ExamItem实现：`ExamItem.kt`
- 三个校区的实现文件
