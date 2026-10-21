package com.aether.stream.feature.gatekeeper

import com.aether.stream.core.platform.getPlatformHardwareSeed

object AetherCryptoEngine {

    private const val SECRET_PEPPER = "AETHER_QUANTUM_STREAM_V1_PRIVATE_SALT_99482"
    const val DEFAULT_MASTER_CODE = "AETHER-2026"
    const val DEFAULT_ADMIN_PIN = "1983"

    fun getFormattedDeviceId(): String {
        val rawSeed = getPlatformHardwareSeed()
        val hash = fnv1a64(rawSeed + SECRET_PEPPER)
        val fourDigitNumber = ((hash ushr 16) % 9000UL + 1000UL).toInt()
        return "AETH-$fourDigitNumber"
    }

    fun generateUnlockCodeForDevice(deviceId: String): String {
        val normalizedId = deviceId.trim().uppercase()
        val payload = "$normalizedId::$SECRET_PEPPER::UNLOCK_TOKEN"
        val hashHex = customMixHashHex(payload)
        val part1 = hashHex.substring(0, 4)
        val part2 = hashHex.substring(4, 8)
        return "$part1-$part2"
    }

    fun verifyAccessCode(
        inputCode: String,
        currentDeviceId: String,
        activeMasterCode: String
    ): Boolean {
        val cleanedInput = inputCode.trim().uppercase()
        if (cleanedInput.isEmpty()) return false

        if (cleanedInput == activeMasterCode.trim().uppercase()) {
            return true
        }

        val expectedDeviceCode = generateUnlockCodeForDevice(currentDeviceId)
        val cleanedInputNoDash = cleanedInput.replace("-", "")
        val expectedNoDash = expectedDeviceCode.replace("-", "")

        return cleanedInput == expectedDeviceCode || cleanedInputNoDash == expectedNoDash
    }

    private fun fnv1a64(input: String): ULong {
        var hash = 14695981039346656037UL
        val prime = 1099511628211UL
        for (char in input) {
            hash = hash xor char.code.toULong()
            hash *= prime
        }
        return hash
    }

    private fun customMixHashHex(input: String): String {
        var h1 = 0x6A09E667F3BCC908UL
        var h2 = 0xBB67AE8584CAA73BUL

        for ((index, ch) in input.withIndex()) {
            val v = ch.code.toULong() + (index.toULong() * 31UL)
            h1 = (h1 xor v) * 0xBF58476D1CE4E5B9UL
            h1 = (h1 shl 13) or (h1 ushr 51)
            h2 = (h2 + h1) * 0x94D049BB133111EBUL
            h2 = (h2 shl 17) or (h2 ushr 47)
        }

        val combined = h1 xor h2
        val chars = "0123456789ABCDEF"
        val sb = StringBuilder(8)
        for (i in 0 until 8) {
            val nibble = ((combined ushr (i * 8)) and 0x0FUL).toInt()
            sb.append(chars[nibble])
        }
        return sb.toString()
    }
}