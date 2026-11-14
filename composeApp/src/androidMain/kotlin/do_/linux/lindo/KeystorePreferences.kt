package do_.linux.lindo

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Migrate existing plaintext secrets on read; keep UI preferences in their original format. */
internal class KeystorePreferences(context: Context) : SharedPreferences by context.getSharedPreferences("lindo_auth_session", Context.MODE_PRIVATE) {
    companion object {
        private const val KEY_ALIAS = "lindo.session.v1"
    }
    private val backing = context.getSharedPreferences("lindo_auth_session", Context.MODE_PRIVATE)
    private val secrets = setOf("saved_accounts", "raw_cookies", "api_key", "pending_rsa_priv_b64", "pending_rsa_nonce", "composer_drafts")
    private val prefix = "keystore:v1:"
    private val keyLock = Any()
    private fun key(recreate: Boolean = false): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (recreate) runCatching { store.deleteEntry(KEY_ALIAS) }
        else (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun deleteKeyAliasQuietly() = synchronized(keyLock) {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        }
    }
    private fun isUnrecoverableKeyError(err: Throwable?): Boolean {
        var cur = err
        while (cur != null) {
            if (cur is KeyPermanentlyInvalidatedException || cur is UnrecoverableKeyException) return true
            cur = cur.cause?.takeIf { it !== cur }
        }
        return false
    }
    private fun encrypt(value: String, recreate: Boolean = false): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(recreate))
        return prefix + Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    override fun getString(key: String?, defValue: String?): String? {
        val stored = backing.getString(key, defValue) ?: return null
        if (key !in secrets) return stored
        if (!stored.startsWith(prefix)) {
            if (stored != defValue) edit().putString(key, stored).apply()
            return stored
        }
        return try {
            val bytes = Base64.decode(stored.removePrefix(prefix), Base64.NO_WRAP)
            if (bytes.size <= 12) {
                backing.edit().remove(key).apply()
                return defValue
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (err: GeneralSecurityException) {
            if (isUnrecoverableKeyError(err)) {
                deleteKeyAliasQuietly()
            }
            backing.edit().remove(key).apply()
            defValue
        } catch (err: ProviderException) {
            if (isUnrecoverableKeyError(err)) {
                deleteKeyAliasQuietly()
            }
            backing.edit().remove(key).apply()
            defValue
        } catch (_: IllegalArgumentException) {
            backing.edit().remove(key).apply()
            defValue
        } catch (_: Exception) {
            defValue
        }
    }
    override fun getAll(): MutableMap<String, *> = backing.all.mapValues { (k, v) -> if (k in secrets) "[protected]" else v }.toMutableMap()
    override fun edit(): SharedPreferences.Editor {
        val editor = backing.edit()
        return object : SharedPreferences.Editor by editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key in secrets && value != null) {
                    val stored = runCatching { encrypt(value) }.getOrElse { err ->
                        if (isUnrecoverableKeyError(err)) {
                            runCatching { encrypt(value, recreate = true) }.getOrNull()
                        } else {
                            null
                        }
                    }
                    if (stored != null) {
                        editor.putString(key, stored)
                    }
                } else {
                    editor.putString(key, value)
                }
                return this
            }
        }
    }
}
