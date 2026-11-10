package com.xalvxnlab.meshron.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Thin wrapper over Bouncy Castle's RFC 8032 Ed25519. The 32-byte seed is the same
 * input libsodium's crypto_sign_seed_keypair (and so hypercore-crypto) uses, so the
 * Phase 2 Hyperswarm worklet derives an identical key pair from it.
 */
object Ed25519 {
    const val SEED_SIZE = 32
    const val PUBLIC_KEY_SIZE = 32

    fun publicKeyFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == SEED_SIZE)
        return Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE) return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}
