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

data class M3UChannel(
    val id: String,
    val name: String,
    val group: String,
    val logoUrl: String,
    val streamUrl: String
)

object M3UParserAndroid {

    fun parseM3UStream(inputStream: InputStream): Flow<M3UChannel> = flow {
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 16384)
        
        var currentId = ""
        var currentName = ""
        var currentGroup = "Allgemein"
        var currentLogo = ""
        var isInsideExtInf = false

        try {
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty()) {
                    if (trimmedLine.startsWith("#EXTINF:")) {
                        currentId = extractAttributeFast(trimmedLine, "tvg-id")
                        currentName = extractChannelNameFast(trimmedLine)
                        currentGroup = extractAttributeFast(trimmedLine, "group-title").ifEmpty { "Allgemein" }
                        currentLogo = extractAttributeFast(trimmedLine, "tvg-logo")
                        isInsideExtInf = true
                    } else if (!trimmedLine.startsWith("#") && isInsideExtInf) {
                        emit(
                            M3UChannel(
                                id = currentId,
                                name = currentName.ifEmpty { "Unbenannter Kanal" },
                                group = currentGroup,
                                logoUrl = currentLogo,
                                streamUrl = trimmedLine
                            )
                        )
                        currentId = ""
                        currentName = ""
                        currentGroup = "Allgemein"
                        currentLogo = ""
                        isInsideExtInf = false
                    }
                }
                line = reader.readLine()
            }
        } finally {
            reader.close()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun parseM3UToList(inputStream: InputStream): List<M3UChannel> = withContext(Dispatchers.IO) {
        val resultList = ArrayList<M3UChannel>(5000)
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8), 16384)

        var currentId = ""
        var currentName = ""
        var currentGroup = "Allgemein"
        var currentLogo = ""
        var isInsideExtInf = false

        try {
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmedLine = line.trim()
                if (trimmedLine.isNotEmpty()) {
                    if (trimmedLine.startsWith("#EXTINF:")) {
                        currentId = extractAttributeFast(trimmedLine, "tvg-id")
                        currentName = extractChannelNameFast(trimmedLine)
                        currentGroup = extractAttributeFast(trimmedLine, "group-title").ifEmpty { "Allgemein" }
                        currentLogo = extractAttributeFast(trimmedLine, "tvg-logo")
                        isInsideExtInf = true
                    } else if (!trimmedLine.startsWith("#") && isInsideExtInf) {
                        resultList.add(
                            M3UChannel(
                                id = currentId,
                                name = currentName.ifEmpty { "Unbenannter Kanal" },
                                group = currentGroup,
                                logoUrl = currentLogo,
                                streamUrl = trimmedLine
                            )
                        )
                        currentId = ""
                        currentName = ""
                        currentGroup = "Allgemein"
                        currentLogo = ""
                        isInsideExtInf = false
                    }
                }
                line = reader.readLine()
            }
        } finally {
            reader.close()
        }
        resultList
    }

    private fun extractAttributeFast(line: String, attributeKey: String): String {
        val searchPrefix = "$attributeKey=\""
        val startIndex = line.indexOf(searchPrefix, ignoreCase = true)
        if (startIndex == -1) return ""

        val valueStartIndex = startIndex + searchPrefix.length
        val endIndex = line.indexOf("\"", valueStartIndex)
        if (endIndex == -1) return ""

        return line.substring(valueStartIndex, endIndex).trim()
    }

    private fun extractChannelNameFast(line: String): String {
        val lastCommaIndex = line.lastIndexOf(',')
        if (lastCommaIndex == -1 || lastCommaIndex == line.length - 1) return ""
        return line.substring(lastCommaIndex + 1).trim()
    }
}
