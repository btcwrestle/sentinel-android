package com.samourai.wallet.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Argon2id parameters.
 *
 * PIN unlock runs on a cold start, so it uses the interactive profile.
 * Backup encrypt and decrypt run when the user asks, so they use a heavier
 * profile. Callers store the profile next to the salt so a later change
 * still opens older material.
 */
object Argon2Kdf {

    const val PIN_MEMORY_KIB = 19 * 1024
    const val PIN_ITERATIONS = 2
    const val PIN_PARALLELISM = 1

    const val BACKUP_MEMORY_KIB = 64 * 1024
    const val BACKUP_ITERATIONS = 3
    const val BACKUP_PARALLELISM = 1

    fun derive(
        password: CharArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        outputLen: Int
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKiB)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(outputLen)
        generator.generateBytes(password, out)
        return out
    }
}
