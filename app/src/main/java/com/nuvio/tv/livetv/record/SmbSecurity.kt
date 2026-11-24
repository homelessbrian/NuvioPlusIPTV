package com.nuvio.tv.livetv.record

import com.hierynomus.security.AEADBlockCipher
import com.hierynomus.security.Cipher
import com.hierynomus.security.DerivationFunction
import com.hierynomus.security.Mac
import com.hierynomus.security.MessageDigest
import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.security.jce.JceSecurityProvider

/**
 * Uses Android's own (fast, hardware-backed) security code for signing and encrypting what's
 * sent to a network folder, and the bundled library only for what Android lacks (the MD4 step
 * Windows logins need, AES-CCM on older boxes). Signing every byte of a recording with the
 * bundled library alone kept the processor busy and filled memory while copying.
 */
internal class SmbSecurity : SecurityProvider {
    private val fast = JceSecurityProvider()
    private val full = BCSecurityProvider()

    private inline fun <T> pick(a: () -> T, b: () -> T): T =
        try { a() } catch (_: Exception) { b() }

    override fun getDigest(name: String): MessageDigest = pick({ fast.getDigest(name) }, { full.getDigest(name) })
    override fun getMac(name: String): Mac = pick({ fast.getMac(name) }, { full.getMac(name) })
    override fun getCipher(name: String): Cipher = pick({ fast.getCipher(name) }, { full.getCipher(name) })
    override fun getAEADBlockCipher(name: String): AEADBlockCipher =
        pick({ fast.getAEADBlockCipher(name) }, { full.getAEADBlockCipher(name) })
    override fun getDerivationFunction(name: String): DerivationFunction =
        pick({ fast.getDerivationFunction(name) }, { full.getDerivationFunction(name) })
}
