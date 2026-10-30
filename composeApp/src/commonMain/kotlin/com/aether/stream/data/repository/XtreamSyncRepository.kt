package com.aether.stream.data.repository

import com.aether.stream.data.local.AetherDao
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.CategoryEntity
import com.aether.stream.data.local.EpgProgramEntity
import com.aether.stream.data.local.PlaylistEntity
import com.aether.stream.data.local.SeriesEpisodeEntity
import com.aether.stream.data.local.StreamItemEntity
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

@Serializable
data class XtreamCategoryDto(
    @SerialName("category_id") val categoryId: JsonElement? = null,
    @SerialName("category_name") val categoryName: String? = null
)

@Serializable
data class XtreamLiveStreamDto(
    @SerialName("num") val num: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("stream_id") val streamId: JsonElement? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null
)

@Serializable
data class XtreamVodStreamDto(
    @SerialName("num") val num: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("stream_id") val streamId: JsonElement? = null,
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("rating") val rating: JsonElement? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null,
    @SerialName("container_extension") val containerExtension: JsonElement? = null,
    @SerialName("direct_source") val directSource: JsonElement? = null
)

@Serializable
data class XtreamSeriesDto(
    @SerialName("num") val num: JsonElement? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("series_id") val seriesId: JsonElement? = null,
    @SerialName("cover") val cover: String? = null,
    @SerialName("plot") val plot: String? = null,
    @SerialName("rating") val rating: JsonElement? = null,
    @SerialName("category_id") val categoryId: JsonElement? = null
)

@Serializable
data class XtreamEpisodeDto(
    @SerialName("id") val id: JsonElement? = null,
    @SerialName("stream_id") val streamId: JsonElement? = null,
    @SerialName("episode_num") val episodeNum: JsonElement? = null,
    @SerialName("season") val season: JsonElement? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("container_extension") val containerExtension: JsonElement? = null,
    @SerialName("direct_source") val directSource: JsonElement? = null,
    @SerialName("info") val info: JsonElement? = null
)

@Serializable
data class XtreamEpgListingDto(
    @SerialName("id") val id: JsonElement? = null,
    @SerialName("epg_id") val epgId: JsonElement? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("start_timestamp") val startTimestamp: JsonElement? = null,
    @SerialName("stop_timestamp") val stopTimestamp: JsonElement? = null
)

private fun JsonElement?.asCleanString(default: String = ""): String =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: default

