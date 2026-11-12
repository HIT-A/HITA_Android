package cn.limpu.hita.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新检查的版本解析。
 *
 * 关键约束：GitHub 的 immutable releases 会永久占用用过的 tag 名，误操作后只能改用
 * `v3.2.0-release` 这类 tag 重发。这类后缀必须按**正式版**解析，否则已装同号正式版的
 * 用户会永远收不到后续修复更新。
 */
class UpdateVersionParseTest {

    private fun parse(tag: String) = UpdateRepository.ParsedVersion.parse(tag)!!

    @Test
    fun `release markers parse as stable versions`() {
        for (tag in listOf("v3.2.0-release", "v3.2.0-final", "v3.2.0-ga", "v3.2.0-stable")) {
            val v = parse(tag)
            assertNull(tag, v.preLabel)
            assertEquals(tag, "3.2.0", v.displayName())
            assertEquals(tag, parse("v3.2.0").toVersionCode(), v.toVersionCode())
        }
    }

    @Test
    fun `release tag is not ranked below the same stable version`() {
        val stable = parse("v3.2.0")
        val retagged = parse("v3.2.0-release")
        // 同号正式版与重命名 tag 必须等价，不能互相判为「更旧」。
        assertEquals(0, retagged.compareTo(stable))
        assertEquals(0, stable.compareTo(retagged))
    }

    @Test
    fun `older stable users are offered the retagged release`() {
        assertTrue(parse("v3.2.0-release") > parse("v3.1.2"))
    }

    @Test
    fun `real prerelease labels still rank below stable`() {
        for (tag in listOf("v3.2.0-alpha.1", "v3.2.0-beta.2", "v3.2.0-rc.1")) {
            val v = parse(tag)
            assertTrue(tag, v.preLabel != null)
            assertTrue(tag, v < parse("v3.2.0"))
        }
        // 预发布之间仍按 alpha < beta < rc 排序。
        assertTrue(parse("v3.2.0-alpha.1") < parse("v3.2.0-beta.1"))
        assertTrue(parse("v3.2.0-beta.1") < parse("v3.2.0-rc.1"))
    }

    @Test
    fun `plain and two segment versions parse`() {
        assertEquals("3.2.0", parse("v3.2.0").displayName())
        assertEquals("3.2.0", parse("3.2").displayName())
        assertNull(UpdateRepository.ParsedVersion.parse(""))
        assertNull(UpdateRepository.ParsedVersion.parse("not-a-version"))
    }
}
