package do_.linux.lindo.core.update

import com.sun.net.httpserver.HttpServer
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class UpdatePromptTest {
    @Test
    fun laterKeepsManualUpdateAvailableAndNewBuildPromptsAgain() = runBlocking {
        val manager = AppUpdateManager
        val oldRelease = manager.lastPromptedRelease
        val oldPersist = manager.persistPromptedRelease
        val oldInstaller = manager.apkInstaller
        val oldCache = manager.cacheDirProvider
        val cache = Files.createTempDirectory("lindo-update-test").toFile()
        val saved = mutableListOf<String>()
        val bytes = ByteArray(120_000) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base = "http://127.0.0.1:${server.address.port}"
        val installedBuild = LinDoDiagnosticLogger.APP_BUILD
        var build = installedBuild
        var downloads = 0
        var installs = 0
        server.createContext("/release") { exchange ->
            val release = """{"tag_name":"v1.5.0","assets":[{"name":"lindo-1.5.0-build$build.apk",
                "digest":"sha256:$digest","size":${bytes.size},"browser_download_url":"$base/app.apk"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, release.size.toLong())
            exchange.responseBody.use { it.write(release) }
        }
        server.createContext("/app.apk") { exchange ->
            downloads++
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            manager.cacheDirProvider = { cache }
            manager.lastPromptedRelease = ""
            manager.persistPromptedRelease = { saved.add(it) }
            manager.apkInstaller = { file ->
                assertContentEquals(bytes, file.readBytes())
                installs++
                Result.success(Unit)
            }
            suspend fun check(manual: Boolean = false) =
                manager.checkForUpdates(manual, "$base/release").getOrThrow()

            check()
            manager.openDialog(automatic = true)
            assertFalse(manager.dialogVisible)
            build++
            check()
            manager.openDialog(automatic = true)
            assertTrue(manager.dialogVisible && manager.automaticPrompt)
            manager.dismissDialog()
            check()
            manager.openDialog(automatic = true)
            assertFalse(manager.dialogVisible)
            val release = check(manual = true)
            assertTrue(manager.dialogVisible && !manager.automaticPrompt)
            assertEquals(0, downloads)
            manager.downloadAndInstall(release) { error("Expected the local installer") }
            assertEquals(1, downloads)
            assertEquals(1, installs)
            assertFalse(manager.dialogVisible)
            build++
            check()
            manager.openDialog(automatic = true)
            assertTrue(manager.dialogVisible && manager.automaticPrompt)
            assertEquals(2, saved.distinct().size)
        } finally {
            manager.dismissDialog()
            build = installedBuild
            try {
                manager.checkForUpdates(apiEndpointOverride = "$base/release")
            } finally {
                server.stop(0)
                manager.lastPromptedRelease = oldRelease
                manager.persistPromptedRelease = oldPersist
                manager.apkInstaller = oldInstaller
                manager.cacheDirProvider = oldCache
                cache.deleteRecursively()
            }
        }
    }
}
