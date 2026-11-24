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
    fun parseGithubReleaseExtractsSha256FromSha256SumsFallbackWhenAssetDigestMissing() {
        val newerBuild = LinDoDiagnosticLogger.APP_BUILD + 5
        val rawRelease = """
            {
              "tag_name": "v1.1.1",
              "published_at": "2026-11-24T12:00:00Z",
              "body": "- 统一全端版本号与更新完整性校验",
              "assets": [
                {
                  "name": "lindo-1.1.1-build$newerBuild.apk",
                  "size": 14500000,
                  "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.1/lindo-1.1.1-build$newerBuild.apk"
                },
                {
                  "name": "SHA256SUMS.txt",
                  "size": 512,
                  "browser_download_url": "https://github.com/lucaslus/LinDo/releases/download/v1.1.1/SHA256SUMS.txt"
                }
              ]
            }
        """.trimIndent()
        val sumsRaw = """
            1111111111111111111111111111111111111111111111111111111111111111  Lindo-1.1.1-macos-arm64.zip
            FE89AB0123456789FE89AB0123456789FE89AB0123456789FE89AB0123456789  *lindo-1.1.1-build$newerBuild.apk
        """.trimIndent()

        val parsed = AppUpdateManager.parseGithubRelease(
            releaseRawJson = rawRelease,
            manifestRawJson = null,
            sha256SumsRaw = sumsRaw
        )
        assertTrue(parsed.hasUpdate)
        assertEquals("1.1.1", parsed.latestVersionName)
        assertEquals(newerBuild, parsed.latestVersionCode)
        assertEquals(
            "fe89ab0123456789fe89ab0123456789fe89ab0123456789fe89ab0123456789",
            parsed.sha256
        )
    }

    @Test
    fun extractNotesFromMarkdownStripsGithubFullChangelogAndRawMarkdownNoise() {
        val githubAutoBody = """
            ## What's Changed
            * **极简更新弹窗**：移除多余装饰与构建号 by @lucaslus in https://github.com/lucaslus/LinDo/pull/12
            - chore(release): unify v1.1.1 manifests
            **Full Changelog**: https://github.com/lucaslus/LinDo/commits/v1.1.1
        """.trimIndent()
        val notes = AppUpdateManager.extractNotesFromMarkdown(githubAutoBody)
        assertEquals(listOf("极简更新弹窗：移除多余装饰与构建号"), notes)

        val emptyChangelogOnly = "**Full Changelog**: https://github.com/lucaslus/LinDo/commits/v1.1.0"
        assertTrue(AppUpdateManager.extractNotesFromMarkdown(emptyChangelogOnly).isEmpty())
    }

    @Test
    fun cleanupObsoleteCacheEvictsOlderApkWhenNewerVersionArrivesAndMarksExactVerifiedApkReady() {
        val tempDir = java.nio.file.Files.createTempDirectory("lindo_update_gc_test").toFile()
        try {
            AppUpdateManager.cacheDirProvider = { tempDir }
            val oldApk = java.io.File(tempDir, "Lindo-v1.1.0-build100.apk")
            oldApk.writeBytes(ByteArray(120_000) { 0x41 })

            val nextBuild = LinDoDiagnosticLogger.APP_BUILD + 2
            val newApk = java.io.File(tempDir, "Lindo-v1.1.2-build$nextBuild.apk")
            val payload = ByteArray(150_000) { (it % 251).toByte() }
            newApk.writeBytes(payload)
            val sha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { "%02x".format(it) }

            val targetInfo = MobileUpdateInfo(
                hasUpdate = true,
                currentVersionName = LinDoDiagnosticLogger.APP_VERSION_NAME,
                currentVersionCode = LinDoDiagnosticLogger.APP_BUILD,
                latestVersionName = "1.1.2",
                latestVersionCode = nextBuild,
                apkUrl = "https://github.com/lucaslus/LinDo/releases/download/v1.1.2/lindo-1.1.2-build$nextBuild.apk",
                sha256 = sha,
                sizeBytes = payload.size.toLong(),
                notes = emptyList(),
                updatedAt = "2026-11-24"
            )

            AppUpdateManager.cleanupObsoleteCache(keepLatest = targetInfo)
            assertFalse(oldApk.exists(), "Older v1.1.0 APK must be evicted when v1.1.2 is latest")
            assertTrue(newApk.exists(), "Verified v1.1.2 APK must be retained")
            assertTrue(AppUpdateManager.cachedApkReady)

            // Post-upgrade: when hasUpdate=false, all cached APKs are garbage-collected
            AppUpdateManager.cleanupObsoleteCache(
                keepLatest = targetInfo.copy(hasUpdate = false, latestVersionCode = LinDoDiagnosticLogger.APP_BUILD)
            )
            assertFalse(newApk.exists(), "Post-upgrade cache must be reclaimed")
            assertFalse(AppUpdateManager.cachedApkReady)
        } finally {
            AppUpdateManager.cacheDirProvider = null
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun bindInstalledVersionOverridesRuntimeVersionAndMatchesBuildGradleDefaults() {
        val origName = LinDoDiagnosticLogger.APP_VERSION_NAME
        val origBuild = LinDoDiagnosticLogger.APP_BUILD
        try {
            LinDoDiagnosticLogger.bindInstalledVersion("1.2.3", 123)
            assertEquals("1.2.3", LinDoDiagnosticLogger.APP_VERSION_NAME)
            assertEquals(123, LinDoDiagnosticLogger.APP_BUILD)
            assertEquals("Lindo v1.2.3 · build 123", LinDoDiagnosticLogger.APP_VERSION_DISPLAY)
        } finally {
            LinDoDiagnosticLogger.bindInstalledVersion(origName, origBuild)
        }

        val candidates = listOf(
            java.io.File("build.gradle.kts"),
            java.io.File("composeApp/build.gradle.kts")
        )
        val gradleFile = candidates.firstOrNull { it.exists() }
        if (gradleFile != null) {
            val content = gradleFile.readText()
            val gradleVer = Regex("""versionName\s*=\s*"([^"]+)"""").find(content)?.groupValues?.get(1)
            val gradleCode = Regex("""versionCode\s*=\s*(\d+)""").find(content)?.groupValues?.get(1)?.toIntOrNull()
            assertEquals(gradleVer, LinDoDiagnosticLogger.DEFAULT_APP_VERSION_NAME)
            assertEquals(gradleCode, LinDoDiagnosticLogger.DEFAULT_APP_BUILD)
        }
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
