package pl.jclab.refio.cli.acp

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AcpPeerTest {

    @Test
    fun `an unknown method is answered with method-not-found instead of silence`() = runBlocking<Unit> {
        AcpTestClient().use { client ->
            client.start()
            client.request(7, "session/fork")
            val reply = client.next()
            assertEquals(AcpError.METHOD_NOT_FOUND, reply.obj("error")["code"]!!.jsonPrimitive.int)
            assertTrue(reply.isResponse(7))
        }
    }

    @Test
    fun `a malformed line does not end the connection`() = runBlocking<Unit> {
        AcpTestClient().use { client ->
            client.peer.requestHandler = { _, _ -> buildJsonObject { put("ok", true) } }
            client.start()
            client.sendRaw("{not json")
            assertEquals(AcpError.PARSE_ERROR, client.next().obj("error")["code"]!!.jsonPrimitive.int)
            client.request(1, "initialize")
            assertTrue(client.next().isResponse(1))
        }
    }

    @Test
    fun `an answer is delivered only to the request with the same id`() = runBlocking<Unit> {
        AcpTestClient().use { client ->
            client.start()
            val pending = async { client.peer.request("session/request_permission", buildJsonObject { put("q", 1) }) }
            val outgoing = client.next()
            val id = outgoing["id"]!!

            client.respond(JsonPrimitive(id.jsonPrimitive.int + 1000), buildJsonObject { put("wrong", true) })
            client.respond(id, buildJsonObject { put("right", true) })

            assertEquals(buildJsonObject { put("right", true) }, pending.await())
        }
    }

    @Test
    fun `stray println output goes to stderr once stdout is claimed for the protocol`() {
        val originalOut = System.out
        val originalErr = System.err
        val protocol = ByteArrayOutputStream()
        val log = ByteArrayOutputStream()
        val protocolStream = PrintStream(protocol, true)
        try {
            System.setOut(protocolStream)
            System.setErr(PrintStream(log, true))

            val claimed = AcpStdio.claimStdout()
            println("Config YAML parser fallback")

            assertSame(protocolStream, claimed)
            assertEquals("", protocol.toString())
            assertTrue(log.toString().contains("Config YAML parser fallback"))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }
}
