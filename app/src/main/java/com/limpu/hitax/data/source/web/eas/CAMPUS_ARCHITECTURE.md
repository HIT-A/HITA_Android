# 三校区EAS系统架构设计说明

## 概述

HITAX应用支持哈尔滨工业大学三个校区的教务系统：
- **本部**（哈尔滨）
- **深圳校区**
- **威海校区**

## 核心架构设计

### 设计模式：策略模式 + 统一接口

```
┌─────────────────────────────────────────────────────┐
│                    UI层                              │
│  ExamActivity, ScoreActivity, ClassroomActivity等   │
│  完全不知道是哪个校区，只调用统一接口                  │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│                   Repository层                       │
│  EASRepository                                       │
│  - 根据token.campus动态选择对应的实现                │
│  - 提供统一的业务接口给UI层                          │
│  - 处理校区特定的业务逻辑                            │
└─────────────────────────────────────────────────────┘
                        ↓
        ┌───────────────┼───────────────┐
        ↓               ↓               ↓
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  深圳校区      │ │   本部        │ │  威海校区    │
│EASWebSource  │ │BenbuEASSource│ │WeihaiEASSource│
│  (新API)     │ │   (旧API)     │ │  (旧API)     │
└──────────────┘ └──────────────┘ └──────────────┘
```

### 接口定义：EASService

所有校区都实现 `EASService` 接口，定义在：
`app/src/main/java/com/limpu/hitax/data/source/web/service/EASService.kt`

```kotlin
interface EASService {
    fun login(username: String, password: String, code: String?): LiveData<DataState<EASToken>>
    fun getAllTerms(token: EASToken): LiveData<DataState<List<TermItem>>>
    fun getExamItems(token: EASToken, term: TermItem?): LiveData<DataState<List<ermItem>>>
    // ... 其他统一接口
}
```

### 路由逻辑：EASRepository

Repository根据用户的校区动态选择实现：

```kotlin
class EASRepository(private val easToken: EASToken) {
    private val shenzhenService = EASWebSource()
    private val benbuService = BenbuEASWebSource()
    private val weihaiService = WeihaiEASWebSource()

    private fun getService(campus: EASToken.Campus): EASService {
        return when (campus) {
            EASToken.Campus.SHENZHEN -> shenzhenService
            EASToken.Campus.BENBU -> benbuService
            EASToken.Campus.WEIHAI -> weihaiService
        }
    }

    fun getAllTerms(): LiveData<DataState<List<TermItem>>> {
        return getService(easToken.campus).getAllTerms(easToken)
    }
}
```

## 三个校区的差异

### 1. API版本差异

| 校区 | API地址 | 认证方式 | 特点 |
|------|---------|----------|------|
| 深圳 | mjw.hitsz.edu.cn/incoSpringBoot | Bearer Token | 新API，返回JSON |
| 本部 | jwdb.hit.edu.cn | Cookie + Session | 旧API，返回HTML |
| 威海 | jwdb.hit.edu.cn | Cookie + Session | 旧API，返回HTML |

### 2. 功能支持差异

| 功能 | 深圳 | 本部 | 威海 |
|------|------|------|------|
| 登录 | ✅ | ✅ | ✅ |
| 学期查询 | ✅ | ✅ | ✅ |
| 考试查询 | ✅ | ✅ | ❌ |
| 成绩查询 | ✅ | ✅ | ✅ |
| 课表查询 | ✅ | ✅ | ✅ |
| 空教室查询 | ✅ | ✅ | ✅ |

### 3. 考试功能差异

#### 深圳校区
- **考试类型**: API返回的所有考试都标记为"期末"，无实际分类意义
- **UI处理**: 不显示"全部/期中/期末"筛选器
- **数据格式**: JSON，字段清晰（KCMC课程名、KSRQ日期、KSJTSJ时间等）

#### 本部
- **考试类型**: 有明确的期中期末分类
- **UI处理**: 显示"全部/期中/期末"筛选器
- **数据格式**: HTML，需要解析DOM

