package com.dronepeak.app

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.nio.ByteBuffer

/** RFC 6238: only the current 30-second period is accepted, without clock tolerance. */
internal object Totp {
    fun decode(secret: String): ByteArray {
        require(secret.matches(Regex("[A-Z2-7]{32}")))
        var bits = 0
        var buffer = 0
        val output = ArrayList<Byte>()
        secret.forEach { c ->
            buffer = (buffer shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c)
            bits += 5
            if (bits >= 8) { bits -= 8; output.add((buffer shr bits).toByte()) }
        }
        return output.toByteArray()
    }

    fun code(secret: String, epochSeconds: Long): String {
        require(epochSeconds >= 0)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(decode(secret), "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(epochSeconds / 30).array())
        val offset = hash.last().toInt() and 15
        val value = ByteBuffer.wrap(hash, offset, 4).int and 0x7fffffff
        return (value % 1000000).toString().padStart(6, '0')
    }

    fun verify(secret: String, input: String, epochSeconds: Long): Boolean =
        input.matches(Regex("[0-9]{6}")) && runCatching {
            java.security.MessageDigest.isEqual(code(secret, epochSeconds).toByteArray(), input.toByteArray())
        }.getOrDefault(false)

    fun isActivated(savedVersion: Int, currentVersion: Int): Boolean =
        currentVersion > 0 && savedVersion == currentVersion
}
