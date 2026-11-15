package cn.limpu.hita.ui.main.navigation

import cn.limpu.hita.data.model.eas.EASToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「扩展」栏的可见性规则。
 *
 * 最容易写错的两点：未登录时 campus 仍默认深圳；非深圳账号不应看到深圳专属扩展。
 * 这两条必须被测试锁住，否则会出现「未登录也能进校园卡」。
 */
class CampusExtensionTest {

    @Test
    fun `not signed in yields no extensions even though campus defaults to shenzhen`() {
        val entries = CampusExtension.available(EASToken.Campus.SHENZHEN, loggedIn = false)
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `shenzhen signed in yields every extension`() {
        val entries = CampusExtension.available(EASToken.Campus.SHENZHEN, loggedIn = true)
        assertEquals(CampusExtension.entries.toList(), entries)
    }

    @Test
    fun `benbu signed in yields none because services are shenzhen only`() {
        assertTrue(CampusExtension.available(EASToken.Campus.BENBU, loggedIn = true).isEmpty())
    }

    @Test
    fun `weihai signed in yields none`() {
        assertTrue(CampusExtension.available(EASToken.Campus.WEIHAI, loggedIn = true).isEmpty())
    }

    @Test
    fun `null campus yields none`() {
        assertTrue(CampusExtension.available(null, loggedIn = true).isEmpty())
    }

    @Test
    fun `empty result means the whole section is hidden`() {
        // 与 UI 的 if (entries.isNotEmpty()) 契约一致：空列表时整栏不渲染。
        assertTrue(CampusExtension.available(EASToken.Campus.BENBU, loggedIn = true).isNotEmpty().not())
    }
}
