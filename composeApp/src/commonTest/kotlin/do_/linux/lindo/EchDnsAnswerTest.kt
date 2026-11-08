package do_.linux.lindo

import do_.linux.lindo.core.network.EchDnsAnswer
import do_.linux.lindo.core.network.extractEchConfig
import do_.linux.lindo.core.network.parseEchDnsAnswers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EchDnsAnswerTest {
    // Fixture only. The app always gets the ECH configuration from DNS.
    private val ech = "AEX+DQBBVgAgACAeeACn4Pv8MXGtNDZHIYaygv7LFVWo5brRBKikvtdBYgAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="

    @Test
    fun httpsAnswerRequiresAUsableEchRecordForTheRequestedName() {
        val payload = """
            {"Status":0,"Answer":[
                {"name":"linux.do.","type":65,"TTL":10,"data":"1 . alpn=h2 ipv4hint=31.13.85.53"},
                {"name":"other.example.","type":65,"TTL":90,"data":"1 . ech=$ech"},
                {"name":"linux.do.","type":65,"TTL":42,"data":"1 . ech=\"$ech\""}
            ]}
        """.trimIndent()
        assertEquals(listOf(EchDnsAnswer("1 . ech=\"$ech\"", 42)), parseEchDnsAnswers(payload, "linux.do", 65))
        assertTrue(parseEchDnsAnswers(payload.replace("\"Status\":0", "\"Status\":2"), "linux.do", 65).isEmpty())
        assertNull(extractEchConfig("1 . ech=${ech.dropLast(8)}"))
    }

    @Test
    fun addressAnswersIgnorePoisonedAndNonPublicAddresses() {
        val addresses = listOf("31.13.85.53", "192.168.0.1", "10.1.0.1", "198.18.0.1", "104.20.16.234")
        val records = addresses.joinToString(",") { ip -> """{"name":"linux.do.","type":1,"TTL":60,"data":"$ip"}""" }
        val payload = """{"Status":0,"Answer":[$records]}"""
        assertEquals(listOf(EchDnsAnswer("104.20.16.234", 60)), parseEchDnsAnswers(payload, "linux.do", 1))
    }
}
