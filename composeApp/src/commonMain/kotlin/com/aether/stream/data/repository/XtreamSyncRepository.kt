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
        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }

    suspend fun triggerDailySilentSyncIfNeeded(): Int = withContext(Dispatchers.IO) {
        val activePlaylist = dao.getActivePlaylistOnce() ?: return@withContext 0
        val existingCatKeys = dao.getExistingCategoryKeys(activePlaylist.id)
        val timeZone = TimeZone.currentSystemDefault()
        val lastSyncDate = kotlinx.datetime.Instant
            .fromEpochMilliseconds(activePlaylist.lastSyncEpochMillis)
            .toLocalDateTime(timeZone).date
        val todayDate = Clock.System.now().toLocalDateTime(timeZone).date

        // Auch synchronisieren, wenn die Datenbank trotz Sync-Zeitstempel noch 0 Kategorien hat!
        if (activePlaylist.lastSyncEpochMillis == 0L || existingCatKeys.isEmpty() || lastSyncDate < todayDate) {
            return@withContext synchronizePlaylistWithSmartLock(activePlaylist)
        }
        return@withContext 0
    }

    /**
     * Stufenweiser Sofort-Sync (Sequenziell):
     * Verhindert das Blockieren durch Xtream-Firewalls (kein 6-fach-Flooding)
     * und speichert Live-TV, VOD und Serien jeweils sofort nach dem Laden in die Datenbank!
     */
    suspend fun synchronizePlaylistWithSmartLock(
        playlist: PlaylistEntity
    ): Int = withContext(Dispatchers.IO) {
        val cleanServer = playlist.serverUrl.trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "http://$it" else it
        }.trimEnd('/')
        val apiEndpoint = "$cleanServer/player_api.php"

        val existingKeys = dao.getExistingCategoryKeys(playlist.id).toSet()
        val isUpgradeSync = playlist.isInitialSyncCompleted && existingKeys.isNotEmpty()

        val knownCategoryKeys = mutableSetOf<String>()
        var newlyDiscoveredCount = 0
        var totalStreamsSaved = 0

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

        // ==========================================
        // STUFE 1: LIVE-TV LADEN & SOFORT SPEICHERN
        // ==========================================
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

        val liveStreams = mutableListOf<StreamItemEntity>()
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
                        targetList = liveCategories
                    )
                }

                liveStreams.add(
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
            }
        }

        if (liveCategories.isNotEmpty()) {
            dao.insertNewCategoriesIgnoringExisting(liveCategories)
        }
        if (liveStreams.isNotEmpty()) {
            liveStreams.chunked(2500).forEach { chunk ->
                dao.insertNewStreamsIgnoringExisting(chunk)
            }
            totalStreamsSaved += liveStreams.size
        }

        // ==========================================
        // STUFE 2: VOD / FILME LADEN & SOFORT SPEICHERN
        // ==========================================
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

        val vodStreams = mutableListOf<StreamItemEntity>()
        fetchVodStreams(apiEndpoint, playlist).forEachIndexed { idx, dto ->
            val sId = dto.streamId.asCleanInt(0)
            if (sId != 0) {
                val rawCatId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
                val targetCatKey = "${playlist.id}_${AetherSectionType.VOD.name}_$rawCatId"
                val finalCatKey = if (knownCategoryKeys.contains(targetCatKey)) {
                    targetCatKey
                } else {
                    buildCategoryEntity(
                        catId = rawCatId,
                        catName = "⚡ Weitere Filme ($rawCatId)",
                        section = AetherSectionType.VOD,
                        sortIndex = 9999,
                        targetList = vodCategories
                    )
                }
                val ext = dto.containerExtension.asCleanString("mp4").trimStart('.').ifBlank { "mp4" }
                val directUrl = dto.directSource.asCleanString("")
                val finalVodUrl = if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) {
                    directUrl
                } else {
                    "$cleanServer/movie/${playlist.username}/${playlist.password}/$sId.$ext"
                }

                vodStreams.add(
                    StreamItemEntity(
                        uniqueStreamKey = "${playlist.id}_VOD_$sId",
                        playlistId = playlist.id,
                        providerStreamId = sId,
                        uniqueCategoryKey = finalCatKey,
                        sectionType = AetherSectionType.VOD,
                        name = dto.name?.takeIf { it.isNotBlank() } ?: "Film #$sId",
                        logoUrl = dto.streamIcon,
                        streamUrl = finalVodUrl,
                        containerExtension = ext,
                        rating = dto.rating.asCleanString(""),
                        isVisible = true,
                        customChannelNumber = dto.num.asCleanInt(idx + 1)
                    )
                )
            }
        }

        if (vodCategories.isNotEmpty()) {
            dao.insertNewCategoriesIgnoringExisting(vodCategories)
        }
        if (vodStreams.isNotEmpty()) {
            vodStreams.chunked(2500).forEach { chunk ->
                dao.insertNewStreamsIgnoringExisting(chunk)
            }
            totalStreamsSaved += vodStreams.size
        }

        // ==========================================
        // STUFE 3: SERIEN LADEN & SOFORT SPEICHERN
        // ==========================================
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

        val seriesStreams = mutableListOf<StreamItemEntity>()
        fetchSeries(apiEndpoint, playlist).forEachIndexed { idx, dto ->
            val sId = dto.seriesId.asCleanInt(0)
            if (sId != 0) {
                val rawCatId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
                val targetCatKey = "${playlist.id}_${AetherSectionType.SERIES.name}_$rawCatId"
                val finalCatKey = if (knownCategoryKeys.contains(targetCatKey)) {
                    targetCatKey
                } else {
                    buildCategoryEntity(
                        catId = rawCatId,
                        catName = "⚡ Weitere Serien ($rawCatId)",
                        section = AetherSectionType.SERIES,
                        sortIndex = 9999,
                        targetList = seriesCategories
                    )
                }
                seriesStreams.add(
                    StreamItemEntity(
                        uniqueStreamKey = "${playlist.id}_SERIES_$sId",
                        playlistId = playlist.id,
                        providerStreamId = sId,
                        uniqueCategoryKey = finalCatKey,
                        sectionType = AetherSectionType.SERIES,
                        name = dto.name?.takeIf { it.isNotBlank() } ?: "Serie #$sId",
                        logoUrl = dto.cover,
                        streamUrl = "$cleanServer/series/${playlist.username}/${playlist.password}/$sId",
                        rating = dto.rating.asCleanString(""),
                        plotDescription = dto.plot,
                        isVisible = true,
                        customChannelNumber = dto.num.asCleanInt(idx + 1)
                    )
                )
            }
        }

        if (seriesCategories.isNotEmpty()) {
            dao.insertNewCategoriesIgnoringExisting(seriesCategories)
        }
        if (seriesStreams.isNotEmpty()) {
            seriesStreams.chunked(2500).forEach { chunk ->
                dao.insertNewStreamsIgnoringExisting(chunk)
            }
            totalStreamsSaved += seriesStreams.size
        }

        // Nur als erfolgreich synchronisiert markieren, wenn tatsächlich Streams empfangen wurden!
        if (totalStreamsSaved > 0) {
            dao.markPlaylistSynced(
                playlistId = playlist.id,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
        }

        newlyDiscoveredCount
    }

    suspend fun fetchAndStoreSeriesEpisodes(
        playlist: PlaylistEntity,
        seriesItem: StreamItemEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = playlist.serverUrl.trim().let {
            if (!it.startsWith("http://") && !it.startsWith("https://")) "http://$it" else it
        }.trimEnd('/')
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
            jsonParser.parseToJsonElement(rawText)
        }.getOrNull() ?: return@withContext false

        val episodesElement = (rootElement as? JsonObject)?.get("episodes") ?: return@withContext false
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
                "$cleanServer/series/${playlist.username}/${playlist.password}/$rawId.$ext"
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

        when (episodesElement) {
            is JsonObject -> {
                episodesElement.forEach { (seasonKey, seasonValue) ->
                    val seasonNum = seasonKey.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
                    if (seasonValue is JsonArray) {
                        seasonValue.forEachIndexed { index, itemElement ->
                            runCatching {
                                val dto = jsonParser.decodeFromJsonElement<XtreamEpisodeDto>(itemElement)
                                parseEpisodeDtoAndAdd(dto, seasonNum, index + 1)
                            }
                        }
                    }
                }
            }
            is JsonArray -> {
                episodesElement.forEachIndexed { outerIndex, element ->
                    when (element) {
                        is JsonArray -> {
                            val seasonNum = outerIndex + 1
                            element.forEachIndexed { innerIndex, itemElement ->
                                runCatching {
                                    val dto = jsonParser.decodeFromJsonElement<XtreamEpisodeDto>(itemElement)
                                    parseEpisodeDtoAndAdd(dto, seasonNum, innerIndex + 1)
                                }
                            }
                        }
                        is JsonObject -> {
                            runCatching {
                                val dto = jsonParser.decodeFromJsonElement<XtreamEpisodeDto>(element)
                                parseEpisodeDtoAndAdd(dto, 1, outerIndex + 1)
                            }
                        }
                        else -> Unit
                    }
                }
            }
            else -> Unit
        }

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
        }.body<String>()
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
        }.body<String>()
        jsonParser.decodeFromString<List<XtreamLiveStreamDto>>(raw)
    }.getOrDefault(emptyList())

    private suspend fun fetchVodStreams(
        endpoint: String,
        playlist: PlaylistEntity
    ): List<XtreamVodStreamDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_vod_streams")
        }.body<String>()
        jsonParser.decodeFromString<List<XtreamVodStreamDto>>(raw)
    }.getOrDefault(emptyList())

    private suspend fun fetchSeries(
        endpoint: String,
        playlist: PlaylistEntity
    ): List<XtreamSeriesDto> = runCatching {
        val raw = httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_series")
        }.body<String>()
        jsonParser.decodeFromString<List<XtreamSeriesDto>>(raw)
    }.getOrDefault(emptyList())
}
