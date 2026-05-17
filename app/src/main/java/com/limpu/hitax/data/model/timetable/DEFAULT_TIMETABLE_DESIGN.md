# 默认课表设计说明

## 概述

为了防止不同来源的内容互相污染，系统采用"默认课表"设计，统一存放EAS导入的内容和AI创建的内容。

## 设计目的

### 问题背景
1. **EAS导入**：从教务系统导入的课程、考试等
2. **AI创建**：用户通过AI助手创建的课程安排
3. **自定义课表**：用户手动创建的纯净课表

如果混在一起，会导致：
- 用户自定义课表被大量自动内容污染
- 难以区分哪些是用户手动创建的，哪些是系统自动生成的
- 删除或管理时容易误删用户精心制作的内容

### 解决方案
使用专门的"默认课表"存放所有自动生成的内容：
- **EAS导入的课程** → 默认课表
- **考试安排** → 默认课表  
- **AI创建的内容** → 默认课表
- **用户自定义的课表** → 保持独立和纯净

## 默认课表规范

### 命名规则
- 基础名称：`"新建课表"`（来自 `R.string.default_timetable_name`）
- 自动编号：`"新建课表"`, `"新建课表 2"`, `"新建课表 3"` ...
- 编号逻辑：查找现有默认课表，最大编号 + 1

### 识别特征
- `code = ""`（空字符串）表示自定义课表
- `code != ""` 表示EAS课表（如 `"2025-2026-2"`）

### 创建时机
1. **首次导入EAS课程**：如果不存在默认课表，自动创建
2. **首次导入考试**：如果不存在默认课表，自动创建
3. **AI创建内容**：如果不存在默认课表，自动创建

## 代码实现

### 获取或创建默认课表

```kotlin
// 在 ExamDetailFragment.kt 中
private fun importExamToTimetable() {
    // 1. 尝试获取现有默认课表
    val defaultTimetable = timetableDao.getFirstCustomTimetableSync()
    
    if (defaultTimetable == null) {
        // 2. 如果不存在，创建新的默认课表
        val newTable = createDefaultTimetable(timetableDao)
        importExamEvent(newTable, eventItemDao)
    } else {
        // 3. 如果存在，直接使用
        importExamEvent(defaultTimetable, eventItemDao)
    }
}

private fun createDefaultTimetable(timetableDao: TimetableDao): Timetable {
    val defaultPrefix = getString(R.string.default_timetable_name)
    
    // 查找现有默认课表，确定编号
    val existingTables = timetableDao.getTimetableNamesWithDefaultSync("$defaultPrefix%")
    var maxNumber = 0
    for (tableName in existingTables) {
        val number = try {
            tableName.replace(defaultPrefix, "").trim().toInt()
        } catch (e: NumberFormatException) {
            0
        }
        if (number > maxNumber) maxNumber = number
    }
    
    // 创建新的默认课表
    return Timetable().apply {
        id = UUID.randomUUID().toString()
        name = if (maxNumber > 0) "$defaultPrefix ${maxNumber + 1}" else defaultPrefix
        code = "" // 重要：空code表示自定义课表
        startTime = Timestamp(System.currentTimeMillis())
    }
}
```

### AI工具中的使用

```kotlin
// 在 AddTimetableArrangementTool.kt 中
fun addArrangement(arrangement: Arrangement) {
    // 获取或创建默认课表
    val timetable = repository.ensureDefaultCustomTimetableSync()
    
    // 创建事件
    val event = EventItem().apply {
        timetableId = timetable.id
        source = EventItem.SOURCE_AGENT // 标记为AI创建
        // ... 其他字段
    }
    
    // 保存到默认课表
    eventItemDao.insertEventSync(event)
}
```

## 数据库操作

### DAO方法

