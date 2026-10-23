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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

/**
 * 100% crash-resistent: 'info' und 'container_extension' sind als JsonElement deklariert,
 * damit ein leeres PHP-Array ("info": []) niemals den Staffel-Abruf abbricht!
 */
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
        val timeZone = TimeZone.currentSystemDefault()
        val lastSyncDate = kotlinx.datetime.Instant
            .fromEpochMilliseconds(activePlaylist.lastSyncEpochMillis)
            .toLocalDateTime(timeZone).date
        val todayDate = Clock.System.now().toLocalDateTime(timeZone).date

        if (activePlaylist.lastSyncEpochMillis == 0L || lastSyncDate < todayDate) {
            return@withContext synchronizePlaylistWithSmartLock(activePlaylist)
        }
        return@withContext 0
    }

    suspend fun synchronizePlaylistWithSmartLock(
        playlist: PlaylistEntity
    ): Int = withContext(Dispatchers.IO) {
        val baseUrl = playlist.serverUrl.trimEnd('/')
        val apiEndpoint = "$baseUrl/player_api.php"

        val existingKeys = dao.getExistingCategoryKeys(playlist.id).toSet()
        val isUpgradeSync = playlist.isInitialSyncCompleted && existingKeys.isNotEmpty()

        coroutineScope {
            val liveCatsDeferred = async { fetchCategories(apiEndpoint, playlist, "get_live_categories") }
            val vodCatsDeferred = async { fetchCategories(apiEndpoint, playlist, "get_vod_categories") }
            val seriesCatsDeferred = async { fetchCategories(apiEndpoint, playlist, "get_series_categories") }

            val liveStreamsDeferred = async { fetchLiveStreams(apiEndpoint, playlist) }
            val vodStreamsDeferred = async { fetchVodStreams(apiEndpoint, playlist) }
            val seriesDeferred = async { fetchSeries(apiEndpoint, playlist) }

            val allCategories = mutableListOf<CategoryEntity>()
            val knownCategoryKeys = mutableSetOf<String>()
            var newlyDiscoveredCount = 0

            fun registerCategory(
                catId: String,
                catName: String,
                section: AetherSectionType,
                sortIndex: Int
            ): String {
                val key = "${playlist.id}_${section.name}_$catId"
                if (knownCategoryKeys.add(key)) {
                    val isBrandNewGroup = isUpgradeSync && !existingKeys.contains(key)
                    if (isBrandNewGroup) newlyDiscoveredCount++
                    allCategories.add(
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

            liveCatsDeferred.await().forEachIndexed { idx, dto ->
                registerCategory(
                    catId = dto.categoryId.asCleanString("0"),
                    catName = dto.categoryName?.takeIf { it.isNotBlank() } ?: "Unbenannte Gruppe",
                    section = AetherSectionType.LIVE,
                    sortIndex = idx
                )
            }
            vodCatsDeferred.await().forEachIndexed { idx, dto ->
                registerCategory(
                    catId = dto.categoryId.asCleanString("0"),
                    catName = dto.categoryName?.takeIf { it.isNotBlank() } ?: "Unbenannte Filme",
                    section = AetherSectionType.VOD,
                    sortIndex = idx
                )
            }
            seriesCatsDeferred.await().forEachIndexed { idx, dto ->
                registerCategory(
                    catId = dto.categoryId.asCleanString("0"),
                    catName = dto.categoryName?.takeIf { it.isNotBlank() } ?: "Unbenannte Serien",
                    section = AetherSectionType.SERIES,
                    sortIndex = idx
                )
            }

            val allStreamEntities = mutableListOf<StreamItemEntity>()

            liveStreamsDeferred.await().forEachIndexed { idx, dto ->
                val sId = dto.streamId.asCleanInt(0)
                if (sId != 0) {
                    val rawCatId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
                    val targetCatKey = "${playlist.id}_${AetherSectionType.LIVE.name}_$rawCatId"

                    val finalCatKey = if (knownCategoryKeys.contains(targetCatKey)) {
                        targetCatKey
                    } else {
                        registerCategory(
                            catId = rawCatId,
                            catName = "⚡ Spezial-Feeds & Unkategorisiert ($rawCatId)",
                            section = AetherSectionType.LIVE,
                            sortIndex = 9999
                        )
                    }

                    allStreamEntities.add(
                        StreamItemEntity(
                            uniqueStreamKey = "${playlist.id}_LIVE_$sId",
                            playlistId = playlist.id,
                            providerStreamId = sId,
                            uniqueCategoryKey = finalCatKey,
                            sectionType = AetherSectionType.LIVE,
                            name = dto.name?.takeIf { it.isNotBlank() } ?: "Kanal #$sId",
                            logoUrl = dto.streamIcon,
                            streamUrl = "$baseUrl/live/${playlist.username}/${playlist.password}/$sId.ts",
                            epgChannelId = dto.epgChannelId,
                            containerExtension = "ts",
                            isVisible = true,
                            customChannelNumber = dto.num.asCleanInt(idx + 1)
                        )
                    )
                }
            }

            vodStreamsDeferred.await().forEachIndexed { idx, dto ->
                val sId = dto.streamId.asCleanInt(0)
                if (sId != 0) {
                    val rawCatId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
                    val targetCatKey = "${playlist.id}_${AetherSectionType.VOD.name}_$rawCatId"
                    val finalCatKey = if (knownCategoryKeys.contains(targetCatKey)) {
                        targetCatKey
                    } else {
                        registerCategory(
                            catId = rawCatId,
                            catName = "⚡ Weitere Filme ($rawCatId)",
                            section = AetherSectionType.VOD,
                            sortIndex = 9999
                        )
                    }
                    val ext = dto.containerExtension.asCleanString("mp4").trimStart('.').ifBlank { "mp4" }
                    val directUrl = dto.directSource.asCleanString("")
                    val finalVodUrl = if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) {
                        directUrl
                    } else {
                        "$baseUrl/movie/${playlist.username}/${playlist.password}/$sId.$ext"
                    }

                    allStreamEntities.add(
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

            seriesDeferred.await().forEachIndexed { idx, dto ->
                val sId = dto.seriesId.asCleanInt(0)
                if (sId != 0) {
                    val rawCatId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
                    val targetCatKey = "${playlist.id}_${AetherSectionType.SERIES.name}_$rawCatId"
                    val finalCatKey = if (knownCategoryKeys.contains(targetCatKey)) {
                        targetCatKey
                    } else {
                        registerCategory(
                            catId = rawCatId,
                            catName = "⚡ Weitere Serien ($rawCatId)",
                            section = AetherSectionType.SERIES,
                            sortIndex = 9999
                        )
                    }
                    allStreamEntities.add(
                        StreamItemEntity(
                            uniqueStreamKey = "${playlist.id}_SERIES_$sId",
                            playlistId = playlist.id,
                            providerStreamId = sId,
                            uniqueCategoryKey = finalCatKey,
                            sectionType = AetherSectionType.SERIES,
                            name = dto.name?.takeIf { it.isNotBlank() } ?: "Serie #$sId",
                            logoUrl = dto.cover,
                            streamUrl = "$baseUrl/series/${playlist.username}/${playlist.password}/$sId",
                            rating = dto.rating.asCleanString(""),
                            plotDescription = dto.plot,
                            isVisible = true,
                            customChannelNumber = dto.num.asCleanInt(idx + 1)
                        )
                    )
                }
            }

            dao.insertNewCategoriesIgnoringExisting(allCategories)

            allStreamEntities.chunked(2500).forEach { chunk ->
                dao.insertNewStreamsIgnoringExisting(chunk)
            }

            dao.markPlaylistSynced(
                playlistId = playlist.id,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )

            newlyDiscoveredCount
        }
    }

    /**
     * Phase 2 (100% Universal-Parser):
     * Unterstützt sowohl JsonObject ("1": [...], "2": [...]) als auch JsonArray (1-Staffel-Serien / Listen)
     * und ignoriert kaputte/leere TMDB-Info-Arrays ("info": []), ohne abzustürzen.
     */
    suspend fun fetchAndStoreSeriesEpisodes(
        playlist: PlaylistEntity,
        seriesItem: StreamItemEntity
    ): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = playlist.serverUrl.trimEnd('/')
        val apiEndpoint = "$baseUrl/player_api.php"
        val seriesId = seriesItem.providerStreamId

        val rootElement = runCatching {
            httpClient.get(apiEndpoint) {
                header("User-Agent", IPTV_USER_AGENT)
                parameter("username", playlist.username)
                parameter("password", playlist.password)
                parameter("action", "get_series_info")
                parameter("series_id", seriesId)
            }.body<JsonElement>()
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
                "$baseUrl/series/${playlist.username}/${playlist.password}/$rawId.$ext"
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
        httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", action)
        }.body<List<XtreamCategoryDto>>()
    }.getOrDefault(emptyList())

    private suspend fun fetchLiveStreams(
        endpoint: String,
        playlist: PlaylistEntity
    ): List<XtreamLiveStreamDto> = runCatching {
        httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_live_streams")
        }.body<List<XtreamLiveStreamDto>>()
    }.getOrDefault(emptyList())

    private suspend fun fetchVodStreams(
        endpoint: String,
        playlist: PlaylistEntity
    ): List<XtreamVodStreamDto> = runCatching {
        httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_vod_streams")
        }.body<List<XtreamVodStreamDto>>()
    }.getOrDefault(emptyList())

    private suspend fun fetchSeries(
        endpoint: String,
        playlist: PlaylistEntity
    ): List<XtreamSeriesDto> = runCatching {
        httpClient.get(endpoint) {
            header("User-Agent", IPTV_USER_AGENT)
            parameter("username", playlist.username)
            parameter("password", playlist.password)
            parameter("action", "get_series")
        }.body<List<XtreamSeriesDto>>()
    }.getOrDefault(emptyList())
}
