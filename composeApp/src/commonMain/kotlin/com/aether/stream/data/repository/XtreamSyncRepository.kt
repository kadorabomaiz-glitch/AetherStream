package com.aether.stream.data.repository

import com.aether.stream.data.local.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

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
    (this as? JsonPrimitive)?.intOrNull ?: (this as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull() ?: default

private fun JsonElement?.asCleanLong(default: Long = 0L): Long =
    (this as? JsonPrimitive)?.longOrNull ?: (this as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull() ?: default

object AetherEpgFuzzyMatcher {
    private val prefixCountryRegex = Regex("^(\\|?[a-z]{2,3}\\|?\\s*[-|:]?\\s*)", RegexOption.IGNORE_CASE)
    private val stripTagsRegex = Regex("\\b(de|at|ch|uk|us|tr|pl|fr|es|it|nl|fhd|uhd|4k|8k|hd|sd|hevc|h265|h264|1080p|1080i|720p|50fps|60fps|raw|gold|vip|backup|low|ger|germany)\\b", RegexOption.IGNORE_CASE)
    private val nonAlphaNumRegex = Regex("[^a-z0-9]")
    private val digitsRegex = Regex("\\d+")

    fun normalizeChannelStem(rawName: String?): String {
        if (rawName.isNullOrBlank()) return ""
        return rawName.trim().replace(prefixCountryRegex, "").replace(stripTagsRegex, " ").lowercase().replace(nonAlphaNumRegex, "")
    }

    fun extractNumericSignature(rawName: String?): String {
        if (rawName.isNullOrBlank()) return ""
        val clean = rawName.replace(Regex("\\b(1080p|1080i|720p|50fps|60fps|4k|8k|h264|h265|24/7)\\b", RegexOption.IGNORE_CASE), "")
        return digitsRegex.findAll(clean).joinToString("_") { it.value }
    }

    fun computeLevenshteinSimilarity(stemA: String, stemB: String): Float {
        if (stemA.isEmpty() || stemB.isEmpty()) return 0f
        if (stemA == stemB) return 1.0f
        val lenA = stemA.length; val lenB = stemB.length; val maxLen = max(lenA, lenB)
        if (abs(lenA - lenB).toFloat() / maxLen.toFloat() > 0.35f) return 0f
        var prevRow = IntArray(lenB + 1) { it }; var currRow = IntArray(lenB + 1)
        for (i in 1..lenA) {
            currRow[0] = i; val chA = stemA[i - 1]
            for (j in 1..lenB) {
                val cost = if (chA == stemB[j - 1]) 0 else 1
                currRow[j] = min(min(currRow[j - 1] + 1, prevRow[j] + 1), prevRow[j - 1] + cost)
            }
            val tmp = prevRow; prevRow = currRow; currRow = tmp
        }
        return (1.0f - (prevRow[lenB].toFloat() / maxLen.toFloat())).coerceIn(0f, 1f)
    }

    fun findBestFuzzyMatch(targetChannelName: String, candidateEpgIds: List<String>, minScore: Float = 0.80f): String? {
        val targetStem = normalizeChannelStem(targetChannelName)
        if (targetStem.length < 2) return null
        val targetNumbers = extractNumericSignature(targetChannelName)
        var bestCandidate: String? = null; var bestScore = minScore
        for (candidate in candidateEpgIds) {
            val candNumbers = extractNumericSignature(candidate)
            if (targetNumbers.isNotEmpty() && candNumbers.isNotEmpty() && targetNumbers != candNumbers) continue
            val candStem = normalizeChannelStem(candidate)
            if (candStem.isEmpty()) continue
            if (candStem == targetStem) return candidate
            val score = computeLevenshteinSimilarity(targetStem, candStem)
            if (score > bestScore) { bestScore = score; bestCandidate = candidate }
        }
        return bestCandidate
    }
}

class XtreamSyncRepository(private val dao: AetherDao, private val httpClient: HttpClient) {

    companion object {
        private const val ORPHAN_CATEGORY_ID = "aether_orphan_catch"
        private const val ORPHAN_CATEGORY_NAME = "Uncategorized / Unkategorisiert"
        private const val IPTV_USER_AGENT = "TiviMate/5.1.0 (Linux; Android 11) ExoPlayerLib/2.19.1"
        private const val MAX_RECURSION_DEPTH = 6
        private val jsonParser = Json { isLenient = true; ignoreUnknownKeys = true; coerceInputValues = true; allowSpecialFloatingPointValues = true; allowStructuredMapKeys = true }
    }

    private fun normalizeServerUrl(rawUrl: String): String =
        rawUrl.trim().let { if (!it.startsWith("http://") && !it.startsWith("https://")) "http://$it" else it }.trimEnd('/')

    private fun sanitizeStreamText(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val code = ch.code
            if (code >= 32 && code != 0xFFFD && !ch.isSurrogate()) sb.append(ch) else if (ch == '\t' || ch == '\n') sb.append(' ')
        }
        return sb.toString().trim().ifEmpty { fallback }
    }

    private fun sanitizeRawJsonPayload(raw: String): String {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return ""
        val first = trimmed.indexOfFirst { it == '[' || it == '{' }
        val last = trimmed.indexOfLast { it == ']' || it == '}' }
        if (first < 0 || last <= first) return ""
        val sliced = trimmed.substring(first, last + 1)
        val sb = StringBuilder(sliced.length)
        for (ch in sliced) if (ch.code >= 32 || ch == '\n' || ch == '\r' || ch == '\t') sb.append(ch)
        return sb.toString()
    }

    private inline fun <reified T> decodeListResiliently(rawPayload: String): List<T> {
        val clean = sanitizeRawJsonPayload(rawPayload)
        if (clean.isEmpty()) return emptyList()
        val resultList = ArrayList<T>()
        val fastParsed = runCatching { jsonParser.parseToJsonElement(clean) }.getOrNull()
        if (fastParsed != null) {
            when (fastParsed) {
                is JsonArray -> for (item in fastParsed) if (item is JsonObject) runCatching { resultList.add(jsonParser.decodeFromJsonElement<T>(item)) }
                is JsonObject -> for ((_, item) in fastParsed) if (item is JsonObject) runCatching { resultList.add(jsonParser.decodeFromJsonElement<T>(item)) }
                else -> Unit
            }
            return resultList
        }
        var depth = 0; var inString = false; var isEscaped = false; var objStartIdx = -1
        for (i in clean.indices) {
            val ch = clean[i]
            if (isEscaped) { isEscaped = false; continue }
            if (ch == '\\' && inString) { isEscaped = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (!inString) {
                if (ch == '{') { if (depth == 0) objStartIdx = i; depth++ }
                else if (ch == '}') {
                    depth--
                    if (depth == 0 && objStartIdx >= 0) {
                        runCatching { resultList.add(jsonParser.decodeFromString<T>(clean.substring(objStartIdx, i + 1))) }
                        objStartIdx = -1
                    } else if (depth < 0) { depth = 0; objStartIdx = -1 }
                }
            }
        }
        return resultList
    }

    private fun buildCompositeStreamKey(playlistId: Long, section: AetherSectionType, providerStreamId: Int, uniqueCategoryKey: String, cleanName: String): String {
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
            val vodCount = dao.getVisibleStreamCountForSection(activePlaylist.id, AetherSectionType.VOD)
            val nowMs = Clock.System.now().toEpochMilliseconds()
            val isStale = (nowMs - activePlaylist.lastSyncEpochMillis) > 43_200_000L
            if (existingCatKeys.isNotEmpty() && vodCount > 0 && !isStale) return@withContext 0
            synchronizePlaylistWithSmartLock(activePlaylist)
        }.getOrDefault(0)
    }

    suspend fun synchronizePlaylistWithSmartLock(playlist: PlaylistEntity): Int = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl)
        val apiEndpoint = "$cleanServer/player_api.php"
        val existingKeys = runCatching { dao.getExistingCategoryKeys(playlist.id).toSet() }.getOrDefault(emptySet())
        val isUpgradeSync = playlist.isInitialSyncCompleted && existingKeys.isNotEmpty()
        val knownCategoryKeys = mutableSetOf<String>()
        var newlyDiscoveredCount = 0

        fun buildCategoryEntity(catId: String, catName: String, section: AetherSectionType, sortIndex: Int, targetList: MutableList<CategoryEntity>): String {
            val key = "${playlist.id}_${section.name}_$catId"
            if (knownCategoryKeys.add(key)) {
                val isNew = isUpgradeSync && !existingKeys.contains(key)
                if (isNew) newlyDiscoveredCount++
                targetList.add(CategoryEntity(key, playlist.id, catId, sanitizeStreamText(catName, "Kategorie"), section, true, false, isNew, sortIndex))
            }
            return key
        }

        val liveCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_live_categories").forEachIndexed { idx, dto ->
            val cId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
            buildCategoryEntity(cId, if (cId == ORPHAN_CATEGORY_ID) ORPHAN_CATEGORY_NAME else (dto.categoryName?.takeIf { it.isNotBlank() } ?: "Live TV"), AetherSectionType.LIVE, idx, liveCategories)
        }
        if (liveCategories.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(liveCategories)

        val vodCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_vod_categories").forEachIndexed { idx, dto ->
            val cId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
            buildCategoryEntity(cId, if (cId == ORPHAN_CATEGORY_ID) ORPHAN_CATEGORY_NAME else (dto.categoryName?.takeIf { it.isNotBlank() } ?: "Filme"), AetherSectionType.VOD, idx, vodCategories)
        }
        if (vodCategories.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(vodCategories)

        val seriesCategories = mutableListOf<CategoryEntity>()
        fetchCategories(apiEndpoint, playlist, "get_series_categories").forEachIndexed { idx, dto ->
            val cId = dto.categoryId.asCleanString(ORPHAN_CATEGORY_ID)
            buildCategoryEntity(cId, if (cId == ORPHAN_CATEGORY_ID) ORPHAN_CATEGORY_NAME else (dto.categoryName?.takeIf { it.isNotBlank() } ?: "Serien"), AetherSectionType.SERIES, idx, seriesCategories)
        }
        if (seriesCategories.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(seriesCategories)

        val liveStreams = ArrayList<StreamItemEntity>(4096)
        val orphanLiveCats = mutableListOf<CategoryEntity>()
        fetchLiveStreams(apiEndpoint, playlist).forEachIndexed { idx, dto ->
            val sId = dto.streamId.asCleanInt(0)
            if (sId != 0) {
                val rawCatId = dto.categoryId.asCleanString("")
                val candKey = "${playlist.id}_${AetherSectionType.LIVE.name}_$rawCatId"
                val finalCatKey = if (rawCatId.isNotEmpty() && knownCategoryKeys.contains(candKey)) candKey else buildCategoryEntity(ORPHAN_CATEGORY_ID, ORPHAN_CATEGORY_NAME, AetherSectionType.LIVE, 9999, orphanLiveCats)
                val cleanName = sanitizeStreamText(dto.name, "Kanal #$sId")
                val compKey = buildCompositeStreamKey(playlist.id, AetherSectionType.LIVE, sId, finalCatKey, cleanName)
                val normEpg = AetherEpgFuzzyMatcher.normalizeChannelStem(cleanName)
                liveStreams.add(StreamItemEntity(compKey, playlist.id, sId, finalCatKey, AetherSectionType.LIVE, cleanName, dto.streamIcon?.trim(), "$cleanServer/live/${playlist.username}/${playlist.password}/$sId.ts", dto.epgChannelId?.trim()?.takeIf { it.isNotBlank() } ?: normEpg.ifEmpty { compKey }, "ts", true, false, dto.num.asCleanInt(idx + 1)))
            }
        }
        if (orphanLiveCats.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(orphanLiveCats)
        if (liveStreams.isNotEmpty()) liveStreams.chunked(2500).forEach { chunk -> runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }; yield() }

        val vodStreams = ArrayList<StreamItemEntity>(4096)
        val orphanVodCats = mutableListOf<CategoryEntity>()
        fetchVodStreams(apiEndpoint, playlist, null).forEachIndexed { idx, dto ->
            val sId = dto.streamId.asCleanInt(0)
            if (sId != 0) {
                val rawCatId = dto.categoryId.asCleanString("")
                val candKey = "${playlist.id}_${AetherSectionType.VOD.name}_$rawCatId"
                val finalCatKey = if (rawCatId.isNotEmpty() && knownCategoryKeys.contains(candKey)) candKey else buildCategoryEntity(ORPHAN_CATEGORY_ID, ORPHAN_CATEGORY_NAME, AetherSectionType.VOD, 9999, orphanVodCats)
                val ext = dto.containerExtension.asCleanString("mp4").trimStart('.').ifBlank { "mp4" }
                val directUrl = dto.directSource.asCleanString("")
                val finalVodUrl = if (directUrl.startsWith("http://") || directUrl.startsWith("https://")) directUrl else "$cleanServer/movie/${playlist.username}/${playlist.password}/$sId.$ext"
                val cleanName = sanitizeStreamText(dto.name, "Film #$sId")
                val compKey = buildCompositeStreamKey(playlist.id, AetherSectionType.VOD, sId, finalCatKey, cleanName)
                vodStreams.add(StreamItemEntity(compKey, playlist.id, sId, finalCatKey, AetherSectionType.VOD, cleanName, dto.streamIcon?.trim(), finalVodUrl, null, ext, true, false, dto.num.asCleanInt(idx + 1), false, dto.rating.asCleanString("")))
            }
        }
        if (orphanVodCats.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(orphanVodCats)
        if (vodStreams.isNotEmpty()) vodStreams.chunked(2500).forEach { chunk -> runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }; yield() }

        val seriesStreams = ArrayList<StreamItemEntity>(2048)
        val orphanSeriesCats = mutableListOf<CategoryEntity>()
        fetchSeries(apiEndpoint, playlist, null).forEachIndexed { idx, dto ->
            val sId = dto.seriesId.asCleanInt(0)
            if (sId != 0) {
                val rawCatId = dto.categoryId.asCleanString("")
                val candKey = "${playlist.id}_${AetherSectionType.SERIES.name}_$rawCatId"
                val finalCatKey = if (rawCatId.isNotEmpty() && knownCategoryKeys.contains(candKey)) candKey else buildCategoryEntity(ORPHAN_CATEGORY_ID, ORPHAN_CATEGORY_NAME, AetherSectionType.SERIES, 9999, orphanSeriesCats)
                val cleanName = sanitizeStreamText(dto.name, "Serie #$sId")
                val compKey = buildCompositeStreamKey(playlist.id, AetherSectionType.SERIES, sId, finalCatKey, cleanName)
                seriesStreams.add(StreamItemEntity(compKey, playlist.id, sId, finalCatKey, AetherSectionType.SERIES, cleanName, dto.cover?.trim(), "$cleanServer/series/${playlist.username}/${playlist.password}/$sId", null, "mp4", true, false, dto.num.asCleanInt(idx + 1), false, dto.rating.asCleanString(""), dto.plot?.let { sanitizeStreamText(it, "") }?.takeIf { it.isNotBlank() }))
            }
        }
        if (orphanSeriesCats.isNotEmpty()) dao.insertNewCategoriesIgnoringExisting(orphanSeriesCats)
        if (seriesStreams.isNotEmpty()) seriesStreams.chunked(2500).forEach { chunk -> runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }; yield() }

        dao.markPlaylistSynced(playlist.id, Clock.System.now().toEpochMilliseconds())
        newlyDiscoveredCount
    }

    suspend fun fetchAndStoreShortEpgForStream(playlist: PlaylistEntity, stream: StreamItemEntity, storeDescriptions: Boolean = true): List<EpgProgramEntity> = withContext(Dispatchers.IO) {
        val nowMs = Clock.System.now().toEpochMilliseconds()
        val windowStartMs = nowMs - 3_600_000L; val windowEndMs = nowMs + 21_600_000L
        val channelKey = stream.epgChannelId?.takeIf { it.isNotBlank() } ?: stream.uniqueStreamKey
        val normalizedStem = AetherEpgFuzzyMatcher.normalizeChannelStem(stream.name)

        if (stream.providerStreamId > 0) {
            val apiEndpoint = "${normalizeServerUrl(playlist.serverUrl)}/player_api.php"
            val parsedPrograms = runCatching {
                val raw = httpClient.get(apiEndpoint) {
                    header("User-Agent", IPTV_USER_AGENT)
                    parameter("username", playlist.username); parameter("password", playlist.password)
                    parameter("action", "get_short_epg"); parameter("stream_id", stream.providerStreamId); parameter("limit", 16)
                }.body<String>()
                val cleanJson = sanitizeRawJsonPayload(raw)
                if (!cleanJson.startsWith("{")) return@runCatching emptyList()
                val rootObj = jsonParser.parseToJsonElement(cleanJson) as? JsonObject ?: return@runCatching emptyList()
                val listings = rootObj["epg_listings"] as? JsonArray ?: return@runCatching emptyList()
                listings.mapNotNull { elem ->
                    val dto = runCatching { jsonParser.decodeFromJsonElement<XtreamEpgListingDto>(elem) }.getOrNull() ?: return@mapNotNull null
                    val startSec = dto.startTimestamp.asCleanLong(0L); val stopSec = dto.stopTimestamp.asCleanLong(0L)
                    if (startSec <= 0L || stopSec <= startSec) return@mapNotNull null
                    val decodedTitle = sanitizeStreamText(decodeBase64OrPlain(dto.title), "Live Programm")
                    val decodedDesc = if (storeDescriptions) sanitizeStreamText(decodeBase64OrPlain(dto.description), "").takeIf { it.isNotBlank() } else null
                    EpgProgramEntity(epgChannelId = channelKey, title = decodedTitle, description = decodedDesc, startEpochMillis = startSec * 1000L, endEpochMillis = stopSec * 1000L, normalizedChannelName = normalizedStem)
                }
            }.getOrDefault(emptyList())
            if (parsedPrograms.isNotEmpty()) { dao.deleteEpgForChannel(channelKey); dao.insertEpgProgramsInBatches(parsedPrograms); return@withContext parsedPrograms }
        }
        if (normalizedStem.isNotEmpty()) {
            val byNorm = runCatching { dao.getEpgByNormalizedNameInWindow(normalizedStem, windowStartMs, windowEndMs) }.getOrDefault(emptyList())
            if (byNorm.isNotEmpty()) return@withContext byNorm
        }
        val allEpgIds = runCatching { dao.getAllDistinctEpgChannelIds() }.getOrDefault(emptyList())
        if (allEpgIds.isNotEmpty()) {
            val matchedId = AetherEpgFuzzyMatcher.findBestFuzzyMatch(stream.name, allEpgIds)
            if (matchedId != null) return@withContext runCatching { dao.getEpgForChannelInWindow(matchedId, windowStartMs, windowEndMs) }.getOrDefault(emptyList())
        }
        emptyList()
    }

    suspend fun pruneExpiredEpgAndGetChannelCount(pastDaysToKeep: Int = 7): Int = withContext(Dispatchers.IO) {
        runCatching {
            dao.deleteExpiredEpgProgramsBefore(Clock.System.now().toEpochMilliseconds() - (pastDaysToKeep.coerceIn(1, 30).toLong() * 86_400_000L))
            dao.getDistinctEpgChannelsCount()
        }.getOrDefault(0)
    }

    suspend fun clearAllEpgCache() = withContext(Dispatchers.IO) { runCatching { dao.clearAllEpgPrograms() } }

    suspend fun fetchVodStreamsForCategory(playlist: PlaylistEntity, category: CategoryEntity): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl); val apiEndpoint = "$cleanServer/player_api.php"
        val dtos = fetchVodStreams(apiEndpoint, playlist, if (category.providerCategoryId == ORPHAN_CATEGORY_ID) null else category.providerCategoryId)
        if (dtos.isEmpty()) return@withContext false
        val entities = dtos.mapIndexedNotNull { idx, dto ->
            val sId = dto.streamId.asCleanInt(0)
            if (sId == 0) null else {
                val ext = dto.containerExtension.asCleanString("mp4").trimStart('.').ifBlank { "mp4" }
                val direct = dto.directSource.asCleanString("")
                val url = if (direct.startsWith("http://") || direct.startsWith("https://")) direct else "$cleanServer/movie/${playlist.username}/${playlist.password}/$sId.$ext"
                val cleanName = sanitizeStreamText(dto.name, "Film #$sId")
                val compKey = buildCompositeStreamKey(playlist.id, AetherSectionType.VOD, sId, category.uniqueCategoryKey, cleanName)
                StreamItemEntity(compKey, playlist.id, sId, category.uniqueCategoryKey, AetherSectionType.VOD, cleanName, dto.streamIcon?.trim(), url, null, ext, true, false, dto.num.asCleanInt(idx + 1), false, dto.rating.asCleanString(""))
            }
        }
        if (entities.isNotEmpty()) { entities.chunked(2500).forEach { chunk -> runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }; yield() }; return@withContext true }
        false
    }

    suspend fun fetchSeriesForCategory(playlist: PlaylistEntity, category: CategoryEntity): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl); val apiEndpoint = "$cleanServer/player_api.php"
        val dtos = fetchSeries(apiEndpoint, playlist, if (category.providerCategoryId == ORPHAN_CATEGORY_ID) null else category.providerCategoryId)
        if (dtos.isEmpty()) return@withContext false
        val entities = dtos.mapIndexedNotNull { idx, dto ->
            val sId = dto.seriesId.asCleanInt(0)
            if (sId == 0) null else {
                val cleanName = sanitizeStreamText(dto.name, "Serie #$sId")
                val compKey = buildCompositeStreamKey(playlist.id, AetherSectionType.SERIES, sId, category.uniqueCategoryKey, cleanName)
                StreamItemEntity(compKey, playlist.id, sId, category.uniqueCategoryKey, AetherSectionType.SERIES, cleanName, dto.cover?.trim(), "$cleanServer/series/${playlist.username}/${playlist.password}/$sId", null, "mp4", true, false, dto.num.asCleanInt(idx + 1), false, dto.rating.asCleanString(""), dto.plot?.let { sanitizeStreamText(it, "") }?.takeIf { it.isNotBlank() })
            }
        }
        if (entities.isNotEmpty()) { entities.chunked(2500).forEach { chunk -> runCatching { dao.insertNewStreamsIgnoringExisting(chunk) }; yield() }; return@withContext true }
        false
    }

    suspend fun fetchAndStoreSeriesEpisodes(playlist: PlaylistEntity, seriesItem: StreamItemEntity): Boolean = withContext(Dispatchers.IO) {
        val cleanServer = normalizeServerUrl(playlist.serverUrl); val apiEndpoint = "$cleanServer/player_api.php"
        val rootObj = runCatching {
            val raw = httpClient.get(apiEndpoint) {
                header("User-Agent", IPTV_USER_AGENT); parameter("username", playlist.username); parameter("password", playlist.password)
                parameter("action", "get_series_info"); parameter("series_id", seriesItem.providerStreamId)
            }.body<String>()
            val clean = sanitizeRawJsonPayload(raw)
            if (clean.isEmpty()) null else jsonParser.parseToJsonElement(clean) as? JsonObject
        }.getOrNull() ?: return@withContext false

        val episodesElement = rootObj["episodes"] ?: rootObj["seasons"] ?: return@withContext false
        val episodeEntities = mutableListOf<SeriesEpisodeEntity>()

        fun parseEp(epDto: XtreamEpisodeDto, fallbackSeason: Int, fallbackEpIdx: Int) {
            val rawId = epDto.id.asCleanString("").ifEmpty { epDto.streamId.asCleanString("") }
            if (rawId.isEmpty()) return
            val infoObj = epDto.info as? JsonObject
            val seasonNum = epDto.season.asCleanInt(fallbackSeason).coerceAtLeast(1)
            val epNum = epDto.episodeNum.asCleanInt(fallbackEpIdx).coerceAtLeast(1)
            val ext = epDto.containerExtension.asCleanString("").ifEmpty { infoObj?.get("container_extension").asCleanString("mp4") }.trimStart('.').ifBlank { "mp4" }
            val plot = infoObj?.get("plot").asCleanString("").let { sanitizeStreamText(it, "") }.takeIf { it.isNotEmpty() }
            val durSec = infoObj?.get("duration_secs").asCleanInt(0).toLong()
            val direct = epDto.directSource.asCleanString("")
            val videoUrl = if (direct.startsWith("http://") || direct.startsWith("https://")) direct else "$cleanServer/series/${playlist.username}/${playlist.password}/${rawId.substringBeforeLast(".")}.$ext"
            episodeEntities.add(SeriesEpisodeEntity("${seriesItem.uniqueStreamKey}_s${seasonNum}_e${epNum}_id$rawId", seriesItem.uniqueStreamKey, seasonNum, epNum, sanitizeStreamText(epDto.title, "Episode $epNum"), ext, plot, durSec * 1000L, 0L, 0L, videoUrl, infoObj?.get("rating").asCleanString("")))
        }

        fun scan(el: JsonElement, curSeason: Int, fbIdx: Int, depth: Int) {
            if (depth > MAX_RECURSION_DEPTH) return
            when (el) {
                is JsonObject -> {
                    if ((el.containsKey("id") || el.containsKey("stream_id")) && (el.containsKey("episode_num") || el.containsKey("container_extension") || el.containsKey("title") || el.containsKey("info"))) {
                        runCatching { parseEp(jsonParser.decodeFromJsonElement(el), curSeason, fbIdx) }
                    } else {
                        var runIdx = 1
                        el.forEach { (k, child) ->
                            val kNum = k.trim().toIntOrNull()?.coerceAtLeast(1)
                            if (child is JsonObject && (child.containsKey("id") || child.containsKey("stream_id"))) scan(child, curSeason, kNum ?: runIdx, depth + 1)
                            else scan(child, kNum ?: curSeason, runIdx, depth + 1)
                            runIdx++
                        }
                    }
                }
                is JsonArray -> el.forEachIndexed { idx, item -> if (item is JsonArray) scan(item, idx + 1, 1, depth + 1) else scan(item, curSeason, idx + 1, depth + 1) }
                else -> Unit
            }
        }
        scan(episodesElement, 1, 1, 0)
        if (episodeEntities.isNotEmpty()) { dao.insertOrUpdateEpisodes(episodeEntities); return@withContext true }
        false
    }

    private suspend fun fetchCategories(ep: String, pl: PlaylistEntity, act: String): List<XtreamCategoryDto> = runCatching {
        decodeListResiliently<XtreamCategoryDto>(httpClient.get(ep) { header("User-Agent", IPTV_USER_AGENT); parameter("username", pl.username); parameter("password", pl.password); parameter("action", act) }.body())
    }.getOrDefault(emptyList())

    private suspend fun fetchLiveStreams(ep: String, pl: PlaylistEntity): List<XtreamLiveStreamDto> = runCatching {
        decodeListResiliently<XtreamLiveStreamDto>(httpClient.get(ep) { header("User-Agent", IPTV_USER_AGENT); parameter("username", pl.username); parameter("password", pl.password); parameter("action", "get_live_streams") }.body())
    }.getOrDefault(emptyList())

    private suspend fun fetchVodStreams(ep: String, pl: PlaylistEntity, catId: String? = null): List<XtreamVodStreamDto> = runCatching {
        decodeListResiliently<XtreamVodStreamDto>(httpClient.get(ep) { header("User-Agent", IPTV_USER_AGENT); parameter("username", pl.username); parameter("password", pl.password); parameter("action", "get_vod_streams"); if (!catId.isNullOrBlank()) parameter("category_id", catId) }.body())
    }.getOrDefault(emptyList())

    private suspend fun fetchSeries(ep: String, pl: PlaylistEntity, catId: String? = null): List<XtreamSeriesDto> = runCatching {
        decodeListResiliently<XtreamSeriesDto>(httpClient.get(ep) { header("User-Agent", IPTV_USER_AGENT); parameter("username", pl.username); parameter("password", pl.password); parameter("action", "get_series"); if (!catId.isNullOrBlank()) parameter("category_id", catId) }.body())
    }.getOrDefault(emptyList())
}
