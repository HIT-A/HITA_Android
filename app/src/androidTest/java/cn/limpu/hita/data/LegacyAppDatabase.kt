package cn.limpu.hita.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/**
 * 按导出的 schema JSON 建一个旧版本 AppDatabase，用于迁移测试。
 *
 * 不用 room-testing 的 MigrationTestHelper：它依赖 kotlinx-serialization 1.8，
 * 而应用运行时固定为 1.7.3，设备上解析 schema 会 AbstractMethodError。
 */
internal fun createLegacyAppDatabase(name: String, version: Int): SupportSQLiteOpenHelper {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    context.deleteDatabase(name)
    val schema = legacySchema(version)
    val callback = object : SupportSQLiteOpenHelper.Callback(version) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", table))
                entity.optJSONArray("indices")?.let { indices ->
                    for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql")
                            .replace("$" + "{TABLE_NAME}", table))
                    }
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("Unexpected legacy database upgrade")
        }
    }
    return FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
    )
}

/** 导出 schema 中该版本的全部表名。 */
internal fun legacySchemaTables(version: Int): Set<String> {
    val entities = legacySchema(version).getJSONArray("entities")
    return (0 until entities.length()).mapTo(mutableSetOf()) { entities.getJSONObject(it).getString("tableName") }
}

private fun legacySchema(version: Int): JSONObject =
    InstrumentationRegistry.getInstrumentation().context.assets
        .open("cn.limpu.hita.data.AppDatabase/$version.json")
        .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
