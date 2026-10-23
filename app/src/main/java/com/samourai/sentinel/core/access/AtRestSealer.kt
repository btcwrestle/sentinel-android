package com.samourai.sentinel.core.access

/**
 * Optional outer seal around the PIN-wrapped data key.
 * A sealer that does not transform bytes reports [seals] false.
 */
interface AtRestSealer {
    val seals: Boolean
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

object NoAtRestSealer : AtRestSealer {
    override val seals: Boolean = false
    override fun seal(plain: ByteArray): ByteArray = plain.copyOf()
    override fun open(sealed: ByteArray): ByteArray = sealed.copyOf()
}

object AtRestSealers {
    /**
     * Android Keystore when this process can use it, otherwise no outer seal.
     * The PIN-derived wrap is still what the PIN opens.
     */
    fun deviceDefault(): AtRestSealer {
        return try {
            val sealer = AndroidKeystoreSealer()
            val token = byteArrayOf(7, 7, 7)
            val opened = sealer.open(sealer.seal(token))
            if (!opened.contentEquals(token)) NoAtRestSealer else sealer
        } catch (t: Throwable) {
            NoAtRestSealer
        }
    }
}

class SealUnavailableException(cause: Throwable? = null) :
    Exception("Stored key seal is unavailable", cause)
