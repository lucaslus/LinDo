package do_.linux.lindo

import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.render.DiscourseAstParser
import do_.linux.lindo.core.render.PostBlockNode
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Temporary review probes. Fixtures and transport contain no real account credentials. */
class LinDoAuditProbeTest {
    private fun preview(raw: String): List<PostBlockNode> {
        val method = Class.forName("do_.linux.lindo.ui.LinDoAppKt")
            .getDeclaredMethod("compileMarkdownAndBbcodeToPreviewHtml", String::class.java)
        method.isAccessible = true
        return DiscourseAstParser.parseCookedHtml(method.invoke(null, raw) as String)
    }

    @Test fun previewPreservesBoldAndLinkSemantics() {
        val nodes = preview("**bold** [reference](https://example.com/docs)")
        val runs = nodes.filterIsInstance<PostBlockNode.Paragraph>().flatMap { it.rich.runs }
        assertTrue(runs.any { it.bold && it.text == "bold" }, "Preview must render bold, rather than literal Markdown")
        assertTrue(runs.any { it.href == "https://example.com/docs" }, "Preview must preserve a working link")
    }

    @Test fun previewPreservesLiteralCodeTags() {
        val code = "<item>value</item>"
        val nodes = preview("```xml\n$code\n```")
        assertEquals(code, nodes.filterIsInstance<PostBlockNode.CodeBlock>().single().code)
    }

    @Test fun spoilerContentRequiresAnExplicitReveal() {
        val nodes = DiscourseAstParser.parseCookedHtml("<p>Visible <span class=\"spoiler\">secret fixture</span></p>")
        assertFalse(nodes.filterIsInstance<PostBlockNode.Paragraph>().any { "secret fixture" in it.text },
            "A spoiler must not be displayed immediately as ordinary paragraph text")
    }

    @Test fun vectorOnlyContentHasAnExplicitFallback() {
        val nodes = DiscourseAstParser.parseCookedHtml("<svg role=\"img\" viewBox=\"0 0 24 24\"><path d=\"M0 0L24 24\"/></svg>")
        assertTrue(nodes.isNotEmpty(), "A meaningful vector image must not silently disappear")
    }

    @Test fun draftJsonPreservesTabs() = runTest {
        val previous = UserApiKeyAuthManager.webViewHttpExecutor
        var captured: String? = null
        try {
            UserApiKeyAuthManager.webViewHttpExecutor = { _, _, form, _ ->
                captured = form?.get("data")
                200 to "{\"draft_sequence\":4}"
            }
            val text = "before\tafter"
            LinuxDoRepository.saveCloudDraft("new_topic", 3, text)
            assertEquals(text, Json.parseToJsonElement(captured!!).jsonObject["reply"]!!.jsonPrimitive.content)
        } finally { UserApiKeyAuthManager.webViewHttpExecutor = previous }
    }

    @Test fun authRejectsAnEncryptedPayloadWithTheWrongNonce() {
        val oldKey = UserApiKeyAuthManager.getApiKey()
        val oldPersist = UserApiKeyAuthManager.onPersistSession
        val oldState = UserApiKeyAuthManager.onAuthStateChanged
        try {
            UserApiKeyAuthManager.onPersistSession = null
            UserApiKeyAuthManager.onAuthStateChanged = null
            val (url, _) = UserApiKeyAuthManager.generateAuthorizationUrl()
            val params = url.substringAfter('?').split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val publicDer = params.getValue("public_key").lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicDer)))
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val payload = Base64.getEncoder().encodeToString(cipher.doFinal("{\"key\":\"audit-fixture-only\",\"nonce\":\"wrong-nonce\"}".toByteArray()))
            val accepted = UserApiKeyAuthManager.handleAuthRedirectUri("discourse://auth_redirect?payload=" + URLEncoder.encode(payload, "UTF-8"))
            assertFalse(accepted, "A callback for the wrong authorization nonce must be rejected")
        } finally {
            UserApiKeyAuthManager.setApiKey(oldKey)
            UserApiKeyAuthManager.onPersistSession = oldPersist
            UserApiKeyAuthManager.onAuthStateChanged = oldState
        }
    }
}
