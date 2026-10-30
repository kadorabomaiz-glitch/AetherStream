package com.aether.stream.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AetherSectionType {
    LIVE,
    VOD,
    SERIES
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistName: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    val isActive: Boolean = true,
    val expirationTimestampSec: Long? = null,
    val maxConnections: Int = 1,
    val isInitialSyncCompleted: Boolean = false,
    val lastSyncEpochMillis: Long = 0L,
    val filterPinCode: String = "0000"
)

@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["playlistId", "sectionType", "isVisible", "isLocked"]),
        Index(value = ["playlistId", "isNewlyDiscovered"])
    ]
)
data class CategoryEntity(
    @PrimaryKey val uniqueCategoryKey: String,
    val playlistId: Long,
    val providerCategoryId: String,
    val categoryName: String,
    val sectionType: AetherSectionType,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val isNewlyDiscovered: Boolean = false,
    val customSortOrder: Int = 0
)

/**
 * L10 ZERO-DROP COMPOSITE PRIMARY KEY MATRIX:
 * Kombiniert (providerStreamId, uniqueCategoryKey, name), damit identische Stream-IDs
 * in verschiedenen Kategorien oder unter unterschiedlichen Feed-Namen niemals verworfen werden.
 */
@Entity(
    tableName = "stream_items",
    primaryKeys = ["providerStreamId", "uniqueCategoryKey", "name"],
    indices = [
        Index(value = ["uniqueStreamKey"], unique = true),
        Index(value = ["uniqueCategoryKey", "isVisible", "isLocked"]),
        Index(value = ["playlistId", "sectionType", "isQuickFavorite"]),
        Index(value = ["playlistId", "lastWatchedEpochMillis"])
    ]
)
data class StreamItemEntity(
    val uniqueStreamKey: String,
    val playlistId: Long,
    val providerStreamId: Int,
    val uniqueCategoryKey: String,
    val sectionType: AetherSectionType,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val epgChannelId: String? = null,
    val containerExtension: String? = "ts",
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    val customChannelNumber: Int = 0,
    val isQuickFavorite: Boolean = false,
    val rating: String? = null,
    val plotDescription: String? = null,
    val resumePositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val lastWatchedEpochMillis: Long = 0L
)

@Entity(tableName = "favorite_folders")
data class FavoriteFolderEntity(
    @PrimaryKey(autoGenerate = true) val folderId: Long = 0L,
    val playlistId: Long,
    val folderName: String,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "favorite_folder_mappings",
    primaryKeys = ["folderId", "uniqueStreamKey"],
    indices = [Index(value = ["uniqueStreamKey"])]
)
data class FavoriteFolderMappingEntity(
    val folderId: Long,
    val uniqueStreamKey: String,
    val addedEpochMillis: Long
)

@Entity(
    tableName = "epg_programs",
    indices = [Index(value = ["epgChannelId", "startEpochMillis", "endEpochMillis"])]
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val epgChannelId: String,
    val title: String,
    val description: String?,
    val startEpochMillis: Long,
    val endEpochMillis: Long
)

@Entity(
    tableName = "series_episodes",
    indices = [
        Index(value = ["seriesStreamKey", "seasonNumber", "episodeNumber"], unique = true),
        Index(value = ["seriesStreamKey"]),
        Index(value = ["seasonNumber"])
    ]
)
data class SeriesEpisodeEntity(
    @PrimaryKey val uniqueEpisodeKey: String,
    val seriesStreamKey: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String = "mp4",
    val plotDescription: String? = null,
    val durationMs: Long = 0L,
    val resumePositionMs: Long = 0L,
    val lastWatchedEpochMillis: Long = 0L,
    val streamUrl: String,
    val rating: String? = null
)