private fun JsonElement?.asCleanInt(default: Int = 0): Int =
    (this as? JsonPrimitive)?.intOrNull
        ?: (this as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
        ?: default

private fun JsonElement?.asCleanLong(default: Long = 0L): Long =
    (this as? JsonPrimitive)?.longOrNull
        ?: (this as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()
        ?: default

class XtreamSyncRepository(
    private val dao: AetherDao,
    private val httpClient: HttpClient
) {

    companion object {
        private const val ORPHAN_CATEGORY_ID = "aether_orphan_catch"
        private const val ORPHAN_CATEGORY_NAME = "Uncategorized / Unkategorisiert"
        private const val IPTV_USER_AGENT = "TiviMate/5.1.0 (Linux; Android 11) ExoPlayerLib/2.19.1"
        private const val MAX_RECURSION_DEPTH = 6

        private val jsonParser = Json {
            isLenient = true
            ignoreUnknownKeys = true
            coerceInputValues = true
            allowSpecialFloatingPointValues = true
            allowStructuredMapKeys = true
        }
    }

    private fun normalizeServerUrl(rawUrl: String): String =
        rawUrl.trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "http://$it" else it
        }.trimEnd('/')

    /**
     * L10 Sanitizer: Bereinigt Steuerzeichen, kaputte UTF-8-Surrogate und ungeschlossene Quotes in Stream-Namen.
     */
    private fun sanitizeStreamText(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            if (code >= 32 && code != 0xFFFD && !ch.isSurrogate()) {
                sb.append(ch)
            } else if (ch == '\t' || ch == '\n') {
                sb.append(' ')
            }
        }
        return sb.toString().trim().ifEmpty { fallback }
    }

    /**
     * L10 Raw Payload Sanitizer: Schneidet führenden PHP/HTML-Müll vor '['/'{' ab und filtert ASCII-Control-Chars.
     */
    private fun sanitizeRawJsonPayload(raw: String): String {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return ""
        val firstBracket = trimmed.indexOfFirst { it == '[' || it == '{' }
        val lastBracket = trimmed.indexOfLast { it == ']' || it == '}' }
        if (firstBracket < 0 || lastBracket <= firstBracket) return ""

        val sliced = trimmed.substring(firstBracket, lastBracket + 1)
        val sb = StringBuilder(sliced.length)
        for (ch in sliced) {
            val c = ch.code
            if (c >= 32 || ch == '\n' || ch == '\r' || ch == '\t') {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * L10 Fault-Isolated JSON Array/Map Decoder mit Bracket-Depth Object Recovery:
     * Selbst wenn ein 50.000-Zeilen-Payload mitten im Stream ein unescaped Quote enthält,
     * rettet der Bracket-Scanner alle intakten JSON-Objekte einzeln (0 Chunk-Drops).
     */
    private inline fun <reified T> decodeListResiliently(rawPayload: String): List<T> {
        val cleanPayload = sanitizeRawJsonPayload(rawPayload)
        if (cleanPayload.isEmpty()) return emptyList()

        val resultList = ArrayList<T>()
        val fastParsed = runCatching { jsonParser.parseToJsonElement(cleanPayload) }.getOrNull()

        if (fastParsed != null) {
            when (fastParsed) {
                is JsonArray -> {
                    for (item in fastParsed) {
                        if (item is JsonObject) {
                            runCatching { resultList.add(jsonParser.decodeFromJsonElement<T>(item)) }
                        }
                    }
                }
                is JsonObject -> {
                    for ((_, item) in fastParsed) {
                        if (item is JsonObject) {
                            runCatching { resultList.add(jsonParser.decodeFromJsonElement<T>(item)) }
                        }
                    }
                }
                else -> Unit
            }
            return resultList
        }

        // Fallback Bracket-Depth Object Recovery für korrupte JSON-Streams
        var depth = 0
        var inString = false
        var isEscaped = false
        var objStartIdx = -1

        for (i in cleanPayload.indices) {
            val ch = cleanPayload[i]
            if (isEscaped) {
                isEscaped = false
                continue
            }
            if (ch == '\\' && inString) {
                isEscaped = true
                continue
            }
            if (ch == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (ch == '{') {
                    if (depth == 0) objStartIdx = i
                    depth += 1
                } else if (ch == '}') {
                    depth -= 1
                    if (depth == 0 && objStartIdx >= 0) {
                        val singleObjSlice = cleanPayload.substring(objStartIdx, i + 1)
                        runCatching {
                            resultList.add(jsonParser.decodeFromString<T>(singleObjSlice))
                        }
                        objStartIdx = -1
                    } else if (depth < 0) {
                        depth = 0
                        objStartIdx = -1
                    }
                }
            }
        }
        return resultList
    }

    private fun buildCompositeStreamKey(
        playlistId: Long,
        section: AetherSectionType,
        providerStreamId: Int,
        uniqueCategoryKey: String,
        cleanName: String
    ): String {
        val nameHash = cleanName.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
        return "${playlistId}_${section.name}_${providerStreamId}_${uniqueCategoryKey}_$nameHash"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBase64OrPlain(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val decoded = Base64.Default.decode(trimmed).decodeToString().trim()
            if (decoded.isNotEmpty() && decoded.none { it.code < 9 || (it.code in 14..31) }) decoded else trimmed
        }.getOrDefault(trimmed)
    }

    suspend fun triggerDailySilentSyncIfNeeded(): Int = withContext(Dispatchers.IO) {
        runCatching {
            val activePlaylist = dao.getActivePlaylistOnce() ?: return@withContext 0
            val existingCatKeys = dao.getExistingCategoryKeys(activePlaylist.id)
            if (existingCatKeys.isNotEmpty()) return@withContext 0
            synchronizePlaylistWithSmartLock(activePlaylist)
        }.getOrDefault(0)
    }

    suspend fun synchronizePlaylistWithSmartLock(
        playlist: PlaylistEntity
    ): Int = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"

        val existingKeys = runCatching { dao.getExistingCategoryKeys(playlist.id).toSet() }.getOrDefault(emptySet())
        val isUpgradeSync = playlist.isInitialSyncCompleted && existingKeys.isNotEmpty()

        val knownCategoryKeys = mutableSetOf<String>()
        var newlyDiscoveredCount = 0

        fun buildCategoryEntity(
            catId: String,
            catName: String,
            section: AetherSectionType,
            sortIndex: Int,
            targetList: MutableList<CategoryEntity>
        ): String {
            val key = "${playlist.id}_${section.name}_$catId"
            if (knownCategoryKeys.add(key)) {
                val isBrandNewGroup = isUpgradeSync && !existingKeys.contains(key)
                if (isBrandNewGroup) newlyDiscoveredCount++
                targetList.add(
                    CategoryEntity(
                        uniqueCategoryKey = key,
                        playlistId = playlist.id,
                        providerCategoryId = catId,
                        categoryName = sanitizeStreamText(catName, "Kategorie"),
                        sectionType = section,
                        isVisible = true,
                        isLocked = false,
                        isNewlyDiscovered = isBrandNewGroup,
                        customSortOrder = sortIndex
                    )
                )
            }
            return key
        }

        // 1. KATEGORIEN LADEN (Live, VOD & Serien)
        val liveCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_live_categories").forEachIndexed { idx, dto ->
            val cId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
            val cName = if (cId == ORPHAN_CATEGORY_ID) ORPHAN_CATEGORY_NAME else (dto.categoryName?.takeIf { it.isNotBlank() } ?: "Live TV")
            buildCategoryEntity(cId, cName, AetherSectionType.LIVE, idx, liveCategories)
        }
        if (liveCategories.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(liveCategories)

        val vodCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_vod_categories").forEachIndexed { idx, dto ->
            val cId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
            val cName = if (cId == ORPHAN_CATEGORY_ID) ORPHAN_CATEGORY_NAME else (dto.categoryName?.takeIf { it.isNotBlank() } ?: "Filme")
            buildCategoryEntity(cId, cName, AetherSectionType.VOD, idx, vodCategories)
        }
        if (vodCategories.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(vodCategories)

        val seriesCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_series_categories").forEachIndexed { idx, dto ->
            val cId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
            val cName = if (cId == ORPHAN_CATEGORY_ID) ORPHAN_CATEGORY_NAME else (dto.categoryName?.takeIf { it.isNotBlank() } ?: "Serien")
            buildCategoryEntity(cId, cName, AetherSectionType.SERIES, idx, seriesCategories)
        }
        if (seriesCategories.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(seriesCategories)

        // 2. LIVE-TV STREAMS LADEN (Inkl. Orphan Catch-Net "aether_orphan_catch" & 2.500er Batching)
        val liveStreams = ArrayList<StreamItemEntity>(4096)
        val orphanLiveCats = mutableListOf<CategoryEntity>()
        fetchLiveStreams(apiEndpoint, playlist).forEachIndexed { idx, dto ->
            val sId = dto.streamId.asCleanInt(0)
            if (sId != 0) {
                val rawCatId = dto.categoryId.asCleanString("")
                val candidateKey = "${playlist.id}_${AetherSectionType.LIVE.name}_$rawCatId"
                val finalCatKey = if (rawCatId.isNotEmpty() && knownCategoryKeys.contains(candidateKey)) {
                    candidateKey
                } else {
                    buildCategoryEntity(ORPHAN_CATEGORY_ID, ORPHAN_CATEGORY_NAME, AetherSectionType.LIVE, 9999, orphanLiveCats)
                }
                val cleanName = sanitizeStreamText(dto.name, "Kanal #$sId")
                val compositeKey = buildCompositeStreamKey(playlist.id, AetherSectionType.LIVE, sId, finalCatKey, cleanName)

                liveStreams.add(
                    StreamItemEntity(
                        uniqueStreamKey = compositeKey,
                        playlistId = playlist.id,
                        providerStreamId = sId,
                        uniqueCategoryKey = finalCatKey,
                        sectionType = AetherSectionType.LIVE,
                        name = cleanName,
                        logoUrl = dto.streamIcon?.trim(),
                        streamUrl = "$cleanServer/live/${playlist.username}/${playlist.password}/$sId.ts",
                        epgChannelId = dto.epgChannelId?.trim()?.takeIf { it.isNotBlank() } ?: compositeKey,
                        containerExtension = "ts",
                        isVisible = true,
                        customChannelNumber = dto.num.asCleanInt(idx + 1)
                    )
                )
            }
        }
        if (orphanLiveCats.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(orphanLiveCats)
        if (liveStreams.isNotEmpty()) {
            liveStreams.chunked(2500).forEach { chunk ->
                runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }
                yield()
            }
        }

        // 3. SERIEN LADEN (Inkl. Orphan Catch-Net "aether_orphan_catch" & 2.500er Batching)
        val seriesStreams = ArrayList<StreamItemEntity>(2048)
        val orphanSeriesCats = mutableListOf<CategoryEntity>()
        fetchSeries(apiEndpoint, playlist, null).forEachIndexed { idx, dto ->
            val sId = dto.seriesId.asCleanInt(0)
            if (sId != 0) {
                val rawCatId = dto.categoryId.asCleanString("")
                val candidateKey = "${playlist.id}_${AetherSectionType.SERIES.name}_$rawCatId"
                val finalCatKey = if (rawCatId.isNotEmpty() && knownCategoryKeys.contains(candidateKey)) {
                    candidateKey
                } else {
                    buildCategoryEntity(ORPHAN_CATEGORY_ID, ORPHAN_CATEGORY_NAME, AetherSectionType.SERIES, 9999, orphanSeriesCats)
                }
                val cleanName = sanitizeStreamText(dto.name, "Serie #$sId")
                val compositeKey = buildCompositeStreamKey(playlist.id, AetherSectionType.SERIES, sId, finalCatKey, cleanName)

                seriesStreams.add(
                    StreamItemEntity(
                        uniqueStreamKey = compositeKey,
                        playlistId = playlist.id,
                        providerStreamId = sId,
                        uniqueCategoryKey = finalCatKey,
                        sectionType = AetherSectionType.SERIES,
                        name = cleanName,
                        logoUrl = dto.cover?.trim(),
                        streamUrl = "$cleanServer/series/${playlist.username}/${playlist.password}/$sId",
                        rating = dto.rating.asCleanString(""),
                        plotDescription = dto.plot?.let { sanitizeStreamText(it, "") }?.takeIf { it.isNotBlank() },
                        isVisible = true,
                        customChannelNumber = dto.num.asCleanInt(idx + 1)
                    )
                )
            }
        }
        if (orphanSeriesCats.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(orphanSeriesCats)
        if (seriesStreams.isNotEmpty()) {
            seriesStreams.chunked(2500).forEach { chunk ->
                runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }
                yield()
            }
        }

        dao.markPlaylistSynced(playlist.id, Clock.System.now().toEpochMilliseconds())
        newlyDiscoveredCount
    }

    suspend fun fetchAndStoreShortEpgForStream(
        playlist: PlaylistEntity,
        stream: StreamItemEntity,
        storeDescriptions: Boolean = true
    ): List<EpgProgramEntity> = withContext(Dispatchers.IO) {
        if (stream.providerStreamId <= 0) return@withContext emptyList()
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"
        val channelKey = stream.epgChannelId?.takeIf { it.isNotBlank() } ?: stream.uniqueStreamKey

        val parsedPrograms = runCatching {
            val raw = httpClient.get(apiEndpoint) {
                header("User-Agent", IPTV_USER_AGENT)
                parameter("username", playlist.username)
                parameter("password", playlist.password)
                parameter("action", "get_short_epg")
                parameter("stream_id", stream.providerStreamId)
                parameter("limit", 16)
            }.body<String>()

            val cleanJson = sanitizeRawJsonPayload(raw)
            if (!cleanJson.startsWith("{")) return@runCatching emptyList()
            val rootObj = jsonParser.parseToJsonElement(cleanJson) as? JsonObject ?: return@runCatching emptyList()
            val listings = rootObj["epg_listings"] as? JsonArray ?: return@runCatching emptyList()

            listings.mapNotNull { elem ->
                val obj = elem as? JsonObject ?: return@mapNotNull null
                val dto = runCatching { jsonParser.decodeFromJsonElement<XtreamEpgListingDto>(obj) }.getOrNull() ?: return@mapNotNull null
                val startSec = dto.startTimestamp.asCleanLong(0L)
                val stopSec = dto.stopTimestamp.asCleanLong(0L)
                if (startSec <= 0L || stopSec <= startSec) return@mapNotNull null

                val decodedTitle = sanitizeStreamText(decodeBase64OrPlain(dto.title), "Live Programm")
                val decodedDesc = if (storeDescriptions) sanitizeStreamText(decodeBase64OrPlain(dto.description), "").takeIf { it.isNotBlank() } else null

                EpgProgramEntity(
                    epgChannelId = channelKey,
                    title = decodedTitle,
                    description = decodedDesc,
                    startEpochMillis = startSec * 1000L,
                    endEpochMillis = stopSec * 1000L
                )
            }
        }.getOrDefault(emptyList())

        if (parsedPrograms.isNotEmpty()) {
            dao.deleteEpgForChannel(channelKey)
            dao.insertEpgProgramsInBatches(parsedPrograms)
        }
        parsedPrograms
    }

    suspend fun pruneExpiredEpgAndGetChannelCount(pastDaysToKeep: Int = 7): Int = withContext(Dispatchers.IO) {
        runCatching {
            val safeDays = pastDaysToKeep.coerceIn(1, 30).toLong()
            val cutoffMs = Clock.System.now().toEpochMilliseconds() - (safeDays * 86_400_000L)
            dao.deleteExpiredEpgProgramsBefore(cutoffMs)
            dao.getDistinctEpgChannelsCount()
        }.getOrDefault(0)
    }

    suspend fun clearAllEpgCache() = withContext(Dispatchers.IO) {
        runCatching { dao.clearAllEpgPrograms() }
    }

    suspend fun fetchVodStreamsForCategory(
        playlist: PlaylistEntity,
        category: CategoryEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"
        val dtos = if (category.providerCategoryId == ORPHAN_CATEGORY_ID) {
            fetchVodStreams(apiEndpoint, playlist, null)
        } else {
            fetchVodStreams(apiEndpoint, playlist, category.providerCategoryId)
        }
        if (dtos.isEmpty()) return@withContext false

        val entities = dtos.mapIndexedNotNull { idx, dto ->
            val sId = dto.streamId.asCleanInt(0)
            if (sId == 0) null
            else {
                val ext = dto.containerExtension.asCleanString("mp4").trimStart('.').ifBlank { "mp4" }
                val directUrl = dto.directSource.asCleanString("")
                val finalVodUrl = if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) directUrl else "$cleanServer/movie/${playlist.username}/${playlist.password}/$sId.$ext"
                val cleanName = sanitizeStreamText(dto.name, "Film #$sId")
                val compositeKey = buildCompositeStreamKey(playlist.id, AetherSectionType.VOD, sId, category.uniqueCategoryKey, cleanName)
                StreamItemEntity(
                    uniqueStreamKey = compositeKey,
                    playlistId = playlist.id,
                    providerStreamId = sId,
                    uniqueCategoryKey = category.uniqueCategoryKey,
                    sectionType = AetherSectionType.VOD,
                    name = cleanName,
                    logoUrl = dto.streamIcon?.trim(),
                    streamUrl = finalVodUrl,
                    containerExtension = ext,
                    rating = dto.rating.asCleanString(""),
                    isVisible = true,
                    customChannelNumber = dto.num.asCleanInt(idx + 1)
                )
            }
        }

        if (entities.isNotEmpty()) {
            entities.chunked(2500).forEach { chunk ->
                runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }
                yield()
            }
            return@withContext true
        }
        return@withContext false
    }

    suspend fun fetchSeriesForCategory(
        playlist: PlaylistEntity,
        category: CategoryEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"
        val dtos = if (category.providerCategoryId == ORPHAN_CATEGORY_ID) {
            fetchSeries(apiEndpoint, playlist, null)
        } else {
            fetchSeries(apiEndpoint, playlist, category.providerCategoryId)
        }
        if (dtos.isEmpty()) return@withContext false

        val entities = dtos.mapIndexedNotNull { idx, dto ->
            val sId = dto.seriesId.asCleanInt(0)
            if (sId == 0) null
            else {
                val cleanName = sanitizeStreamText(dto.name, "Serie #$sId")
                val compositeKey = buildCompositeStreamKey(playlist.id, AetherSectionType.SERIES, sId, category.uniqueCategoryKey, cleanName)
                StreamItemEntity(
                    uniqueStreamKey = compositeKey,
                    playlistId = playlist.id,
                    providerStreamId = sId,
                    uniqueCategoryKey = category.uniqueCategoryKey,
                    sectionType = AetherSectionType.SERIES,
                    name = cleanName,
                    logoUrl = dto.cover?.trim(),
                    streamUrl = "$cleanServer/series/${playlist.username}/${playlist.password}/$sId",
                    rating = dto.rating.asCleanString(""),
                    plotDescription = dto.plot?.let { sanitizeStreamText(it, "") }?.takeIf { it.isNotBlank() },
                    isVisible = true,
                    customChannelNumber = dto.num.asCleanInt(idx + 1)
                )
            }
        }

        if (entities.isNotEmpty()) {
            entities.chunked(2500).forEach { chunk ->
                runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }
                yield()
            }
            return@withContext true
        }
        return@withContext false
    }

    suspend fun fetchAndStoreSeriesEpisodes(
        playlist: PlaylistEntity,
        seriesItem: StreamItemEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"
        val seriesId = seriesItem.providerStreamId

        val rootElement = runCatching {
            val rawText = httpClient.get(apiEndpoint) {
                header("User-Agent", IPTV_USER_AGENT)
                parameter("username", playlist.username)
                parameter("password", playlist.password)
                parameter("action", "get_series_info")
                parameter("series_id", seriesId)
            }.body<String>()
            val cleanJson = sanitizeRawJsonPayload(rawText)
            if (cleanJson.isEmpty()) return@runCatching null
            jsonParser.parseToJsonElement(cleanJson)
        }.getOrNull() ?: return@withContext false

        val rootObj = rootElement as? JsonObject ?: return@withContext false
        val episodesElement = rootObj["episodes"] ?: rootObj["seasons"] ?: return@withContext false
        val episodeEntities = mutableListOf<SeriesEpisodeEntity>()

        fun parseEpisodeDtoAndAdd(epDto: XtreamEpisodeDto, fallbackSeason: Int, fallbackEpIndex: Int) {
            val rawId = epDto.id.asCleanString("").ifEmpty { epDto.streamId.asCleanString("") }
            if (rawId.isEmpty()) return

            val infoObj = epDto.info as? JsonObject
            val seasonNum = epDto.season.asCleanInt(fallbackSeason).coerceAtLeast(1)
            val epNum = epDto.episodeNum.asCleanInt(fallbackEpIndex).coerceAtLeast(1)
            val extCandidate = epDto.containerExtension.asCleanString("").ifEmpty { infoObj?.get("container_extension").asCleanString("mp4") }
            val ext = extCandidate.trimStart('.').ifBlank { "mp4" }
            val plot = infoObj?.get("plot").asCleanString("").let { sanitizeStreamText(it, "") }.takeIf { it.isNotEmpty() }
            val durationSeconds = infoObj?.get("duration_secs").asCleanInt(0).toLong()
            val rating = infoObj?.get("rating").asCleanString("")

            val directUrl = epDto.directSource.asCleanString("")
            val videoUrl = if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) directUrl else "$cleanServer/series/${playlist.username}/${playlist.password}/${rawId.substringBeforeLast(".")}.$ext"

            episodeEntities.add(
                SeriesEpisodeEntity(
                    uniqueEpisodeKey = "${seriesItem.uniqueStreamKey}_s${seasonNum}_e${epNum}_id$rawId",
                    seriesStreamKey = seriesItem.uniqueStreamKey,
                    seasonNumber = seasonNum,
                    episodeNumber = epNum,
                    title = sanitizeStreamText(epDto.title, "Episode $epNum"),
                    containerExtension = ext,
                    plotDescription = plot,
                    durationMs = durationSeconds * 1000L,
                    streamUrl = videoUrl,
                    rating = rating
                )
            )
        }

        fun scanJsonRecursively(element: JsonElement, currentSeason: Int, fallbackEpIndex: Int, depth: Int) {
            if (depth > MAX_RECURSION_DEPTH) return
            when (element) {
                is JsonObject -> {
                    val hasEpisodeId = element.containsKey("id") || element.containsKey("stream_id")
                    val hasEpisodeFields = element.containsKey("episode_num") || element.containsKey("container_extension") || element.containsKey("title") || element.containsKey("info")
                    if (hasEpisodeId && hasEpisodeFields) {
                        runCatching { parseEpisodeDtoAndAdd(jsonParser.decodeFromJsonElement(element), currentSeason, fallbackEpIndex) }
                    } else {
                        var runningIdx = 1
                        element.forEach { (key, child) ->
                            val parsedKeyNum = key.trim().toIntOrNull()?.coerceAtLeast(1)
                            val childIsDirectEpisode = (child is JsonObject) && (child.containsKey("id") || child.containsKey("stream_id"))
                            if (childIsDirectEpisode) scanJsonRecursively(child, currentSeason, parsedKeyNum ?: runningIdx, depth + 1)
                            else scanJsonRecursively(child, parsedKeyNum ?: currentSeason, runningIdx, depth + 1)
                            runningIdx++
                        }
                    }
                }
                is JsonArray -> element.forEachIndexed { index, item ->
                    if (item is JsonArray) scanJsonRecursively(item, index + 1, 1, depth + 1)
                    else scanJsonRecursively(item, currentSeason, index + 1, depth + 1)
                }
                else -> Unit
            }
        }

        scanJsonRecursively(episodesElement, currentSeason = 1, fallbackEpIndex = 1, depth = 0)
        if (episodeEntities.isNotEmpty()) {
            dao.insertOrUpdateEpisodes(episodeEntities)
            return@withContext true
        }
        return@withContext false
    }

    private suspend fun fetchCategories(endpoint: String, playlist: PlaylistEntity, action: String): List<XtreamCategoryDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", action)
        }.body<String>()
        decodeListResiliently<XtreamCategoryDto>(raw)
    }.getOrDefault(emptyList())

    private suspend fun fetchLiveStreams(endpoint: String, playlist: PlaylistEntity): List<XtreamLiveStreamDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_live_streams")
        }.body<String>()
        decodeListResiliently<XtreamLiveStreamDto>(raw)
    }.getOrDefault(emptyList())

    private suspend fun fetchVodStreams(endpoint: String, playlist: PlaylistEntity, categoryId: String? = null): List<XtreamVodStreamDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_vod_streams")
            if (!categoryId.isNullOrBlank()) parameter("category_id", categoryId)
        }.body<String>()
        decodeListResiliently<XtreamVodStreamDto>(raw)
    }.getOrDefault(emptyList())

    private suspend fun fetchSeries(endpoint: String, playlist: PlaylistEntity, categoryId: String? = null): List<XtreamSeriesDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_series")
            if (!categoryId.isNullOrBlank()) parameter("category_id", categoryId)
        }.body<String>()
        decodeListResiliently<XtreamSeriesDto>(raw)
    }.getOrDefault(emptyList())
}