```kotlin
// TimetableDao.kt
@Query("SELECT * FROM timetable WHERE code is null OR TRIM(code) = '' ORDER BY createdAt ASC LIMIT 1")
fun getFirstCustomTimetableSync(): Timetable?

@Query("SELECT name from timetable where name like :defaultName")
fun getTimetableNamesWithDefaultSync(defaultName: String): List<String>
```

## 用户体验

### 导入考试流程
1. 用户查看考试详情
2. 点击"导入到课表"按钮
3. 系统自动使用默认课表：
   - 如果存在：直接导入
   - 如果不存在：自动创建"新建课表"后导入
4. 提示："已导入到默认课表: 新建课表"

### 防重复机制
- 检查课程名称 + 地点是否已存在
- 如果已存在：提示"该考试已导入默认课表"
- 避免重复导入同一场考试

## 相关文件

### 核心实现
- **考试导入**: `ExamDetailFragment.kt`
- **AI工具**: `AddTimetableArrangementTool.kt`
- **仓储层**: `TimetableRepository.kt`
- **数据访问**: `TimetableDao.kt`

### 字符串资源
```xml
<!-- strings.xml -->
<string name="default_timetable_name">新建课表</string>
```

### 数据库表
```sql
-- timetable 表结构
CREATE TABLE timetable (
    id TEXT PRIMARY KEY,
    name TEXT,                    -- 课表名称
    code TEXT,                    -- EAS代码（空表示自定义）
    startTime TIMESTAMP,          -- 开始时间
    endTime TIMESTAMP,            -- 结束时间
    createdAt TIMESTAMP           -- 创建时间
);

-- events 表结构
CREATE TABLE events (
    id TEXT PRIMARY KEY,
    timetableId TEXT,             -- 关联课表ID
    type TEXT,                    -- CLASS/EXAM/OTHER/TAG
    source TEXT,                  -- EAS_IMPORT/MANUAL/AGENT/ICS_IMPORT
    name TEXT,                    -- 事件名称
    place TEXT,                   -- 地点
    ...
);
```

## 扩展指南

### 添加新的自动内容来源

如果需要添加新的自动内容来源（如ICS导入），应该：

1. **使用默认课表**：
   ```kotlin
   val defaultTimetable = timetableDao.getFirstCustomTimetableSync()
       ?: createDefaultTimetable()
   ```

2. **设置正确的source**：
   ```kotlin
   event.source = EventItem.SOURCE_ICS_IMPORT // 或其他来源标识
   ```

3. **添加防重复检查**：
   ```kotlin
   val isDuplicate = existingEvents.any { event ->
       event.type == EventType &&
       event.name == itemName &&
       event.source == event.source
   }
   ```

## 维护说明

### 检查默认课表使用情况

```sql
-- 查看所有默认课表
SELECT * FROM timetable WHERE code = '' OR code IS NULL;

-- 查看默认课表中的事件数量
SELECT timetableId, COUNT(*) as event_count 
FROM events 
WHERE timetableId IN (
    SELECT id FROM timetable WHERE code = '' OR code IS NULL
)
GROUP BY timetableId;
```

### 清理空的默认课表

```sql
-- 删除没有事件的默认课表
DELETE FROM timetable 
WHERE (code = '' OR code IS NULL) 
AND id NOT IN (SELECT DISTINCT timetableId FROM events);
```

## 注意事项

1. **不要删除默认课表功能**：这是防止数据污染的核心机制
2. **保持一致性**：所有自动生成的内容都必须使用默认课表
3. **用户可见性**：用户可以清楚看到哪些是自动导入的，哪些是自己创建的
4. **可选择性**：用户可以选择将默认课表中的内容复制到自己的课表

## 优势总结

✅ **数据隔离**：自动内容与用户内容分离
✅ **防止污染**：用户自定义课表保持纯净
✅ **易于管理**：可以批量操作默认课表中的内容
✅ **灵活性**：用户可以选择性地将内容复制到自己的课表
✅ **一致性**：所有自动内容统一存放，便于维护
