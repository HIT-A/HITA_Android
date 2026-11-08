package cn.limpu.hita.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 备份规则与实际偏好文件名必须逐字匹配：写错一个字母，凭据就会悄悄进入云端备份。
 * 这里同时校验 Android 11 及以下的 full-backup-content 与 Android 12+ 的
 * data-extraction-rules（云备份 + 设备迁移）。
 *
 * 用例直接读仓库真实资源文件，不复制常量，避免规则改动后测试失去意义。
 */
class SecurityResourceRulesTest {

    private val appRes = File("src/main/res/xml")

    /** 全仓库含凭据/隐私的偏好文件名（含 hitauser 模块的 local_user_profile）。 */
    private val sensitivePreferences = listOf(
        "local_eas_token.xml",
        "local_eas_token_secure_v2.xml",
        "local_eas_token_secure_v2_fallback.xml",
        "local_eas_credentials.xml",
        "ai_settings.xml",
        "ai_settings_secure_v2.xml",
        "grader_secure_v1.xml",
        "local_user_profile.xml",
    )

    @Test
    fun `full backup content excludes every credential preference`() {
        val xml = appRes.resolve("analytics_backup_rules.xml").readText()
        sensitivePreferences.forEach { name ->
            assertTrue(
                "full-backup-content 必须排除 $name",
                xml.contains("""<exclude domain="sharedpref" path="$name" />""")
            )
        }
        assertTrue(
            "WebView cookie jar 含 CASTGC/WebVPN 票据，必须排除",
            xml.contains("""<exclude domain="root" path="app_webview/" />""")
        )
    }

    @Test
    fun `cloud backup and device transfer exclude the same credential preferences`() {
        val xml = appRes.resolve("analytics_data_extraction_rules.xml").readText()
        val cloudStart = xml.indexOf("<cloud-backup>")
        val cloudEnd = xml.indexOf("</cloud-backup>")
        val transferStart = xml.indexOf("<device-transfer>")
        val transferEnd = xml.indexOf("</device-transfer>")
        assertTrue("规则文件必须同时定义 cloud-backup 与 device-transfer", cloudStart in 0 until cloudEnd && transferStart > cloudEnd && transferEnd > transferStart)

        val cloud = xml.substring(cloudStart, cloudEnd)
        val transfer = xml.substring(transferStart, transferEnd)
        sensitivePreferences.forEach { name ->
            val rule = """<exclude domain="sharedpref" path="$name" />"""
            assertTrue("cloud-backup 必须排除 $name", cloud.contains(rule))
            assertTrue("device-transfer 必须排除 $name", transfer.contains(rule))
        }
        listOf(cloud, transfer).forEach { section ->
            assertTrue(
                "cookie jar 必须同时排除云备份与设备迁移",
                section.contains("""<exclude domain="root" path="app_webview/" />""")
            )
        }
    }

    @Test
    fun `non sensitive analytics exclusions are preserved`() {
        val xml = appRes.resolve("analytics_data_extraction_rules.xml").readText()
        listOf("usage_analytics.xml", "usage_analytics_v2.db", "stats.xml").forEach { name ->
            assertTrue("$name 的排除规则不能回退", xml.contains(name))
        }
    }

    @Test
    fun `app file provider paths are minimal`() {
        val xml = appRes.resolve("file_paths.xml").readText()
        assertTrue("不得暴露整个根分区", !xml.contains("<root-path"))
        assertTrue("不得暴露外部缓存目录", !xml.contains("<external-cache-path"))
        assertTrue("不得再有第二个全量 external-path", Regex("<external-path[^>]*path=\"\\s*\\.").findAll(xml).count() == 0)
        // 真实调用点需要的能力必须保留
        assertTrue("token 附件走 cacheDir", xml.contains("<cache-path"))
        assertTrue("ICS 导出走 filesDir 兜底", xml.contains("<files-path"))
        assertTrue("ICS 导出走 external files", xml.contains("<external-files-path"))
        assertTrue("APK 安装只放行 Downloads 子目录", xml.contains("path=\"Download/\""))
    }

    @Test
    fun `style file provider paths drop root and whole external storage`() {
        val xml = File("../style/src/main/res/xml/file_paths.xml").readText()
        assertTrue("style 不得暴露根分区", !xml.contains("<root-path"))
        assertTrue("style 不得暴露外部缓存", !xml.contains("<external-cache-path"))
        assertTrue(
            "style 不得暴露整个外部存储",
            Regex("<external-path[^>]*path=\"\\s*\\.").findAll(xml).count() == 0
        )
    }

    @Test
    fun `network security config forbids cleartext by default`() {
        val xml = appRes.resolve("network_security_config.xml").readText()
        assertTrue(
            "默认必须禁止明文，只允许白名单例外",
            xml.contains("""<base-config cleartextTrafficPermitted="false">""")
        )
        listOf("vpn.hit.edu.cn", "ivpn.hit.edu.cn", "today.hitwh.edu.cn",
            "www.hitsz.edu.cn", "faculty.hitsz.edu.cn", "jw.hitsz.edu.cn").forEach { host ->
            assertTrue("校园 http 服务需要显式放行：$host", xml.contains(host))
        }
        assertTrue("不得放行任意域名", !xml.contains("""<domain includeSubdomains="true">hitsz.edu.cn"""))
        assertTrue("不得放行任意域名(hit.edu.cn)", !xml.contains("""<domain includeSubdomains="true">hit.edu.cn"""))
    }

    @Test
    fun `manifests no longer enable cleartext globally`() {
        val appManifest = File("src/main/AndroidManifest.xml").readText()
        val styleManifest = File("../style/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "app manifest 应改用 networkSecurityConfig",
            appManifest.contains("""android:networkSecurityConfig="@xml/network_security_config"""")
        )
        assertTrue(
            "app manifest 不得保留 usesCleartextTraffic",
            !appManifest.contains("""android:usesCleartextTraffic="true"""")
        )
        assertTrue(
            "style manifest 不得打开全局明文",
            !styleManifest.contains("""android:usesCleartextTraffic="true"""")
        )
    }
}
