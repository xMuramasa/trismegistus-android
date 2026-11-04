package dev.xmuramasa.trismegistus.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingInputTests {
    @Test
    fun readsTheQrPayload() {
        assertEquals(
            PairingOffer("https://relay.example.test", "K7Q2-9XMB-4TRD"),
            PairingInput.fromQr("""{"v":1,"url":"https://relay.example.test/","code":"K7Q2-9XMB-4TRD"}"""),
        )
    }

    @Test
    fun refusesOtherPayloads() {
        assertNull(PairingInput.fromQr("""{"v":2,"url":"https://relay.example.test","code":"K7Q2-9XMB-4TRD"}"""))
        assertNull(PairingInput.fromQr("""{"v":"1","url":"https://relay.example.test","code":"K7Q2-9XMB-4TRD"}"""))
        assertNull(PairingInput.fromQr("""{"v":1,"url":"http://relay.example.test","code":"K7Q2-9XMB-4TRD"}"""))
        assertNull(PairingInput.fromQr("""{"v":1,"url":"https://relay.example.test","code":"nope"}"""))
        assertNull(PairingInput.fromQr("https://relay.example.test"))
        assertNull(PairingInput.fromQr("[1]"))
    }

    @Test
    fun normalisesAPastedCode() {
        assertEquals("K7Q2-9XMB-4TRD", PairingInput.code(" K7Q2-9XMB-4TRD\n"))
        assertEquals("K7Q2-9XMB-4TRD", PairingInput.code("K7Q29XMB4TRD"))
        assertEquals("k7q2-9xmb-4trd", PairingInput.code("k7q2 9xmb 4trd".replace(" ", "-")))
        assertNull(PairingInput.code("K7Q2-9XMB"))
        assertNull(PairingInput.code("K7Q2-9XMB-4TR!"))
    }

    @Test
    fun relayUrlsAreHttpsOnly() {
        assertEquals("https://relay.example.test", PairingInput.url("relay.example.test/"))
        assertEquals("https://relay.example.test:8443", PairingInput.url(" https://relay.example.test:8443 "))
        assertNull(PairingInput.url("http://relay.example.test"))
        assertNull(PairingInput.url("https://relay.example.test/?x=1"))
        assertNull(PairingInput.url("ftp://relay.example.test"))
    }
}
