package cn.limpu.hita.data.model.campus

/**
 * 大学城图书馆智慧墙的在馆人数。
 *
 * 字段来源：`GET /smart-wall_api/smart-walls/peoples` 的 `model`，
 * `curInNum` 是当前在馆人数，`todayInNum` 是今日累计入馆。
 */
data class LibraryPeoples(
    val currentInLibrary: Int,
    val todayVisitors: Int,
)

/**
 * 静音仓总体占用。
 *
 * 字段来源：`GET /smart-wall_api/smart-walls/room-nums` 的 `model`，
 * `haveUse` 是已用间数，`useAble` 是可用间数。
 */
data class LibraryRoomNums(
    val occupied: Int,
    val available: Int,
)

/**
 * 一次抓取到的智慧墙快照。
 *
 * 两个接口是同一面墙的两个分区，缺失任一半都会让「在馆 × 静音仓」的展示错位，
 * 因此快照只在两边都成功后才成立。
 */
data class LibrarySmartWallSnapshot(
    val peoples: LibraryPeoples,
    val rooms: LibraryRoomNums,
    val fetchedAtMillis: Long,
)
