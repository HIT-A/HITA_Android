package cn.limpu.hita.data.source.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import cn.limpu.hita.data.model.timetable.Timetable

@Dao
interface TimetableDao {


    /**
     * 根据教务代码查找课表
     */
    @Query("SELECT * FROM timetable WHERE code is :easCode")
    fun getTimetableByEASCodeSync(easCode: String): Timetable?

    @Query("SELECT * FROM timetable WHERE code is :easCode")
    fun getTimetableByEASCode(easCode: String): LiveData<Timetable?>

    @Query(
        """
        SELECT * FROM timetable
        WHERE code IN (:easCodes)
        ORDER BY
            CASE
                WHEN code = :preferredCode THEN 0
                WHEN code = :legacyCode THEN 1
                ELSE 2
            END,
            createdAt DESC
        LIMIT 1
        """
    )
    fun getTimetableByEASCodeCandidates(
        easCodes: List<String>,
        preferredCode: String,
        legacyCode: String
    ): LiveData<Timetable?>

    /**
     * 按候选 EAS code 查找本地课表。
     *
     * 新格式会带校区前缀，例如 BENBU:2025-2026-2；旧版本只保存 term.getCode()。
     * 这里保留 legacyCode 优先级，是为了升级后能复用旧数据，避免同一学期导入出两张课表。
     */
    @Query(
        """
        SELECT * FROM timetable
        WHERE code IN (:easCodes)
        ORDER BY
            CASE
                WHEN code = :preferredCode THEN 0
                WHEN code = :legacyCode THEN 1
                ELSE 2
            END,
            createdAt DESC
        LIMIT 1
        """
    )
    fun getTimetableByEASCodeCandidatesSync(
        easCodes: List<String>,
        preferredCode: String,
        legacyCode: String
    ): Timetable?

    /**
     * 自定义课表：code 为空或空白的课表。
     *
     * 注意：不要把“自定义课表”直接等同于“默认课表”。用户手动创建的课表也在这里。
     */
    @Query("SELECT * FROM timetable WHERE code is null OR TRIM(code) = '' ORDER BY createdAt ASC LIMIT 1")
    fun getFirstCustomTimetableSync(): Timetable?

    @Query("SELECT * FROM timetable order by -startTime")
    fun getTimetables(): LiveData<List<Timetable>>

    @Query("SELECT * FROM timetable order by -startTime")
    fun getTimetablesSync(): List<Timetable>

    /**
     * 历史数据清理使用：只找名字像“默认课表”的自定义课表。
     *
     * 清理逻辑仍需在 Repository 中结合事件来源判断，DAO 不负责决定是否删除。
     */
    @Query("SELECT * FROM timetable WHERE (code is null OR TRIM(code) = '') AND name like :defaultName")
    fun getDefaultNamedCustomTimetablesSync(defaultName: String): List<Timetable>

    @Query("SELECT * FROM timetable WHERE id is :id")
    fun getTimetableById(id: String): LiveData<Timetable>

    @Query("SELECT * FROM timetable WHERE id is :id")
    fun getTimetableByIdSync(id: String): Timetable?

    @Query("UPDATE timetable SET name = :name WHERE id = :id")
    fun renameTimetableSync(id: String, name: String): Int

    /**
     *  获取所有使用默认名字的课表名字
     */
    @Query("SELECT name from timetable where name like :defaultName")
    fun getTimetableNamesWithDefaultSync(defaultName: String): List<String>

    /**
     * 获得离某时间戳最近且已开始的时间表。
     *
     * 这个查询用于“没有显式选择课表”时的兜底，例如考试导入选择目标课表。
     * SQL 保持旧实现不动；如需增强排序规则，应在 Repository 包装新方法。
     */
    @Query("SELECT * from timetable where (:ts-startTime in (select min(:ts-startTime) from timetable where :ts>startTime)) limit 1")
    fun getTimetableClosestToTimestamp(ts: Long): LiveData<Timetable?>

    @Query("SELECT * from timetable where (:ts-startTime in (select min(:ts-startTime) from timetable where :ts>startTime)) limit 1")
    fun getTimetableClosestToTimestampSync(ts: Long): Timetable?


    @Query("select count(*) from timetable")
    fun geeTimetableCount(): LiveData<Int>

    @Query("select DISTINCT place from events where place like :string AND hidden = 0")
    fun searchLocation(string: String): LiveData<List<String>>


    /**
     * 保存课表
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveTimetableSync(data: Timetable)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveTimetablesSync(data: List<Timetable>)

    @Delete
    fun deleteTimetablesSync(timetables: List<Timetable>)

    @Query("delete from timetable where id in (:ids)")
    fun deleteTimetablesInIdsSync(ids: List<String>)


    @Query("SELECT * FROM timetable WHERE id in (:ids)")
    fun getTimetablesInIdsSync(ids: List<String>): List<Timetable>



    @Query("delete from timetable")
    fun clear()
}
