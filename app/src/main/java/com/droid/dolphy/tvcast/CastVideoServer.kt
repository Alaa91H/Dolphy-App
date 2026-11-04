package com.droid.dolphy.tvcast

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Serves exactly one prepared media file to the television.
 *
 * The random path token prevents other devices on the same Wi-Fi from browsing
 * or downloading the selected file just because they can reach the phone.
 * A zero port lets the OS pick an unused port, so another app owning 8080 no
 * longer breaks casting.
 */
class CastVideoServer(
    private val videoFile: File,
    private val mimeType: String,
    private val displayName: String,
) : NanoHTTPD(0) {

    private val token = ByteArray(18)
        .also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    val playbackPath: String = "/video/$token"
    val pagePath: String = "/play/$token"

    fun playbackUrl(host: String): String =
        "http://${CastNetwork.formatHost(host)}:$listeningPort$playbackPath"

    fun pageUrl(host: String): String =
        "http://${CastNetwork.formatHost(host)}:$listeningPort$pagePath"

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == pagePath -> servePage(session)
                session.uri == playbackPath -> serveVideo(session)
                session.uri == "/" -> newFixedLengthResponse(
                    Response.Status.REDIRECT,
                    MIME_HTML,
                    "",
                ).apply { addHeader("Location", pagePath) }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        } catch (error: IOException) {
            Log.w("CastVideoServer", "Unable to serve media", error)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Unable to serve media",
            )
        }
    }

    private fun servePage(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
        }
        val isImage = mimeType.startsWith("image/")
        val body = """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8"/>
            <meta name="viewport" content="width=device-width,initial-scale=1"/>
            <title>Dolphy Cast</title>
            <style>
              html,body{margin:0;height:100%;background:#000;color:#fff;font-family:sans-serif}
              body{display:flex;align-items:center;justify-content:center}
              video,img{max-width:100%;max-height:100vh}
            </style></head><body>
            ${if (isImage) "<img src=\"$playbackPath\" alt=\"Dolphy Cast\"/>"
            else "<video src=\"$playbackPath\" controls autoplay playsinline></video>"}
            </body></html>
        """.trimIndent()
        val response = if (session.method == Method.HEAD) {
            newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                ByteArrayInputStream(ByteArray(0)),
                body.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            )
        } else {
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)
        }
        addCommonHeaders(response)
        return response
    }

    private fun serveVideo(session: IHTTPSession): Response {
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
        }
        val length = videoFile.length()
        val rangeHeader = session.headers.entries
            .firstOrNull { it.key.equals("range", ignoreCase = true) }
            ?.value
        val selection = parseRangeHeader(rangeHeader, length)
        if (selection is RangeSelection.Unsatisfiable) {
            return newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                MIME_PLAINTEXT,
                "",
            ).apply {
                addHeader("Content-Range", "bytes */$length")
                addCommonHeaders(this)
            }
        }

        val start: Long
        val end: Long
        val status: Response.IStatus
        when (selection) {
            RangeSelection.Full -> {
                start = 0
                end = (length - 1).coerceAtLeast(0)
                status = Response.Status.OK
            }
            is RangeSelection.Partial -> {
                start = selection.start
                end = selection.end
                status = Response.Status.PARTIAL_CONTENT
            }
            RangeSelection.Unsatisfiable -> error("handled above")
        }
        val contentLength = if (length == 0L) 0L else (end - start + 1).coerceAtLeast(0)
        if (session.method == Method.HEAD) {
            val response = newFixedLengthResponse(
                status,
                mimeType,
                ByteArrayInputStream(ByteArray(0)),
                contentLength,
            )
            if (status == Response.Status.PARTIAL_CONTENT) {
                response.addHeader("Content-Range", "bytes $start-$end/$length")
            }
            addCommonHeaders(response)
            return response
        }

        val stream = FileInputStream(videoFile)
        if (start > 0) stream.channel.position(start)
        val response = newFixedLengthResponse(status, mimeType, stream, contentLength)
        if (status == Response.Status.PARTIAL_CONTENT) {
            response.addHeader("Content-Range", "bytes $start-$end/$length")
        }
        response.addHeader("Content-Disposition", "inline; filename=\"${safeHeaderFileName(displayName)}\"")
        addCommonHeaders(response)
        return response
    }

    private fun addCommonHeaders(response: Response) {
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-store")
    }

    private fun safeHeaderFileName(name: String): String =
        name.replace("\"", "").replace("\r", "").replace("\n", "").take(120)
}
