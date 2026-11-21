package cn.limpu.hita.testutil

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.UUID

/**
 * 每个测试实例用独立前缀的 SharedPreferences，避免写入真实用户数据、也避免测试之间互相污染。
 */
internal class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
    private val prefix = "hita-test-${UUID.randomUUID()}-"
    private val names = mutableSetOf<String>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        names += name
        return super.getSharedPreferences(prefix + name, mode)
    }

    fun clear() {
        names.forEach { super.getSharedPreferences(prefix + it, MODE_PRIVATE).edit().clear().commit() }
    }
}
