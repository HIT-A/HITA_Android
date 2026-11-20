package cn.limpu.hita.data.source.web.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智慧墙签名规则（接口文档 §4.2）。
 *
 * 固定种子 → 固定 MD5 链，测试不依赖随机数，因此可以逐字节断言 URL。
 */
class LibrarySmartWallSignerTest {

    @Test
    fun `md5Hex is 32 lowercase hex chars`() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", LibrarySmartWallSigner.md5Hex("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", LibrarySmartWallSigner.md5Hex(""))
        val nonce = LibrarySmartWallSigner.nonce("hita-nonce")
        assertEquals(32, nonce.length)
        assertTrue(nonce.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("3b37892b403f8e5dcb36b0d0b92bcf0d", nonce)
    }

    @Test
    fun `signature is md5 of lang nonce and salt in that order`() {
        val nonce = LibrarySmartWallSigner.nonce("hita-nonce")
        assertEquals(
            "f0445593f782e93274fcacb118c86b61",
            LibrarySmartWallSigner.signature(nonce),
        )
        // 原文顺序：lang=zh_CN + nonce=<nonce> + wm-receive-confirm
        assertEquals(
            LibrarySmartWallSigner.md5Hex("lang=zh_CNnonce=${nonce}wm-receive-confirm"),
            LibrarySmartWallSigner.signature(nonce),
        )
    }

    @Test
    fun `signed url carries lang nonce and signature`() {
        val url = LibrarySmartWallSigner.signedUrl(
            baseUrl = "https://www.utszlib.edu.cn/smart-wall_api",
            path = "smart-walls/peoples",
            seed = "hita-nonce",
        )
        assertEquals(
            "https://www.utszlib.edu.cn/smart-wall_api/smart-walls/peoples" +
                "?lang=zh_CN&nonce=3b37892b403f8e5dcb36b0d0b92bcf0d" +
                "&signature=f0445593f782e93274fcacb118c86b61",
            url,
        )
    }

    @Test
    fun `path slashes are normalized on both sides`() {
        val url = LibrarySmartWallSigner.signedUrl(
            baseUrl = "https://www.utszlib.edu.cn/smart-wall_api/",
            path = "/smart-walls/room-nums/",
            seed = "seed",
        )
        assertTrue(url.startsWith("https://www.utszlib.edu.cn/smart-wall_api/smart-walls/room-nums?"))
        assertFalse(url.contains("//smart-walls"))
    }

    @Test
    fun `salt and lang match the documented constants`() {
        assertEquals("wm-receive-confirm", LibrarySmartWallSigner.SALT)
        assertEquals("zh_CN", LibrarySmartWallSigner.LANG)
    }
}
