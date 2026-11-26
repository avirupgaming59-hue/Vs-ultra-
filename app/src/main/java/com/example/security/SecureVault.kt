package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "SecureVault"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "vsshell_master_vault_key_v1"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12
private const val ENC_PREFIX = "vsshell:enc:v1:"
private const val PIN_HASH_PREFIX = "pbkdf2:sha256:"
private const val PIN_ITERATIONS = 120_000
private const val PIN_KEY_BITS = 256
private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * Hardware-backed Android Keystore AES-256-GCM credential vault and salted PBKDF2 PIN verifier.
 * Protects stored server passwords, tokens, and app lock credentials at rest.
 */
object SecureVault {

    private val secureRandom = SecureRandom()
    private val base64DecodeTable = IntArray(128) { -1 }.apply {
        for (i in BASE64_ALPHABET.indices) {
            this[BASE64_ALPHABET[i].code] = i
        }
        // Also accept URL-safe variants '-' and '_' when decoding
        this['-'.code] = 62
        this['_'.code] = 63
    }

    internal fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i++].toInt() and 0xFF
            val hasB1 = i < bytes.size
            val b1 = if (hasB1) bytes[i++].toInt() and 0xFF else 0
            val hasB2 = i < bytes.size
            val b2 = if (hasB2) bytes[i++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(BASE64_ALPHABET[(triple ushr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(triple ushr 12) and 0x3F])
            out.append(if (hasB1) BASE64_ALPHABET[(triple ushr 6) and 0x3F] else '=')
            out.append(if (hasB2) BASE64_ALPHABET[triple and 0x3F] else '=')
        }
        return out.toString()
    }

    internal fun decodeBase64(input: String): ByteArray {
        val cleaned = StringBuilder(input.length)
        for (ch in input) {
            if (!ch.isWhitespace()) {
                cleaned.append(ch)
            }
        }
        if (cleaned.isEmpty()) return ByteArray(0)

        var effectiveLen = cleaned.length
        while (effectiveLen > 0 && cleaned[effectiveLen - 1] == '=') {
            effectiveLen--
        }
        val rem = effectiveLen % 4
        require(rem != 1) { "Invalid Base64 length" }

        val outSize = (effectiveLen / 4) * 3 + when (rem) {
            2 -> 1
            3 -> 2
            else -> 0
        }
        val out = ByteArray(outSize)
        var outIdx = 0
        var idx = 0

        fun decodeChar(c: Char): Int {
            val code = c.code
            if (code >= 128) throw IllegalArgumentException("Invalid Base64 character")
            val value = base64DecodeTable[code]
            if (value < 0) throw IllegalArgumentException("Invalid Base64 character")
            return value
        }

        while (idx + 4 <= effectiveLen) {
            val v0 = decodeChar(cleaned[idx++])
            val v1 = decodeChar(cleaned[idx++])
            val v2 = decodeChar(cleaned[idx++])
            val v3 = decodeChar(cleaned[idx++])
            val chunk = (v0 shl 18) or (v1 shl 12) or (v2 shl 6) or v3
            out[outIdx++] = (chunk ushr 16).toByte()
            out[outIdx++] = (chunk ushr 8).toByte()
            out[outIdx++] = chunk.toByte()
        }

        when (effectiveLen - idx) {
            2 -> {
                val v0 = decodeChar(cleaned[idx])
                val v1 = decodeChar(cleaned[idx + 1])
                out[outIdx++] = ((v0 shl 2) or (v1 ushr 4)).toByte()
            }
            3 -> {
                val v0 = decodeChar(cleaned[idx])
                val v1 = decodeChar(cleaned[idx + 1])
                val v2 = decodeChar(cleaned[idx + 2])
                out[outIdx++] = ((v0 shl 2) or (v1 ushr 4)).toByte()
                out[outIdx++] = (((v1 and 0x0F) shl 4) or (v2 ushr 2)).toByte()
            }
        }
        return out
    }

    fun isEncryptedPayload(value: String): Boolean = value.startsWith(ENC_PREFIX)

    fun encryptSecret(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        if (isEncryptedPayload(plaintext)) return plaintext
        val key = getOrCreateKeystoreSecretKey()
            ?: throw IllegalStateException("Android Keystore is unavailable; refusing to store a credential in plaintext")
        return try {
            val encoded = encryptAesGcmBase64(plaintext.toByteArray(StandardCharsets.UTF_8), key)
            "$ENC_PREFIX$encoded"
        } catch (err: Exception) {
            Log.w(TAG, "Unable to encrypt credential with Keystore", err)
            throw IllegalStateException("Unable to encrypt credential with Android Keystore", err)
        }
    }

    fun decryptSecret(stored: String): String {
        if (stored.isEmpty()) return ""
        if (!isEncryptedPayload(stored)) return stored
        val key = getOrCreateKeystoreSecretKey()
            ?: throw IllegalStateException("Android Keystore is unavailable; cannot decrypt the saved credential")
        return try {
            val payload = stored.removePrefix(ENC_PREFIX)
            val bytes = decryptAesGcmBase64(payload, key)
            String(bytes, StandardCharsets.UTF_8)
        } catch (err: Exception) {
            Log.w(TAG, "Unable to decrypt stored credential", err)
            throw IllegalStateException("Unable to decrypt saved credential with Android Keystore", err)
        }
    }

    internal fun encryptAesGcmBase64(plaintextBytes: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv != null && iv.size == GCM_IV_BYTES) { "Invalid GCM IV length" }
        val ciphertext = cipher.doFinal(plaintextBytes)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return encodeBase64(combined)
    }

    internal fun decryptAesGcmBase64(base64Payload: String, key: SecretKey): ByteArray {
        val combined = decodeBase64(base64Payload)
        require(combined.size > GCM_IV_BYTES) { "Corrupted encrypted payload" }
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    internal fun generateRawAes256KeyForTests(): SecretKey {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return SecretKeySpec(bytes, "AES")
    }

    fun isKeystoreEncryptionAvailable(): Boolean = getOrCreateKeystoreSecretKey() != null

    private fun getOrCreateKeystoreSecretKey(): SecretKey? {
        return runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        }.onFailure { err ->
            Log.w(TAG, "AndroidKeyStore unavailable in current runtime environment", err)
        }.getOrNull()
    }

    fun generateRandomHexToken(byteCount: Int = 32): String {
        val bytes = ByteArray(byteCount.coerceIn(16, 64))
        secureRandom.nextBytes(bytes)
        val hex = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            hex.append(String.format("%02x", b))
        }
        return hex.toString()
    }

    fun hashPin(pin: String): String {
        require(pin.length in 4..12) { "PIN must be 4 to 12 characters" }
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val derived = derivePbkdf2(pin.toCharArray(), salt, PIN_ITERATIONS, PIN_KEY_BITS)
        val b64Salt = encodeBase64(salt)
        val b64Hash = encodeBase64(derived)
        return "$PIN_HASH_PREFIX$PIN_ITERATIONS:$b64Salt:$b64Hash"
    }

    fun verifyPin(pin: String, storedHash: String): Boolean {
        if (pin.isEmpty() || !storedHash.startsWith(PIN_HASH_PREFIX)) return false
        val parts = storedHash.removePrefix(PIN_HASH_PREFIX).split(':')
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull()?.coerceIn(10_000, 500_000) ?: return false
        val salt = runCatching { decodeBase64(parts[1]) }.getOrNull() ?: return false
        val expected = runCatching { decodeBase64(parts[2]) }.getOrNull() ?: return false
        val candidate = derivePbkdf2(pin.toCharArray(), salt, iterations, expected.size * 8)
        return MessageDigest.isEqual(expected, candidate)
    }

    private fun derivePbkdf2(chars: CharArray, salt: ByteArray, iterations: Int, keyBits: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(chars, salt, iterations, keyBits)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun sanitizeUrlForLogs(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return ""
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host.orEmpty()
            val portSuffix = if (uri.port > 0) ":${uri.port}" else ""
            val safeAuthority = when {
                host.isNotBlank() -> "$host$portSuffix"
                !uri.rawAuthority.isNullOrBlank() -> uri.rawAuthority.substringAfter('@')
                else -> ""
            }
            val path = uri.rawPath.orEmpty()
            val prefix = if (scheme.isNotBlank()) "$scheme://" else ""
            if (uri.rawQuery.isNullOrBlank()) "$prefix$safeAuthority$path" else "$prefix$safeAuthority$path?[redacted]"
        }.getOrElse {
            trimmed
                .substringBefore('?')
                .substringBefore('#')
                .replace(Regex("(://)[^/@\\s]+@"), "$1[REDACTED]@")
        }
    }

    fun redactSensitiveText(text: String): String {
        if (text.isBlank()) return text
        return text
            .replace(Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]+"), "$1[REDACTED]")
            .replace(Regex("(?i)(basic\\s+)[A-Za-z0-9+/=._\\-]+"), "$1[REDACTED]")
            .replace(Regex("github_pat_[A-Za-z0-9_]{10,}"), "[REDACTED]")
            .replace(Regex("gh[pousr]_[A-Za-z0-9_]{10,}"), "[REDACTED]")
            .replace(Regex("AIza[0-9A-Za-z_-]{20,}"), "[REDACTED]")
            .replace(Regex("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s]+@"), "$1[REDACTED]@")
            .replace(Regex("(?i)(token|password|passwd|secret|apikey|api_key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
    }
}
