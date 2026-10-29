package com.aether.stream.data.local

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AetherDao {

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY id DESC LIMIT 1")
    fun observeActivePlaylist(): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE isActive = 1 ORDER BY id DESC LIMIT 1")
    suspend fun getActivePlaylistOnce(): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdatePlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET isInitialSyncCompleted = 1, lastSyncEpochMillis = :timestamp WHERE id = :playlistId")
    suspend fun markPlaylistSynced(playlistId: Long, timestamp: Long)

    @Transaction
    suspend fun setEntireSectionVisibility(
        playlistId: Long,
        sectionType: AetherSectionType,
        visible: Boolean
    ) {
        updateAllCategoriesVisibilityInSection(playlistId, sectionType, visible)
        updateAllStreamsVisibilityInSection(playlistId, sectionType, visible)
    }

    @Query("UPDATE categories SET isVisible = :visible, isNewlyDiscovered = 0 WHERE playlistId = :playlistId AND sectionType = :sectionType")
    suspend fun updateAllCategoriesVisibilityInSection(
        playlistId: Long,
        sectionType: AetherSectionType,
        visible: Boolean
    )

    @Query("UPDATE stream_items SET isVisible = :visible WHERE playlistId = :playlistId AND sectionType = :sectionType")
    suspend fun updateAllStreamsVisibilityInSection(
        playlistId: Long,
        sectionType: AetherSectionType,
        visible: Boolean
    )

    @Transaction
    suspend fun setCategoryAndChildrenVisibility(uniqueCategoryKey: String, visible: Boolean) {
        updateSingleCategoryVisibility(uniqueCategoryKey, visible)
        updateStreamsVisibilityByCategory(uniqueCategoryKey, visible)
    }

    @Query("UPDATE categories SET isVisible = :visible, isNewlyDiscovered = 0 WHERE uniqueCategoryKey = :uniqueCategoryKey")
    suspend fun updateSingleCategoryVisibility(uniqueCategoryKey: String, visible: Boolean)

    @Query("UPDATE stream_items SET isVisible = :visible WHERE uniqueCategoryKey = :uniqueCategoryKey")
    suspend fun updateStreamsVisibilityByCategory(uniqueCategoryKey: String, visible: Boolean)

    @Query("UPDATE stream_items SET isVisible = :visible WHERE uniqueStreamKey = :uniqueStreamKey")
    suspend fun setSingleStreamVisibility(uniqueStreamKey: String, visible: Boolean)

    @Query("UPDATE categories SET isLocked = :locked WHERE uniqueCategoryKey = :uniqueCategoryKey")
    suspend fun setCategoryLockState(uniqueCategoryKey: String, locked: Boolean)

    @Query("UPDATE stream_items SET isLocked = :locked WHERE uniqueStreamKey = :uniqueStreamKey")
    suspend fun setStreamLockState(uniqueStreamKey: String, locked: Boolean)

    @Query("""
        SELECT * FROM categories 
        WHERE playlistId = :playlistId 
          AND sectionType = :sectionType 
          AND isVisible = 1 
          AND isLocked = 0 
        ORDER BY customSortOrder ASC, categoryName ASC
    """)
    fun observeVisibleCategories(
        playlistId: Long,
        sectionType: AetherSectionType
    ): Flow<List<CategoryEntity>>

    @Query("""
        SELECT * FROM stream_items 
        WHERE uniqueCategoryKey = :uniqueCategoryKey 
          AND isVisible = 1 
          AND isLocked = 0 
        ORDER BY customChannelNumber ASC, name ASC
    """)
    fun observeVisibleStreamsInCategory(uniqueCategoryKey: String): Flow<List<StreamItemEntity>>

    @Query("SELECT COUNT(*) FROM stream_items WHERE uniqueCategoryKey = :uniqueCategoryKey")
    suspend fun getStreamCountInCategory(uniqueCategoryKey: String): Int

    @Query("""
        SELECT COUNT(*) FROM stream_items 
        WHERE playlistId = :playlistId 
          AND sectionType = :sectionType 
          AND isVisible = 1 
          AND isLocked = 0
    """)
    suspend fun getVisibleStreamCountForSection(
        playlistId: Long,
        sectionType: AetherSectionType
    ): Int

    @Query("""
        SELECT * FROM categories 
        WHERE playlistId = :playlistId 
          AND sectionType = :sectionType 
        ORDER BY isNewlyDiscovered DESC, customSortOrder ASC, categoryName ASC
    """)
    fun observeAllCategoriesForFilterTree(
        playlistId: Long,
        sectionType: AetherSectionType
    ): Flow<List<CategoryEntity>>

    @Query("""
        SELECT * FROM stream_items 
        WHERE uniqueCategoryKey = :uniqueCategoryKey 
        ORDER BY customChannelNumber ASC, name ASC
    """)
    fun observeAllStreamsForFilterTree(uniqueCategoryKey: String): Flow<List<StreamItemEntity>>

    @Query("SELECT COUNT(*) FROM categories WHERE playlistId = :playlistId AND isNewlyDiscovered = 1")
    fun observeNewlyDiscoveredCategoriesCount(playlistId: Long): Flow<Int>

    @Query("SELECT uniqueCategoryKey FROM categories WHERE playlistId = :playlistId")
    suspend fun getExistingCategoryKeys(playlistId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewCategoriesIgnoringExisting(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNewStreamsIgnoringExisting(streams: List<StreamItemEntity>)

    @Query("UPDATE stream_items SET isQuickFavorite = :isFav WHERE uniqueStreamKey = :uniqueStreamKey")
    suspend fun setQuickFavorite(uniqueStreamKey: String, isFav: Boolean)

    @Query("""
        SELECT * FROM stream_items 
        WHERE playlistId = :playlistId 
          AND sectionType = :sectionType 
          AND isQuickFavorite = 1 
          AND isVisible = 1 
          AND isLocked = 0 
        ORDER BY name ASC
    """)
    fun observeQuickFavorites(
        playlistId: Long,
        sectionType: AetherSectionType
    ): Flow<List<StreamItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createFavoriteFolder(folder: FavoriteFolderEntity): Long

    @Query("SELECT * FROM favorite_folders WHERE playlistId = :playlistId ORDER BY sortOrder ASC, folderName ASC")
    fun observeFavoriteFolders(playlistId: Long): Flow<List<FavoriteFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addStreamToFavoriteFolder(mapping: FavoriteFolderMappingEntity)

    @Query("DELETE FROM favorite_folder_mappings WHERE folderId = :folderId AND uniqueStreamKey = :uniqueStreamKey")
    suspend fun removeStreamFromFavoriteFolder(folderId: Long, uniqueStreamKey: String)

    @Query("""
        SELECT s.* FROM stream_items s
        INNER JOIN favorite_folder_mappings m ON s.uniqueStreamKey = m.uniqueStreamKey
        WHERE m.folderId = :folderId AND s.isVisible = 1 AND s.isLocked = 0 
        ORDER BY m.addedEpochMillis DESC
    """)
    fun observeStreamsInFavoriteFolder(folderId: Long): Flow<List<StreamItemEntity>>

    @Query("""
        UPDATE stream_items 
        SET resumePositionMs = :positionMs, 
            totalDurationMs = :durationMs, 
            lastWatchedEpochMillis = :watchedAt 
        WHERE uniqueStreamKey = :uniqueStreamKey
    """)
    suspend fun updateWatchProgress(
        uniqueStreamKey: String,
        positionMs: Long,
        durationMs: Long,
        watchedAt: Long
    )

    @Query("""
        SELECT * FROM stream_items 
        WHERE playlistId = :playlistId 
          AND sectionType = :sectionType 
          AND lastWatchedEpochMillis > 0 
          AND isVisible = 1 
          AND isLocked = 0 
        ORDER BY lastWatchedEpochMillis DESC 
        LIMIT 30
    """)
    fun observeRecentlyWatched(
        playlistId: Long,
        sectionType: AetherSectionType
    ): Flow<List<StreamItemEntity>>

    /**
     * GLOBALE SUCHE: Sucht simultan über Live-TV, VOD-Filme und Serien
     */
    @Query("""
        SELECT * FROM stream_items 
        WHERE playlistId = :playlistId 
          AND isVisible = 1 
          AND isLocked = 0 
          AND name LIKE '%' || :searchQuery || '%' 
        ORDER BY 
          CASE sectionType 
            WHEN 'LIVE' THEN 1 
            WHEN 'VOD' THEN 2 
            WHEN 'SERIES' THEN 3 
          END, 
          name ASC 
        LIMIT 100
    """)
    fun searchVisibleStreams(
        playlistId: Long,
        searchQuery: String
    ): Flow<List<StreamItemEntity>>

    // ==========================================
    // PHASE 2: SERIEN-EPISODEN & FORTSCHRITT
    // ==========================================

    @Query("SELECT DISTINCT seasonNumber FROM series_episodes WHERE seriesStreamKey = :seriesStreamKey ORDER BY seasonNumber ASC")
    suspend fun getAvailableSeasons(seriesStreamKey: String): List<Int>

    @Query("SELECT * FROM series_episodes WHERE seriesStreamKey = :seriesStreamKey AND seasonNumber = :seasonNumber ORDER BY episodeNumber ASC")
    fun observeEpisodesForSeason(seriesStreamKey: String, seasonNumber: Int): Flow<List<SeriesEpisodeEntity>>

    @Query("SELECT * FROM series_episodes WHERE uniqueEpisodeKey = :uniqueEpisodeKey LIMIT 1")
    suspend fun getEpisodeByKey(uniqueEpisodeKey: String): SeriesEpisodeEntity?

    @Query("""
        UPDATE series_episodes 
        SET resumePositionMs = :positionMs, 
            durationMs = :durationMs, 
            lastWatchedEpochMillis = :watchedAt 
        WHERE uniqueEpisodeKey = :uniqueEpisodeKey
    """)
    suspend fun updateEpisodeWatchProgress(
        uniqueEpisodeKey: String,
        positionMs: Long,
        durationMs: Long,
        watchedAt: Long
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateEpisodes(episodes: List<SeriesEpisodeEntity>)

    @Query("DELETE FROM series_episodes WHERE seriesStreamKey = :seriesStreamKey")
    suspend fun deleteEpisodesForSeries(seriesStreamKey: String)

    // ==========================================
    // PHASE 3: EPG TIMELINE MATRIX, BATCH-CHUNKING & PRUNING
    // ==========================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpgProgramsChunk(programs: List<EpgProgramEntity>)

    /**
     * Schreibt große EPG-Datenmengen in performanten 2.000er-Transaktions-Chunks weg,
     * um OOM-Abstürze und SQLite-WAL-Blockaden auf dem Fire TV zu verhindern.
     */
    @Transaction
    suspend fun insertEpgProgramsInBatches(programs: List<EpgProgramEntity>) {
        if (programs.isEmpty()) return
        programs.chunked(2000).forEach { batch ->
            insertEpgProgramsChunk(batch)
        }
    }

    /**
     * Reaktive Zeitfenster-Abfrage für den aktuell fokussierten/laufenden Kanal (Zone 3 & Zone 4).
     * Nutzt den B-Tree-Index (epgChannelId, startEpochMillis, endEpochMillis).
     */
    @Query("""
        SELECT * FROM epg_programs 
        WHERE epgChannelId = :epgChannelId 
          AND endEpochMillis > :windowStartMs 
          AND startEpochMillis < :windowEndMs 
        ORDER BY startEpochMillis ASC 
        LIMIT 40
    """)
    fun observeEpgForChannelInWindow(
        epgChannelId: String,
        windowStartMs: Long,
        windowEndMs: Long
    ): Flow<List<EpgProgramEntity>>

    @Query("""
        SELECT * FROM epg_programs 
        WHERE epgChannelId = :epgChannelId 
          AND endEpochMillis > :windowStartMs 
          AND startEpochMillis < :windowEndMs 
        ORDER BY startEpochMillis ASC 
        LIMIT 40
    """)
    suspend fun getEpgForChannelInWindow(
        epgChannelId: String,
        windowStartMs: Long,
        windowEndMs: Long
    ): List<EpgProgramEntity>

    /**
     * Gebündelte Viewport-Abfrage für die sichtbaren Kanäle in Zone 4 innerhalb des 2-Stunden-Fensters.
     */
    @Query("""
        SELECT * FROM epg_programs 
        WHERE epgChannelId IN (:epgChannelIds) 
          AND endEpochMillis > :windowStartMs 
          AND startEpochMillis < :windowEndMs 
        ORDER BY epgChannelId ASC, startEpochMillis ASC
    """)
    suspend fun getEpgForChannelsBatchInWindow(
        epgChannelIds: List<String>,
        windowStartMs: Long,
        windowEndMs: Long
    ): List<EpgProgramEntity>

    @Query("SELECT COUNT(DISTINCT epgChannelId) FROM epg_programs")
    suspend fun getDistinctEpgChannelsCount(): Int

    @Query("DELETE FROM epg_programs WHERE epgChannelId = :epgChannelId")
    suspend fun deleteEpgForChannel(epgChannelId: String)

    /**
     * Automatisches EPG-Pruning: Entfernt alle abgelaufenen Sendungen vor :cutoffEpochMillis (Bild 5).
     */
    @Query("DELETE FROM epg_programs WHERE endEpochMillis < :cutoffEpochMillis")
    suspend fun deleteExpiredEpgProgramsBefore(cutoffEpochMillis: Long)

    /**
     * Komplett-Reset bei Klick auf „EPG -> Löschen“ (Bild 5 & 6).
     */
    @Query("DELETE FROM epg_programs")
    suspend fun clearAllEpgPrograms()
}

@Database(
    entities = [
        PlaylistEntity::class,
        CategoryEntity::class,
        StreamItemEntity::class,
        FavoriteFolderEntity::class,
        FavoriteFolderMappingEntity::class,
        EpgProgramEntity::class,
        SeriesEpisodeEntity::class
    ],
    version = 2,
    exportSchema = false
)
@ConstructedBy(AetherDatabaseConstructor::class)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun aetherDao(): AetherDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AetherDatabaseConstructor : RoomDatabaseConstructor<AetherDatabase>