#### 威海
- **考试查询**: 暂不支持
- **UI处理**: 显示"暂不支持"提示

## UI层如何处理校区差异

### 方法1：通过Repository获取校区信息

```kotlin
// 在ViewModel中
class ExamViewModel(private val easRepo: EASRepository) : ViewModel() {
    fun shouldShowExamTypeFilter(): Boolean {
        return easRepo.getCurrentCampus() == EASToken.Campus.BENBU
    }
}

// 在Activity中
if (viewModel.shouldShowExamTypeFilter()) {
    // 显示考试类型选择器
} else {
    // 隐藏考试类型选择器
}
```

### 方法2：在数据模型中添加校区标识

```kotlin
// 在ExamItem中添加
var campusName: String? = null  // "深圳校区", "本部", "威海校区"

// 在UI中根据校区做不同处理
when (item.campusName) {
    "深圳校区" -> // 深圳特有的显示逻辑
    "本部" -> // 本部特有的显示逻辑
    "威海校区" -> // 威海特有的显示逻辑
}
```

## 修改某个校区功能时的步骤

### 1. 修改数据源实现
例如：修改深圳校区的考试查询
- 文件：`EASWebSource.kt`
- 方法：`getExamItems(token: EASToken, term: TermItem?)`

### 2. 检查是否影响统一数据格式
- 确保 `TermItem` 的ID格式一致
- 确保 `ExamItem` 的termId设置正确
- 参考：`DATA_STRUCTURE_DESIGN.md`

### 3. 更新UI层（如果需要）
- 如果新校区有特殊的UI需求，添加判断逻辑
- 在ViewModel中添加 `shouldShowXXX()` 方法
- 在Activity/Fragment中根据校区显示/隐藏相应UI

### 4. 更新文档
- 更新本说明文档
- 如果数据结构有变化，更新 `DATA_STRUCTURE_DESIGN.md`

## 新增校区支持

如果需要新增第四个校区，需要：

1. **实现EASService接口**
   ```kotlin
   class NewCampusEASWebSource : EASService {
       override fun login(...) { /* 新校区的登录逻辑 */ }
       override fun getAllTerms(...) { /* 新校区的学期查询 */ }
       // ... 实现所有接口方法
   }
   ```

2. **在EASToken中添加新校区枚举**
   ```kotlin
   enum class Campus {
       SHENZHEN, BENBU, WEIHAI, NEW_CAMPUS
   }
   ```

3. **在EASRepository中注册新校区**
   ```kotlin
   private val newCampusService = NewCampusEASWebSource()

   private fun getService(campus: EASToken.Campus): EASService {
       return when (campus) {
           // ... 现有校区
           EASToken.Campus.NEW_CAMPUS -> newCampusService
       }
   }
   ```

4. **处理新校区的特殊需求**
   - 在UI层添加校区判断
   - 更新数据转换逻辑
   - 测试所有功能

## 常见问题

### Q: 为什么不在UI层直接使用if-else判断校区？
A: 因为UI层不应该知道具体的校区实现细节。应该通过ViewModel/Repository提供的方法来判断，保持架构的清晰性。

### Q: 三个校区的数据格式不统一怎么办？
A: 在各个校区的WebSource实现中统一转换为标准格式（TermItem、ExamItem等），确保UI层不需要关心数据来源。

### Q: 某个校区的API变了怎么办？
A: 只需要修改对应校区的WebSource实现，不需要改动UI层和其他校区的代码。

### Q: 如何测试所有校区的功能？
A: 在登录时选择不同的校区，然后测试各项功能。确保某个校区的改动不会影响其他校区。

## 相关文档

- **数据结构设计**: `DATA_STRUCTURE_DESIGN.md`
- **TermItem定义**: `TermItem.kt`
- **ExamItem定义**: `ExamItem.kt`
- **接口定义**: `EASService.kt`
