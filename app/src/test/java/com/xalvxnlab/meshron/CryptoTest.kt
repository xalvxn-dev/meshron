package com.xalvxnlab.meshron

import com.xalvxnlab.meshron.contacts.ContactParser
import com.xalvxnlab.meshron.contacts.ParsedContact
import com.xalvxnlab.meshron.crypto.Ed25519
import com.xalvxnlab.meshron.crypto.ZBase32
import com.xalvxnlab.meshron.identity.Identity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // RFC 8032 section 7.1, TEST 1
    private val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    private val pub = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val sig = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    )

    @Test fun ed25519MatchesRfc8032() {
        assertArrayEquals(pub, Ed25519.publicKeyFromSeed(seed))
        assertArrayEquals(sig, Ed25519.sign(seed, ByteArray(0)))
        assertTrue(Ed25519.verify(pub, ByteArray(0), sig))
    }

    @Test fun ed25519RejectsTampering() {
        val msg = "hello".toByteArray()
        val s = Ed25519.sign(seed, msg)
        assertTrue(Ed25519.verify(pub, msg, s))
        assertFalse(Ed25519.verify(pub, "hellp".toByteArray(), s))
        s[0] = (s[0].toInt() xor 1).toByte()
        assertFalse(Ed25519.verify(pub, msg, s))
    }

    @Test fun zBase32KnownVectorsAndRoundTrip() {
        // Hand-checked bit groupings against the z-base-32 alphabet.
        assertEquals("", ZBase32.encode(ByteArray(0)))
        assertEquals("yy", ZBase32.encode(byteArrayOf(0x00)))
        assertEquals("ny", ZBase32.encode(byteArrayOf(0x10)))
        assertEquals("9h", ZBase32.encode(byteArrayOf(0xFF.toByte())))
        val id = ZBase32.encode(pub)
        assertEquals(52, id.length)
        assertArrayEquals(pub, ZBase32.decode(id))
    }

    @Test fun contactParser() {
        val id = ZBase32.encode(pub)
        val expectedHex = pub.joinToString("") { "%02x".format(it) }
        assertEquals(ParsedContact.Ok(expectedHex, ""), ContactParser.parse("  $id "))
        assertEquals(
            ParsedContact.Ok(expectedHex, "Ada L"),
            ContactParser.parse("Add me on Meshron: meshron://id/$id?name=Ada+L"),
        )
        assertTrue(ContactParser.parse("hello") is ParsedContact.Invalid)
        assertTrue(ContactParser.parse(id.dropLast(1)) is ParsedContact.Invalid)
        // Last char carries 4 padding bits; a non-zero padding must be rejected.
        assertTrue(ContactParser.parse(id.dropLast(1) + "9") is ParsedContact.Invalid)
    }

    @Test fun usernameRules() {
        assertTrue(Identity.isValidName("  Ada  Lovelace "))
        assertEquals("Ada Lovelace", Identity.cleanName("  Ada  Lovelace "))
        assertFalse(Identity.isValidName("   "))
        assertFalse(Identity.isValidName("x".repeat(33)))
    }
}
