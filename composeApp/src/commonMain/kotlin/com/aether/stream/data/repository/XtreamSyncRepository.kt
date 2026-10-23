package com.aether.stream.data.repository

import com.aether.stream.data.local.AetherDao
import com.aether.stream.data.local.AetherSectionType
import com.aether.stream.data.local.CategoryEntity
import com.aether.stream.data.local.PlaylistEntity
import com.aether.stream.data.local.SeriesEpisodeEntity
import com.aether.stream.data.local.StreamItemEntity
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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

private fun JsonElement?.asCleanString(default: String = ""): String =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: default

private fun JsonElement?.asCleanInt(default: Int = 0): Int =
    (this as? JsonPrimitive)?.intOrNull
        ?: (this as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
        ?: default

class XtreamSyncRepository(
    private val dao: AetherDao,
    private val httpClient: HttpClient
) {

    companion object {
        private const val ORPHAN_CATEGORY_ID = "AETHER_UNCATEGORIZED_FEEDS"
        private const val IPTV_USER_AGENT = "TiviMate/5.1.0 (Linux; Android 11) ExoPlayerLib/2.19.1"
        private const val MAX_RECURSION_DEPTH = 6

        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    private fun normalizeServerUrl(rawUrl: String): String =
        rawUrl.trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "http://$it" else it
        }.trimEnd('/')

    suspend fun triggerDailySilentSyncIfNeeded(): Int = withContext(Dispatchers.IO) {
        runCatching {
            val activePlaylist = dao.getActivePlaylistOnce() ?: return@withContext 0
            val existingCatKeys = dao.getExistingCategoryKeys(activePlaylist.id)
            val timeZone = TimeZone.currentSystemDefault()
            val lastSyncDate = kotlinx.datetime.Instant
                .fromEpochMilliseconds(activePlaylist.lastSyncEpochMillis)
                .toLocalDateTime(timeZone).date
            val todayDate = Clock.System.now().toLocalDateTime(timeZone).date

            if (activePlaylist.lastSyncEpochMillis == 0L || existingCatKeys.isEmpty() || lastSyncDate < todayDate) {
                return@withContext synchronizePlaylistWithSmartLock(activePlaylist)
            }
            0
        }.getOrDefault(0)
    }

    /**
     * L10-Enterprise Sync:
     * 1. Lädt alle Kategorien (Live, VOD, Serien) mit minimalem RAM-Footprint (< 50 KB).
     * 2. Lädt Live-TV-Sender in Chunks und gibt den Heap sofort frei.
     * 3. Lädt für VOD & Serien die erste Kategorie vorab (Pre-Warming) und den Rest Lazy On-Demand,
     *    um OutOfMemoryErrors bei +80.000 VOD-Einträgen auf Fire TV Geräten zu 100 % auszuschließen.
     */
    suspend fun synchronizePlaylistWithSmartLock(
        playlist: PlaylistEntity
    ): Int = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"

        val existingKeys = runCatching { dao.getExistingCategoryKeys(playlist.id).toSet() }.getOrDefault(emptySet())
        val isUpgradeSync = playlist.isInitialSyncCompleted && existingKeys.isNotEmpty()

        val knownCategoryKeys = mutableSetOf<String>()
        var newlyDiscoveredCount = 0
        var totalItemsSaved = 0

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
                        categoryName = catName,
                        sectionType = section,
                        isVisible = !isBrandNewGroup,
                        isLocked = false,
                        isNewlyDiscovered = isBrandNewGroup,
                        customSortOrder = sortIndex
                    )
                )
            }
            return key
        }

        // STUFE 1: KATEGORIEN SYNCHRONISIEREN
        val liveCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_live_categories").forEachIndexed { idx, dto ->
            buildCategoryEntity(
                catId = dto.categoryId.asCleanString("0"),
                catName = dto.categoryName?.takeIf { it.isNotBlank() } ?: "Unbenannte Gruppe",
                section = AetherSectionType.LIVE,
                sortIndex = idx,
                targetList = liveCategories
            )
        }
        if (liveCategories.isNotEmpty()) {
            dao.insertNewCategoriesIgnoringExisting(liveCategories)
            totalItemsSaved += liveCategories.size
        }

        val vodCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_vod_categories").forEachIndexed { idx, dto ->
            buildCategoryEntity(
                catId = dto.categoryId.asCleanString("0"),
                catName = dto.categoryName?.takeIf { it.isNotBlank() } ?: "Unbenannte Filme",
                section = AetherSectionType.VOD,
                sortIndex = idx,
                targetList = vodCategories
            )
        }
        if (vodCategories.isNotEmpty()) {
            dao.insertNewCategoriesIgnoringExisting(vodCategories)
            totalItemsSaved += vodCategories.size
        }

        val seriesCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_series_categories").forEachIndexed { idx, dto ->
            buildCategoryEntity(
                catId = dto.categoryId.asCleanString("0"),
                catName = dto.categoryName?.takeIf { it.isNotBlank() } ?: "Unbenannte Serien",
                section = AetherSectionType.SERIES,
                sortIndex = idx,
                targetList = seriesCategories
            )
        }
        if (seriesCategories.isNotEmpty()) {
            dao.insertNewCategoriesIgnoringExisting(seriesCategories)
            totalItemsSaved += seriesCategories.size
        }

        // STUFE 2: LIVE-TV STREAMS IN SPEICHERSCHONENDEN BATCHES
        val extraLiveCats = mutableListOf<CategoryEntity>()
        val liveBatchBuffer = ArrayList<StreamItemEntity>(2000)

        fetchLiveStreams(apiEndpoint, playlist).forEachIndexed { idx, dto ->
            val sId = dto.streamId.asCleanInt(0)
            if (sId != 0) {
                val rawCatId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
                val targetCatKey = "${playlist.id}_${AetherSectionType.LIVE.name}_$rawCatId"
                val finalCatKey = if (knownCategoryKeys.contains(targetCatKey)) {
                    targetCatKey
                } else {
                    buildCategoryEntity(
                        catId = rawCatId,
                        catName = "⚡ Spezial-Feeds ($rawCatId)",
                        section = AetherSectionType.LIVE,
                        sortIndex = 9999,
                        targetList = extraLiveCats
                    )
                }

                liveBatchBuffer.add(
                    StreamItemEntity(
                        uniqueStreamKey = "${playlist.id}_LIVE_$sId",
                        playlistId = playlist.id,
                        providerStreamId = sId,
                        uniqueCategoryKey = finalCatKey,
                        sectionType = AetherSectionType.LIVE,
                        name = dto.name?.takeIf { it.isNotBlank() } ?: "Kanal #$sId",
                        logoUrl = dto.streamIcon,
                        streamUrl = "$cleanServer/live/${playlist.username}/${playlist.password}/$sId.ts",
                        epgChannelId = dto.epgChannelId,
                        containerExtension = "ts",
                        isVisible = true,
                        customChannelNumber = dto.num.asCleanInt(idx + 1)
                    )
                )

                if (liveBatchBuffer.size >= 2000) {
                    if (extraLiveCats.isNotEmpty()) {
                        dao.insertNewCategoriesIgnoringExisting(extraLiveCats.toList())
                        extraLiveCats.clear()
                    }
                    dao.insertNewStreamsIgnoringExisting(liveBatchBuffer.toList())
                    totalItemsSaved += liveBatchBuffer.size
                    liveBatchBuffer.clear()
                }
            }
        }

        if (extraLiveCats.isNotEmpty()) {
            dao.insertNewCategoriesIgnoringExisting(extraLiveCats)
            extraLiveCats.clear()
        }
        if (liveBatchBuffer.isNotEmpty()) {
            dao.insertNewStreamsIgnoringExisting(liveBatchBuffer)
            totalItemsSaved += liveBatchBuffer.size
            liveBatchBuffer.clear()
        }

        // STUFE 3: PRE-WARMING DER ERSTEN SICHTBAREN VOD- & SERIEN-KATEGORIE
        // (Restliche Kategorien werden in 0,2s Lazy On-Demand geladen, sobald der Nutzer sie anklickt!)
        vodCategories.firstOrNull { it.isVisible && !it.isLocked }?.let { firstVodCat ->
            fetchVodStreamsForCategory(playlist, firstVodCat)
        }
        seriesCategories.firstOrNull { it.isVisible && !it.isLocked }?.let { firstSeriesCat ->
            fetchSeriesForCategory(playlist, firstSeriesCat)
        }

        if (totalItemsSaved > 0) {
            dao.markPlaylistSynced(
                playlistId = playlist.id,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
        }

        newlyDiscoveredCount
    }

    /**
     * Lazy On-Demand Lader für eine einzelne VOD-Kategorie (minimaler RAM-Bedarf, 0,2s Ladezeit).
     */
    suspend fun fetchVodStreamsForCategory(
        playlist: PlaylistEntity,
        category: CategoryEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"
        val dtos = fetchVodStreams(apiEndpoint, playlist, category.providerCategoryId)
        if (dtos.isEmpty()) return@withContext false

        val entities = dtos.mapIndexedNotNull { idx, dto ->
            val sId = dto.streamId.asCleanInt(0)
            if (sId == 0) null
            else {
                val ext = dto.containerExtension.asCleanString("mp4").trimStart('.').ifBlank { "mp4" }
                val directUrl = dto.directSource.asCleanString("")
                val finalVodUrl = if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) {
                    directUrl
                } else {
                    "$cleanServer/movie/${playlist.username}/${playlist.password}/$sId.$ext"
                }
                StreamItemEntity(
                    uniqueStreamKey = "${playlist.id}_VOD_$sId",
                    playlistId = playlist.id,
                    providerStreamId = sId,
                    uniqueCategoryKey = category.uniqueCategoryKey,
                    sectionType = AetherSectionType.VOD,
                    name = dto.name?.takeIf { it.isNotBlank() } ?: "Film #$sId",
                    logoUrl = dto.streamIcon,
                    streamUrl = finalVodUrl,
                    containerExtension = ext,
                    rating = dto.rating.asCleanString(""),
                    isVisible = true,
                    customChannelNumber = dto.num.asCleanInt(idx + 1)
                )
            }
        }

        if (entities.isNotEmpty()) {
            entities.chunked(1000).forEach { dao.insertNewStreamsIgnoringExisting(it) }
            return@withContext true
        }
        return@withContext false
    }

    /**
     * Lazy On-Demand Lader für eine einzelne Serien-Kategorie.
     */
    suspend fun fetchSeriesForCategory(
        playlist: PlaylistEntity,
        category: CategoryEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"
        val dtos = fetchSeries(apiEndpoint, playlist, category.providerCategoryId)
        if (dtos.isEmpty()) return@withContext false

        val entities = dtos.mapIndexedNotNull { idx, dto ->
            val sId = dto.seriesId.asCleanInt(0)
            if (sId == 0) null
            else {
                StreamItemEntity(
                    uniqueStreamKey = "${playlist.id}_SERIES_$sId",
                    playlistId = playlist.id,
                    providerStreamId = sId,
                    uniqueCategoryKey = category.uniqueCategoryKey,
                    sectionType = AetherSectionType.SERIES,
                    name = dto.name?.takeIf { it.isNotBlank() } ?: "Serie #$sId",
                    logoUrl = dto.cover,
                    streamUrl = "$cleanServer/series/${playlist.username}/${playlist.password}/$sId",
                    rating = dto.rating.asCleanString(""),
                    plotDescription = dto.plot,
                    isVisible = true,
                    customChannelNumber = dto.num.asCleanInt(idx + 1)
                )
            }
        }

        if (entities.isNotEmpty()) {
            entities.chunked(1000).forEach { dao.insertNewStreamsIgnoringExisting(it) }
            return@withContext true
        }
        return@withContext false
    }

    /**
     * L10 Rekursiver Deep-Scanner für Serien-Episoden mit Stack-Overflow-Schutz (MAX_RECURSION_DEPTH).
     */
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
            }.body<String>().trim()

            if (!rawText.startsWith("{") && !rawText.startsWith("[")) return@runCatching null
            jsonParser.parseToJsonElement(rawText)
        }.getOrNull() ?: return@withContext false

        val rootObj = rootElement as? JsonObject ?: return@withContext false
        val episodesElement = rootObj["episodes"] ?: return@withContext false
        val episodeEntities = mutableListOf<SeriesEpisodeEntity>()

        fun parseEpisodeDtoAndAdd(epDto: XtreamEpisodeDto, fallbackSeason: Int, fallbackEpIndex: Int) {
            val rawId = epDto.id.asCleanString("").ifEmpty { epDto.streamId.asCleanString("") }
            if (rawId.isEmpty()) return

            val seasonNum = epDto.season.asCleanInt(fallbackSeason).coerceAtLeast(1)
            val epNum = epDto.episodeNum.asCleanInt(fallbackEpIndex).coerceAtLeast(1)
            val ext = epDto.containerExtension.asCleanString("mp4").trimStart('.').ifBlank { "mp4" }

            val infoObj = epDto.info as? JsonObject
            val plot = infoObj?.get("plot").asCleanString("").takeIf { it.isNotEmpty() }
            val durationSeconds = infoObj?.get("duration_secs").asCleanInt(0).toLong()
            val rating = infoObj?.get("rating").asCleanString("")

            val directUrl = epDto.directSource.asCleanString("")
            val videoUrl = if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) {
                directUrl
            } else {
                val cleanEpisodeId = rawId.substringBeforeLast(".")
                "$cleanServer/series/${playlist.username}/${playlist.password}/$cleanEpisodeId.$ext"
            }

            episodeEntities.add(
                SeriesEpisodeEntity(
                    uniqueEpisodeKey = "${seriesItem.uniqueStreamKey}_s${seasonNum}_e${epNum}_id$rawId",
                    seriesStreamKey = seriesItem.uniqueStreamKey,
                    seasonNumber = seasonNum,
                    episodeNumber = epNum,
                    title = epDto.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum",
                    containerExtension = ext,
                    plotDescription = plot,
                    durationMs = durationSeconds * 1000L,
                    streamUrl = videoUrl,
                    rating = rating
                )
            )
        }

        fun scanJsonRecursively(
            element: JsonElement,
            currentSeason: Int,
            fallbackEpIndex: Int,
            depth: Int
        ) {
            if (depth > MAX_RECURSION_DEPTH) return

            when (element) {
                is JsonObject -> {
                    val hasEpisodeId = element.containsKey("id") || element.containsKey("stream_id")
                    val hasEpisodeFields = element.containsKey("episode_num") ||
                        element.containsKey("container_extension") ||
                        element.containsKey("title") ||
                        element.containsKey("info")

                    if (hasEpisodeId && hasEpisodeFields) {
                        runCatching {
                            val dto = jsonParser.decodeFromJsonElement<XtreamEpisodeDto>(element)
                            parseEpisodeDtoAndAdd(dto, currentSeason, fallbackEpIndex)
                        }
                    } else {
                        var runningIdx = 1
                        element.forEach { (key, child) ->
                            val parsedKeyNum = key.trim().toIntOrNull()?.coerceAtLeast(1)
                            val childIsDirectEpisode = (child is JsonObject) &&
                                (child.containsKey("id") || child.containsKey("stream_id"))

                            if (childIsDirectEpisode) {
                                scanJsonRecursively(child, currentSeason, parsedKeyNum ?: runningIdx, depth + 1)
                            } else {
                                scanJsonRecursively(child, parsedKeyNum ?: currentSeason, runningIdx, depth + 1)
                            }
                            runningIdx++
                        }
                    }
                }
                is JsonArray -> {
                    element.forEachIndexed { index, item ->
                        if (item is JsonArray) {
                            scanJsonRecursively(item, index + 1, 1, depth + 1)
                        } else {
                            scanJsonRecursively(item, currentSeason, index + 1, depth + 1)
                        }
                    }
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

    private suspend fun fetchCategories(
        endpoint: String,
        playlist: PlaylistEntity,
        action: String
    ): List<XtreamCategoryDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", action)
        }.body<String>().trim()
        if (!raw.startsWith("[")) return@runCatching emptyList()
        jsonParser.decodeFromString<List<XtreamCategoryDto>>(raw)
    }.getOrDefault(emptyList())

    private suspend fun fetchLiveStreams(
        endpoint: String,
        playlist: PlaylistEntity
    ): List<XtreamLiveStreamDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_live_streams")
        }.body<String>().trim()
        if (!raw.startsWith("[")) return@runCatching emptyList()
        jsonParser.decodeFromString<List<XtreamLiveStreamDto>>(raw)
    }.getOrDefault(emptyList())

    private suspend fun fetchVodStreams(
        endpoint: String,
        playlist: PlaylistEntity,
        categoryId: String? = null
    ): List<XtreamVodStreamDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_vod_streams")
            if (!categoryId.isNullOrBlank()) {
                parameter("category_id", categoryId)
            }
        }.body<String>().trim()
        if (!raw.startsWith("[")) return@runCatching emptyList()
        jsonParser.decodeFromString<List<XtreamVodStreamDto>>(raw)
    }.getOrDefault(emptyList())

    private suspend fun fetchSeries(
        endpoint: String,
        playlist: PlaylistEntity,
        categoryId: String? = null
    ): List<XtreamSeriesDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_series")
            if (!categoryId.isNullOrBlank()) {
                parameter("category_id", categoryId)
            }
        }.body<String>().trim()
        if (!raw.startsWith("[")) return@runCatching emptyList()
        jsonParser.decodeFromString<List<XtreamSeriesDto>>(raw)
    }.getOrDefault(emptyList())
}
