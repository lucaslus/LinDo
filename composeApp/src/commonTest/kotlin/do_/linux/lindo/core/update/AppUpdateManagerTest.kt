package do_.linux.lindo.core.update

import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateManagerTest {
    @Test
    fun parseGithubReleaseDirectlyFromStandardAssetsAndDigest() {
        val newerBuild = LinDoDiagnosticLogger.APP_BUILD + 3
        val rawRelease = """
            {
              "tag_name": "v1.0.39",
              "published_at": "2026-11-17T14:30:00Z",
              "body": "## 更新内容\n- 楼层未读标识阅读后平滑淡出\n- 纯 GitHub Release 自动升级与 SHA-256 校验",
              "assets": [
                {
                  "name": "Lindo-1.0.39-macos-arm64.zip",
                  "size": 8912345,
                  "digest": "sha256:1111111111111111111111111111111111111111111111111111111111111111",
                  "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.0.39/Lindo-1.0.39-macos-arm64.zip"
                },
                {
                  "name": "lindo-0.2.32-build$newerBuild.apk",
                  "size": 12158684,
                  "digest": "sha256:ba47e088b68b0b6d657054197b64a1e47a25947c684bda015da2671c2999629e",
                  "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.0.39/lindo-0.2.32-build$newerBuild.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdateManager.parseGithubRelease(rawRelease)
        assertTrue(parsed.hasUpdate)
        assertEquals("0.2.32", parsed.latestVersionName)
        assertEquals(newerBuild, parsed.latestVersionCode)
        assertEquals(
            "https://github.com/lucaslus/LinDo/releases/download/v1.0.39/lindo-0.2.32-build$newerBuild.apk",
            parsed.apkUrl
        )
        assertEquals(
            "ba47e088b68b0b6d657054197b64a1e47a25947c684bda015da2671c2999629e",
            parsed.sha256
        )
        assertEquals(12158684L, parsed.sizeBytes)
        assertEquals(2, parsed.notes.size)
        assertEquals("2026-11-17", parsed.updatedAt)
    }

    @Test
    fun parseGithubReleaseReportsUpToDateWhenBuildMatches() {
        val currentBuild = LinDoDiagnosticLogger.APP_BUILD
        val rawRelease = """
            {
              "tag_name": "v1.0.38",
              "published_at": "2026-11-17T14:30:00Z",
              "body": "- Initial release",
              "assets": [
                {
                  "name": "lindo-0.2.32-build$currentBuild.apk",
                  "size": 12000000,
                  "digest": "sha256:abcdef",
                  "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.0.38/lindo-0.2.32-build$currentBuild.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = AppUpdateManager.parseGithubRelease(rawRelease)
        assertFalse(parsed.hasUpdate)
        assertEquals(currentBuild, parsed.latestVersionCode)
    }

    @Test
    fun liveGithubReleaseEndToEndWhenEnabled() = kotlinx.coroutines.test.runTest {
        val mode = System.getenv("LINDO_LIVE_GH_RELEASE_MODE").orEmpty()
        if (mode.isEmpty()) return@runTest

        val res = AppUpdateManager.checkForUpdates(openDialogWhenAvailable = false)
        assertTrue(res.isSuccess, "checkForUpdates failed: ${res.exceptionOrNull()}")
        val info = res.getOrThrow()

        if (mode == "expect_empty") {
            assertFalse(info.hasUpdate, "Expected hasUpdate=false when GitHub has 0 releases")
            return@runTest
        }

        if (mode == "expect_update") {
            assertTrue(info.hasUpdate, "Expected hasUpdate=true from live GitHub Release")
            assertTrue(
                info.apkUrl.startsWith("https://github.com/lucaslus/LinDo/releases/download/"),
                "Unexpected apkUrl: ${info.apkUrl}"
            )
            assertEquals(64, info.sha256.length, "Expected 64-hex SHA-256 from GitHub Release")

            val tempDir = java.nio.file.Files.createTempDirectory("lindo_gh_apk_e2e").toFile()
            try {
                AppUpdateManager.cacheDirProvider = { tempDir }
                val verifiedFile = AppUpdateManager.downloadAndVerifyApkToCache(info)
                assertTrue(verifiedFile.exists() && verifiedFile.length() > 1_000_000L)
            } finally {
                AppUpdateManager.cacheDirProvider = null
                tempDir.deleteRecursively()
            }
        }
    }
}
