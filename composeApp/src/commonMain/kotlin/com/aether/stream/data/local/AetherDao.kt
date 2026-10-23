package com.aether.stream.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// ==========================================
// 1. LIVE-TV & KATEGORIE DAOS
// ==========================================

@Dao
interface ChannelDao {
    @Query("""
        SELECT * FROM channels 
        WHERE categoryId = :categoryId 
        ORDER BY orderIndex ASC, name ASC
    """)
    fun getChannelsByCategoryFlow(categoryId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteChannelsFlow(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE customFolderId = :folderId ORDER BY name ASC")
    fun getChannelsByCustomFolderFlow(folderId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE streamId = :streamId LIMIT 1")
    suspend fun getChannelById(streamId: Int): ChannelEntity?

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchChannels(query: String, limit: Int = 100): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE streamId = :streamId")
    suspend fun updateFavoriteStatus(streamId: Int, isFavorite: Boolean)

    @Query("UPDATE channels SET customFolderId = :folderId WHERE streamId = :streamId")
    suspend fun updateCustomFolder(streamId: Int, folderId: String?)

    @Query("DELETE FROM channels")
    suspend fun deleteAllChannels()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE categoryType = :type AND isVisible = 1 AND isLocked = 0 ORDER BY sortOrder ASC")
    fun getVisibleCategoriesFlow(type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE categoryType = :type ORDER BY sortOrder ASC")
    fun getAllCategoriesFlow(type: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isNewlyDiscovered = 1")
    fun getNewlyDiscoveredCategoriesFlow(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Query("UPDATE categories SET isVisible = :isVisible WHERE categoryId = :categoryId")
    suspend fun updateVisibility(categoryId: String, isVisible: Boolean)

    @Query("UPDATE categories SET isLocked = :isLocked WHERE categoryId = :categoryId")
    suspend fun updateLockStatus(categoryId: String, isLocked: Boolean)

    @Query("UPDATE categories SET isNewlyDiscovered = 0 WHERE categoryId = :categoryId")
    suspend fun clearNewlyDiscovered(categoryId: String)

    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()
}

@Dao
interface CustomFolderDao {
    @Query("SELECT * FROM custom_folders ORDER BY sortOrder ASC, folderName ASC")
    fun getAllFoldersFlow(): Flow<List<CustomFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: CustomFolderEntity)

    @Query("DELETE FROM custom_folders WHERE folderId = :folderId")
    suspend fun deleteFolder(folderId: String)
}

// ==========================================
// 2. VOD & SERIEN DAOS (PHASE 2)
// ==========================================

@Dao
interface VodDao {
    @Query("SELECT * FROM vod_streams WHERE categoryId = :categoryId ORDER BY name ASC")
    fun getVodsByCategoryFlow(categoryId: String): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod_streams WHERE categoryId = :categoryId ORDER BY name ASC")
    suspend fun getVodsByCategory(categoryId: String): List<VodEntity>

    @Query("SELECT * FROM vod_streams WHERE streamId = :streamId LIMIT 1")
    suspend fun getVodById(streamId: Int): VodEntity?

    @Query("SELECT * FROM vod_streams WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavoriteVodsFlow(): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod_streams WHERE customFolderId = :folderId ORDER BY name ASC")
    fun getVodsByCustomFolderFlow(folderId: String): Flow<List<VodEntity>>

    @Query("SELECT * FROM vod_streams WHERE name LIKE '%' || :query || '%' ORDER BY rating DESC LIMIT :limit")
    suspend fun searchVods(query: String, limit: Int = 100): List<VodEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVods(vods: List<VodEntity>)

    @Upsert
    suspend fun upsertVod(vod: VodEntity)

    @Query("UPDATE vod_streams SET isFavorite = :isFavorite WHERE streamId = :streamId")
    suspend fun updateFavoriteStatus(streamId: Int, isFavorite: Boolean)

    @Query("UPDATE vod_streams SET customFolderId = :folderId WHERE streamId = :streamId")
    suspend fun updateCustomFolder(streamId: Int, folderId: String?)

    @Query("DELETE FROM vod_streams")
    suspend fun deleteAllVods()

    @Query("SELECT COUNT(*) FROM vod_streams")
    suspend fun getVodCount(): Int
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY title ASC")
    fun getSeriesByCategoryFlow(categoryId: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE categoryId = :categoryId ORDER BY title ASC")
    suspend fun getSeriesByCategory(categoryId: String): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE seriesId = :seriesId LIMIT 1")
    suspend fun getSeriesById(seriesId: Int): SeriesEntity?

    @Query("SELECT * FROM series WHERE isFavorite = 1 ORDER BY title ASC")
    fun getFavoriteSeriesFlow(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE customFolderId = :folderId ORDER BY title ASC")
    fun getSeriesByCustomFolderFlow(folderId: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE title LIKE '%' || :query || '%' ORDER BY rating DESC LIMIT :limit")
    suspend fun searchSeries(query: String, limit: Int = 100): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(seriesList: List<SeriesEntity>)

    @Upsert
    suspend fun upsertSeries(series: SeriesEntity)

    @Query("UPDATE series SET isFavorite = :isFavorite WHERE seriesId = :seriesId")
    suspend fun updateFavoriteStatus(seriesId: Int, isFavorite: Boolean)

    @Query("UPDATE series SET customFolderId = :folderId WHERE seriesId = :seriesId")
    suspend fun updateCustomFolder(seriesId: Int, folderId: String?)

    @Query("DELETE FROM series")
    suspend fun deleteAllSeries()

    @Query("SELECT COUNT(*) FROM series")
    suspend fun getSeriesCount(): Int
}

@Dao
interface EpisodeDao {
    @Query("SELECT DISTINCT seasonNumber FROM series_episodes WHERE seriesId = :seriesId ORDER BY seasonNumber ASC")
    suspend fun getAvailableSeasons(seriesId: Int): List<Int>

    @Query("SELECT * FROM series_episodes WHERE seriesId = :seriesId AND seasonNumber = :seasonNumber ORDER BY episodeNumber ASC")
    fun getEpisodesBySeasonFlow(seriesId: Int, seasonNumber: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM series_episodes WHERE seriesId = :seriesId AND seasonNumber = :seasonNumber ORDER BY episodeNumber ASC")
    suspend fun getEpisodesBySeason(seriesId: Int, seasonNumber: Int): List<EpisodeEntity>

    @Query("SELECT * FROM series_episodes WHERE id = :episodeId LIMIT 1")
    suspend fun getEpisodeById(episodeId: String): EpisodeEntity?

    @Query("""
        SELECT * FROM series_episodes 
        WHERE seriesId = :seriesId 
          AND (seasonNumber > :currentSeason OR (seasonNumber = :currentSeason AND episodeNumber > :currentEpisode))
        ORDER BY seasonNumber ASC, episodeNumber ASC 
        LIMIT 1
    """)
    suspend fun findNextEpisode(seriesId: Int, currentSeason: Int, currentEpisode: Int): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisodes(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM series_episodes WHERE seriesId = :seriesId")
    suspend fun deleteEpisodesForSeries(seriesId: Int)
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE contentId = :contentId LIMIT 1")
    suspend fun getProgress(contentId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE contentId = :contentId LIMIT 1")
    fun getProgressFlow(contentId: String): Flow<WatchProgressEntity?>

    @Query("""
        SELECT * FROM watch_progress 
        WHERE isFinished = 0 AND percentage >= :minPercentage 
        ORDER BY lastWatchedUtc DESC 
        LIMIT :limit
    """)
    fun getContinueWatchingFlow(
        minPercentage: Float = WatchProgressEntity.MIN_RESUME_PERCENTAGE,
        limit: Int = 30
    ): Flow<List<WatchProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveProgress(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE contentId = :contentId")
    suspend fun deleteProgress(contentId: String)

    @Query("UPDATE watch_progress SET isFinished = 1, positionMs = 0 WHERE contentId = :contentId")
    suspend fun markAsCompleted(contentId: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clearHistory()
}
