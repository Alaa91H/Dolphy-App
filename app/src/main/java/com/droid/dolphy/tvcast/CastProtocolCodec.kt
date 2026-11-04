package com.droid.dolphy.tvcast

import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

internal data class CastV2Message(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payload: String,
)

/**
 * Minimal CASTV2 wire codec.
 *
 * CASTV2 frames are a four-byte big-endian length followed by the protobuf
 * CastMessage. Only the fields Dolphy needs are decoded; unknown fields are
 * skipped, which keeps the client compatible with receivers that add fields.
 */
internal object CastV2Codec {

    private const val FIELD_PROTOCOL_VERSION = 1
    private const val FIELD_SOURCE_ID = 2
    private const val FIELD_DESTINATION_ID = 3
    private const val FIELD_NAMESPACE = 4
    private const val FIELD_PAYLOAD_TYPE = 5
    private const val FIELD_PAYLOAD_UTF8 = 6

    fun encodeFrame(message: CastV2Message): ByteArray {
        val body = ByteArrayOutputStream()
        writeVarintField(body, FIELD_PROTOCOL_VERSION, 0)
        writeStringField(body, FIELD_SOURCE_ID, message.sourceId)
        writeStringField(body, FIELD_DESTINATION_ID, message.destinationId)
        writeStringField(body, FIELD_NAMESPACE, message.namespace)
        writeVarintField(body, FIELD_PAYLOAD_TYPE, 0)
        writeStringField(body, FIELD_PAYLOAD_UTF8, message.payload)
        val bytes = body.toByteArray()
        val frame = ByteArray(4 + bytes.size)
        frame[0] = (bytes.size ushr 24).toByte()
        frame[1] = (bytes.size ushr 16).toByte()
        frame[2] = (bytes.size ushr 8).toByte()
        frame[3] = bytes.size.toByte()
        bytes.copyInto(frame, destinationOffset = 4)
        return frame
    }

    fun decodeFrame(frame: ByteArray): CastV2Message? {
        if (frame.size < 4) return null
        val length = ((frame[0].toInt() and 0xff) shl 24) or
            ((frame[1].toInt() and 0xff) shl 16) or
            ((frame[2].toInt() and 0xff) shl 8) or
            (frame[3].toInt() and 0xff)
        if (length <= 0 || length != frame.size - 4) return null
        return decodeBody(frame, 4, frame.size)
    }

    fun decodeBody(bytes: ByteArray, start: Int, end: Int): CastV2Message? {
        val fields = DecodedFields()
        var index = start
        while (index < end) {
            val tag = readVarint(bytes, index, end) ?: return null
            val next = readField(bytes, tag, tag.second, end, fields) ?: return null
            index = next
        }
        if (index != end) return null
        return CastV2Message(fields.sourceId, fields.destinationId, fields.namespace, fields.payload)
    }

    private fun readField(
        bytes: ByteArray,
        tag: Pair<Int, Int>,
        index: Int,
        end: Int,
        fields: DecodedFields,
    ): Int? {
        return when (tag.first and 0x07) {
            0 -> readVarint(bytes, index, end)?.second
            1 -> (index + 8).takeIf { it <= end }
            2 -> readLengthDelimited(bytes, tag.first ushr 3, index, end, fields)
            5 -> (index + 4).takeIf { it <= end }
            else -> null
        }
    }

    private fun readLengthDelimited(
        bytes: ByteArray,
        field: Int,
        index: Int,
        end: Int,
        fields: DecodedFields,
    ): Int? {
        val length = readVarint(bytes, index, end) ?: return null
        if (length.first < 0) return null
        val next = length.second + length.first
        if (next > end) return null
        fields.set(field, bytes, length.second, next)
        return next
    }

    private class DecodedFields {
        var sourceId = ""
        var destinationId = ""
        var namespace = ""
        var payload = ""

        fun set(field: Int, bytes: ByteArray, start: Int, end: Int) {
            when (field) {
                FIELD_SOURCE_ID -> sourceId = bytes.copyOfRange(start, end).toString(StandardCharsets.UTF_8)
                FIELD_DESTINATION_ID -> destinationId = bytes.copyOfRange(start, end).toString(StandardCharsets.UTF_8)
                FIELD_NAMESPACE -> namespace = bytes.copyOfRange(start, end).toString(StandardCharsets.UTF_8)
                FIELD_PAYLOAD_UTF8 -> payload = bytes.copyOfRange(start, end).toString(StandardCharsets.UTF_8)
            }
        }
    }

