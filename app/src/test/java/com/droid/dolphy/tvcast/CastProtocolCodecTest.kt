package com.droid.dolphy.tvcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastProtocolCodecTest {

    @Test
    fun `castv2 frame round trips and uses protocol version zero`() {
        val message = CastV2Message(
            sourceId = "sender-0",
            destinationId = "receiver-0",
            namespace = "urn:x-cast:com.google.cast.tp.connection",
            payload = """{"type":"CONNECT"}""",
        )
        val frame = CastV2Codec.encodeFrame(message)

        assertEquals(8, frame[4].toInt())
        assertEquals(0, frame[5].toInt())
        assertEquals(message, CastV2Codec.decodeFrame(frame))
    }

    @Test
    fun `castv2 decoder rejects a truncated frame`() {
        val frame = CastV2Codec.encodeFrame(
            CastV2Message("sender-0", "receiver-0", "ns", "{}"),
        )

        assertNull(CastV2Codec.decodeFrame(frame.copyOf(frame.size - 1)))
        assertEquals(null, CastV2Codec.decodeFrame(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun `ssdp headers are case insensitive and require a location`() {
        val response = parseSsdpResponse(
            """
            HTTP/1.1 200 OK
            LOCATION: http://192.168.1.20:9197/dmr
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
            USN: uuid:tv::urn:schemas-upnp-org:device:MediaRenderer:1

            """.trimIndent(),
            "192.168.1.20",
        )

        assertNotNull(response)
        assertEquals("http://192.168.1.20:9197/dmr", response?.location)
        assertEquals("192.168.1.20", response?.address)
        assertEquals(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            response?.serviceType,
        )
        assertNull(parseSsdpResponse("HTTP/1.1 200 OK\r\nST: x\r\n"))
    }

    @Test
    fun `upnp description resolves the advertised control url`() {
        val description = parseUpnpDescription(
            """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>Living Room TV</friendlyName>
                <modelName>TV-1</modelName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/upnp/control/AVTransport1</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
            """.trimIndent(),
            "http://192.168.1.20:9197/dmr",
        )

        assertNotNull(description)
        assertEquals("Living Room TV", description?.friendlyName)
        assertEquals("TV-1", description?.modelName)
        assertEquals(
            "http://192.168.1.20:9197/upnp/control/AVTransport1",
            description?.controlUrl,
        )
        assertEquals(
            "urn:schemas-upnp-org:service:AVTransport:1",
            description?.serviceType,
        )
    }

    @Test
    fun `dial apps and application url are parsed without hardcoded ports`() {
        val apps = parseDialApps(
            """
            <service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="1.7">
              <app name="YouTube" />
              <app name="DefaultMediaReceiver" />
            </service>
            """.trimIndent(),
        )

        assertEquals(listOf("YouTube", "DefaultMediaReceiver"), apps)
        assertEquals(
            "http://192.168.1.20:8008/apps/",
            parseDialApplicationUrl(
                "http://192.168.1.20:8008/description.xml",
                "/apps/",
            ),
        )
        assertEquals(
            "http://192.168.1.20:8008/apps/",
            parseDialApplicationUrl("http://192.168.1.20:8008/description.xml", null),
        )
        assertEquals(
            "http://192.168.1.20:8008/apps/YouTube",
            dialAppUrl("http://192.168.1.20:8008/apps/", "YouTube"),
        )
    }

    @Test
    fun `range parser handles open and suffix ranges and rejects impossible starts`() {
        assertEquals(RangeSelection.Partial(0, 99), parseRangeHeader("bytes=0-99", 1_000))
        assertEquals(RangeSelection.Partial(900, 999), parseRangeHeader("bytes=900-", 1_000))
        assertEquals(RangeSelection.Partial(900, 999), parseRangeHeader("bytes=-100", 1_000))
        assertEquals(RangeSelection.Partial(0, 19), parseRangeHeader("bytes=0-999", 20))
        assertEquals(RangeSelection.Unsatisfiable, parseRangeHeader("bytes=1000-", 1_000))
        assertEquals(RangeSelection.Unsatisfiable, parseRangeHeader("bytes=20-10", 1_000))
        assertEquals(RangeSelection.Full, parseRangeHeader("items=0-10", 1_000))
        assertEquals(RangeSelection.Full, parseRangeHeader("bytes=-", 1_000))
    }

    @Test
    fun `didl metadata is a raw document and mime lookup respects the file name`() {
        val metadata = buildDidlMetadata(
            "http://192.168.1.20:8080/video/token?a=1&b=2",
            "video/mp4",
            "A & B",
        )

        assertTrue(metadata.contains("<dc:title>A & B</dc:title>"))
        assertTrue(metadata.contains("http://192.168.1.20:8080/video/token?a=1&b=2"))
        assertTrue(escapeXml(metadata).contains("&lt;DIDL-Lite"))
        assertEquals("video/x-matroska", mimeTypeForName("movie.mkv", null))
        assertEquals("video/mp4", mimeTypeForName("movie.unknown", "video/mp4"))
        assertEquals("mkv", extensionForName("movie.mkv", "video/mp4"))
    }
}
