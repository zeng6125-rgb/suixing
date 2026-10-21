package com.suixing.agent.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的加密存放（用户要求「key 的存放要求更加保密化」）。
 *
 * **为什么要加密**：原来 Key 是**明文**躺在 `settings_json` 里 —— root/ADB 设备上直接把
 * `files/datastore/Suixing_settings.preferences_pb` 拖出来就能读到；换机迁移（device-transfer）
 * 也会把明文一起搬过去。这里用 Android Keystore 里的 AES/GCM 密钥把 Key 加密后再落盘，
 * **密钥存在系统 Keystore 里，不随应用数据走** ⇒ 换机后那串密文解不开，等于没有 Key。
 *
 * **代价（要在 UI 上说清楚）**：换机后用户要重填一次 Key。这是「更保密」与「换机免重填」
 * 之间的取舍 —— Keystore 的密钥不参与备份，两者不能兼得。
 *
 * **内存里始终是明文**：只有落盘那一步是密文（[SettingsRepository] 的 encode/decode 里做），
 * 所以业务代码、UI、后台任务读到的都还是普通字符串，不受这里影响。
 *
 * **不做的事**：
 * - 不开用户认证（生物/人脸）：后台任务（定时任务、悬浮窗）也要读 Key，一开就读不到；
 * - 不引新依赖：`androidx.security:security-crypto` 的 EncryptedSharedPreferences 已被官方
 *   标记 deprecated，这里直接用平台 Keystore API（minSdk 26 够用）。
 */
internal object SecretBox {
    /** Keystore 里的密钥别名。换算法就换别名（旧密文自然作废，用户重填）。 */
    private const val ALIAS = "suixing_secrets_v1"
    /** 密文前缀：让人一眼看出「这串是密文」，也避免把密文当明文发出去。 */
    private const val PREFIX = "enc1:"
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** 这串值是不是密文。 */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /**
     * 明文 → 可落盘的字符串。空串原样返回；已经是密文的原样返回（幂等）。
     *
     * Keystore 不可用时（极少数 ROM）**退回明文**：宁可少一层保护，也不能因为加密失败
     * 把用户的 Key 弄丢 —— 那样用户得重新申请 Key，代价远大于「没加密」。
     */
    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        if (isEncrypted(plain)) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + out, Base64.NO_WRAP)
        }.getOrElse { plain }
    }

    /**
     * 落盘的字符串 → 明文。不是密文就原样返回（旧文件里的明文 ✓）。
     *
     * **解不开就当作「没填」**：换机（密钥没跟过来）、密钥被系统作废（改了锁屏方式）、
     * 数据被清 —— 这几种情况下这份 Key 已经不可用了。返回空串让用户重填，
     * 绝不 crash，也绝不把密文当明文发出去。
     */
    fun decrypt(stored: String): String {
        if (stored.isBlank() || !isEncrypted(stored)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, IV_BYTES)),
            )
            String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
        }.getOrElse { "" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // **不能开用户认证**：后台任务（定时任务、悬浮窗）也要读 Key。
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