    private fun writeStringField(out: ByteArrayOutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarint(out, (field shl 3) or 2)
        writeVarint(out, bytes.size)
        out.write(bytes)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Int) {
        writeVarint(out, field shl 3)
        writeVarint(out, value)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (remaining and 0x7f.inv() != 0) {
            out.write((remaining and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        out.write(remaining)
    }

    private fun readVarint(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var index = start
        while (index < end && shift < 32) {
            val byte = bytes[index].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            index++
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }
}

internal data class SsdpResponse(
    val headers: Map<String, String>,
    val address: String?,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    val location: String? get() = header("location")
    val serviceType: String? get() = header("st")
    val usn: String? get() = header("usn")
}

internal fun parseSsdpResponse(raw: String, address: String? = null): SsdpResponse? {
    val lines = raw.replace("\r\n", "\n").split('\n').filter { it.isNotBlank() }
    if (lines.isEmpty() || !lines.first().startsWith("HTTP/", ignoreCase = true)) return null
    val headers = linkedMapOf<String, String>()
    lines.drop(1).forEach { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val name = line.substring(0, separator).trim().lowercase(Locale.US)
        val value = line.substring(separator + 1).trim()
        if (name.isNotEmpty()) headers[name] = value
    }
    return if (headers["location"].isNullOrBlank()) null else SsdpResponse(headers, address)
}

internal data class UpnpDescription(
    val friendlyName: String?,
    val modelName: String?,
    val controlUrl: String?,
    val serviceType: String?,
)

internal fun parseUpnpDescription(xml: String, location: String): UpnpDescription? {
    return runCatching {
        val document = secureDocumentBuilderFactory().newDocumentBuilder()
            .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            .parse(InputSource(StringReader(xml)))
        val device = findDevice(document) ?: return@runCatching null
        val transport = findTransportService(document, location)
        UpnpDescription(
            friendlyName = childText(device, "friendlyName"),
            modelName = childText(device, "modelName"),
            controlUrl = transport?.controlUrl,
            serviceType = transport?.serviceType,
        )
    }.getOrNull()
}

private data class TransportService(
    val controlUrl: String,
    val serviceType: String,
)

private fun findTransportService(
    document: org.w3c.dom.Document,
    location: String,
): TransportService? {
    val services = document.getElementsByTagNameNS("*", "service")
    return (0 until services.length)
        .asSequence()
        .mapNotNull { services.item(it) as? Element }
        .mapNotNull { service ->
            val type = childText(service, "serviceType") ?: return@mapNotNull null
            if (!type.contains("AVTransport", ignoreCase = true)) return@mapNotNull null
            val control = childText(service, "controlURL") ?: return@mapNotNull null
            val resolved = URI(location).resolve(control).toString()
            if (!resolved.startsWith("http://", ignoreCase = true) &&
                !resolved.startsWith("https://", ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            TransportService(resolved, type)
        }
        .firstOrNull()
}

private fun findDevice(document: org.w3c.dom.Document): Element? {
    return firstElement(document.getElementsByTagNameNS("*", "device"), "device")
        ?: firstElement(document.getElementsByTagName("device"), "device")
}

internal fun parseDialApps(xml: String): List<String> {
    return runCatching {
        val document = secureDocumentBuilderFactory().newDocumentBuilder()
            .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            .parse(InputSource(StringReader(xml)))
        val apps = document.getElementsByTagNameNS("*", "app")
        buildList {
            for (index in 0 until apps.length) {
                val app = apps.item(index) as? Element ?: continue
                app.getAttribute("name").takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()
    }.getOrDefault(emptyList())
}

internal fun parseFriendlyName(xml: String): String? {
    return runCatching {
        val document = secureDocumentBuilderFactory().newDocumentBuilder()
            .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
            .parse(InputSource(StringReader(xml)))
        val device = findDevice(document) ?: return@runCatching null
        childText(device, "friendlyName")
    }.getOrNull()
}

internal fun parseDialApplicationUrl(location: String, header: String?): String {
    val raw = header?.takeIf { it.isNotBlank() } ?: "/apps/"
    val resolved = URI(location).resolve(raw).toString()
    return if (resolved.endsWith('/')) resolved else "$resolved/"
}

internal fun dialAppUrl(baseUrl: String, appName: String): String {
    val encoded = URLEncoder.encode(appName, StandardCharsets.UTF_8.name()).replace("+", "%20")
    return baseUrl.trimEnd('/') + "/" + encoded
}

internal fun resolveLocalUrl(base: String, reference: String): String? {
    val resolved = runCatching { URI(base).resolve(reference).toString() }.getOrNull() ?: return null
    return resolved.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }
}

internal sealed interface RangeSelection {
    data object Full : RangeSelection
    data object Unsatisfiable : RangeSelection
    data class Partial(val start: Long, val end: Long) : RangeSelection
}

internal fun parseRangeHeader(header: String?, fileLength: Long): RangeSelection {
    if (header.isNullOrBlank() || fileLength <= 0) return RangeSelection.Full
    if (!header.startsWith("bytes=", ignoreCase = true)) return RangeSelection.Full
    val value = header.substringAfter('=').substringBefore(',').trim()
    val separator = value.indexOf('-')
    if (separator < 0) return RangeSelection.Full
    val startText = value.substring(0, separator).trim()
    val endText = value.substring(separator + 1).trim()
    return when {
        startText.isEmpty() && endText.isEmpty() -> RangeSelection.Full
        startText.isEmpty() -> suffixRange(endText, fileLength)
        else -> boundedRange(startText, endText, fileLength)
    }
}

private fun suffixRange(endText: String, fileLength: Long): RangeSelection {
    val suffix = endText.toLongOrNull() ?: return RangeSelection.Full
    if (suffix <= 0) return RangeSelection.Unsatisfiable
    return RangeSelection.Partial((fileLength - suffix).coerceAtLeast(0), fileLength - 1)
}

private fun boundedRange(startText: String, endText: String, fileLength: Long): RangeSelection {
    val start = startText.toLongOrNull() ?: return RangeSelection.Full
    if (start >= fileLength || start < 0) return RangeSelection.Unsatisfiable
    val requestedEnd = endText.toLongOrNull() ?: (fileLength - 1)
    if (requestedEnd < start) return RangeSelection.Unsatisfiable
    return RangeSelection.Partial(start, requestedEnd.coerceAtMost(fileLength - 1))
}

internal fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

internal fun buildDidlMetadata(videoUrl: String, mimeType: String, title: String): String {
    // This is a DIDL document, not an XML text node. The caller escapes it once
    // when embedding it in CurrentURIMetaData so & and < are not double-escaped.
    return """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
         xmlns:dc="http://purl.org/dc/elements/1.1/"
         xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
          <item id="1" parentID="0" restricted="1">
            <dc:title>$title</dc:title>
            <upnp:class>object.item.videoItem</upnp:class>
            <res protocolInfo="http-get:*:$mimeType:*">$videoUrl</res>
          </item>
        </DIDL-Lite>
    """.trimIndent()
}

internal fun mimeTypeForName(name: String, fallback: String?): String {
    fallback?.takeIf { it.contains('/') }?.let { return it }
    return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts" -> "video/mp2t"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "video/mp4"
    }
}

internal fun extensionForName(name: String, mimeType: String): String {
    val fromName = name.substringAfterLast('.', "")
        .lowercase(Locale.US)
        .filter { it.isLetterOrDigit() }
        .take(8)
    if (fromName.isNotEmpty()) return fromName
    return when (mimeType.lowercase(Locale.US)) {
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/x-matroska" -> "mkv"
        "video/x-msvideo" -> "avi"
        "video/quicktime" -> "mov"
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "mp4"
    }
}

private fun secureDocumentBuilderFactory(): DocumentBuilderFactory {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isExpandEntityReferences = false
    runCatching {
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }
    runCatching {
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    }
    runCatching {
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    runCatching {
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }
    return factory
}

private fun firstElement(nodes: org.w3c.dom.NodeList, name: String): Element? {
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as? Element ?: continue
        if (element.localName == name || element.nodeName == name) return element
    }
    return null
}

private fun childText(parent: Element, name: String): String? {
    val children = parent.childNodes
    for (index in 0 until children.length) {
        val child = children.item(index)
        if (child.nodeType != Node.ELEMENT_NODE) continue
        val element = child as Element
        if (element.localName == name || element.nodeName == name) {
            return element.textContent?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
    return null
}
