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
    @SerialName("container_extension") val containerExtension: String? = "mp4"
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
    @SerialName("episode_num") val episodeNum: JsonElement? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("container_extension") val containerExtension: String? = "mp4",
    @SerialName("info") val info: XtreamEpisodeInfoDto? = null
)

@Serializable
data class XtreamEpisodeInfoDto(
    @SerialName("plot") val plot: String? = null,
    @SerialName("duration_secs") val durationSecs: JsonElement? = null,
    @SerialName("duration") val duration: String? = null,
    @SerialName("rating") val rating: JsonElement? = null
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
                    val ext = dto.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
                    allStreamEntities.add(
                        StreamItemEntity(
                            uniqueStreamKey = "${playlist.id}_VOD_$sId",
                            playlistId = playlist.id,
                            providerStreamId = sId,
                            uniqueCategoryKey = finalCatKey,
                            sectionType = AetherSectionType.VOD,
                            name = dto.name?.takeIf { it.isNotBlank() } ?: "Film #$sId",
                            logoUrl = dto.streamIcon,
                            streamUrl = "$baseUrl/movie/${playlist.username}/${playlist.password}/$sId.$ext",
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
     * Phase 2: Lädt alle Staffeln und Episoden einer Serie on demand herunter
     * und speichert sie in der Room-Tabelle 'series_episodes'.
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
        val episodesObj = episodesElement as? JsonObject ?: return@withContext false

        val episodeEntities = mutableListOf<SeriesEpisodeEntity>()

        episodesObj.forEach { (seasonKey, seasonEpisodesArray) ->
            val seasonNum = seasonKey.toIntOrNull() ?: 1
            if (seasonEpisodesArray is JsonArray) {
                val dtos = runCatching {
                    jsonParser.decodeFromJsonElement<List<XtreamEpisodeDto>>(seasonEpisodesArray)
                }.getOrDefault(emptyList())

                dtos.forEach { epDto ->
                    val epId = epDto.id.asCleanString("")
                    val epNum = epDto.episodeNum.asCleanInt(1)
                    if (epId.isNotEmpty()) {
                        val durationSeconds = epDto.info?.durationSecs.asCleanInt(0).toLong()
                        val ext = epDto.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
                        val videoUrl = "$baseUrl/series/${playlist.username}/${playlist.password}/$epId.$ext"

                        episodeEntities.add(
                            SeriesEpisodeEntity(
                                uniqueEpisodeKey = "${seriesItem.uniqueStreamKey}_s${seasonNum}_e${epNum}",
                                seriesStreamKey = seriesItem.uniqueStreamKey,
                                seasonNumber = seasonNum,
                                episodeNumber = epNum,
                                title = epDto.title?.takeIf { it.isNotBlank() } ?: "Episode $epNum",
                                containerExtension = ext,
                                plotDescription = epDto.info?.plot,
                                durationMs = durationSeconds * 1000L,
                                streamUrl = videoUrl,
                                rating = epDto.info?.rating.asCleanString("")
                            )
                        )
                    }
                }
            }
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
