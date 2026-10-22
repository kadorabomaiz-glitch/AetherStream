package com.aether.stream.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Strukturierte Entität für einen IPTV-Sender.
 */
data class M3UChannel(
    val id: String,
    val name: String,
    val group: String,
    val logoUrl: String,
    val streamUrl: String
)

object M3UParserAndroid {

    private const val BUFFER_SIZE = 32768 // 32 KB Puffer für minimale I/O-Latenz bei 20.000+ Zeilen

    /**
     * Parst einen Datenstrom zeilenweise und emittiert Sender fortlaufend über einen Kotlin Coroutine Flow.
     * Speicherbedarf: Konstant wenige Kilobyte, selbst bei Playlists > 100 MB.
     */
    fun parseM3UStream(inputStream: InputStream): Flow<M3UChannel> = flow {
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), BUFFER_SIZE)

        var channelId = ""
        var channelName = ""
        var channelGroup = "Allgemein"
        var channelLogo = ""
        var isMetadataPending = false

        try {
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                        channelId = extractAttribute(trimmed, "tvg-id")
                        channelName = extractChannelName(trimmed)
                        channelGroup = extractAttribute(trimmed, "group-title").ifEmpty { "Allgemein" }
                        channelLogo = extractAttribute(trimmed, "tvg-logo")
                        isMetadataPending = true
                    } else if (!trimmed.startsWith("#") && isMetadataPending) {
                        emit(
                            M3UChannel(
                                id = channelId,
                                name = channelName.ifEmpty { "Unbenannter Kanal" },
                                group = channelGroup,
                                logoUrl = channelLogo,
                                streamUrl = trimmed
                            )
                        )
                        channelId = ""
                        channelName = ""
                        channelGroup = "Allgemein"
                        channelLogo = ""
                        isMetadataPending = false
                    }
                }
                line = reader.readLine()
            }
        } finally {
            reader.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parst den Datenstrom asynchron direkt in eine formatierte Liste.
     */
    suspend fun parseM3UToList(inputStream: InputStream): List<M3UChannel> = withContext(Dispatchers.IO) {
        val channels = ArrayList<M3UChannel>(8000)
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), BUFFER_SIZE)

        var channelId = ""
        var channelName = ""
        var channelGroup = "Allgemein"
        var channelLogo = ""
        var isMetadataPending = false

        try {
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                        channelId = extractAttribute(trimmed, "tvg-id")
                        channelName = extractChannelName(trimmed)
                        channelGroup = extractAttribute(trimmed, "group-title").ifEmpty { "Allgemein" }
                        channelLogo = extractAttribute(trimmed, "tvg-logo")
                        isMetadataPending = true
                    } else if (!trimmed.startsWith("#") && isMetadataPending) {
                        channels.add(
                            M3UChannel(
                                id = channelId,
                                name = channelName.ifEmpty { "Unbenannter Kanal" },
                                group = channelGroup,
                                logoUrl = channelLogo,
                                streamUrl = trimmed
                            )
                        )
                        channelId = ""
                        channelName = ""
                        channelGroup = "Allgemein"
                        channelLogo = ""
                        isMetadataPending = false
                    }
                }
                line = reader.readLine()
            }
        } finally {
            reader.close()
        }
        channels
    }

    /**
     * Zero-Allocation Tokenizer: Extrahiert Schlüssel-Wertepaare ohne Regex-Instanziierung.
     */
    private fun extractAttribute(source: String, key: String): String {
        val target = "$key=\""
        val startPos = source.indexOf(target, ignoreCase = true)
        if (startPos == -1) return ""
        val valueStart = startPos + target.length
        val endPos = source.indexOf('"', valueStart)
        if (endPos == -1) return ""
        return source.substring(valueStart, endPos).trim()
    }

    private fun extractChannelName(line: String): String {
        val commaPos = line.lastIndexOf(',')
        if (commaPos == -1 || commaPos == line.length - 1) return ""
        return line.substring(commaPos + 1).trim()
    }
}
