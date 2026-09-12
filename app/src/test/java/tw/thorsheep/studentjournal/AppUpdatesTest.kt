package tw.thorsheep.studentjournal

import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun json(tag: String = "v3.1.0", url: String = "https://github.com/ThorSheep/Simple-App/releases/download/v3.1.0/simple-app-v3.1.0.apk") = """{"tag_name":"$tag","name":"Simple App $tag","body":"更新說明","draft":false,"prerelease":false,"assets":[{"name":"simple-app-v3.1.0.apk","browser_download_url":"$url"}]}"""
    @Test fun parsesOfficialReleaseOnly() {
        val release = AppUpdates.parseRelease(json())
        assertEquals(30100, release.versionCode); assertEquals("v3.1.0", release.tag)
    }
    @Test fun rejectsInvalidOrForeignAsset() {
        assertNull(AppUpdates.releaseCode("v3.1")); assertNull(AppUpdates.releaseCode("v3.1.0-rc1"))
        assertThrows(IllegalArgumentException::class.java) { AppUpdates.parseRelease(json(url = "https://example.org/app.apk")) }
    }
    @Test fun supportsExpectedVersionRange() { assertEquals(9999999, AppUpdates.releaseCode("999.99.99")); assertNull(AppUpdates.releaseCode("1000.0.0")) }
}
