// ==========================================
// VOD & SERIEN ENTITIES (PHASE 2)
// ==========================================

@Entity(
    tableName = "vod_streams",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["name"]),
        Index(value = ["rating"]),
        Index(value = ["addedTimestamp"])
    ]
)
data class VodEntity(
    @PrimaryKey
    val streamId: Int,
    val name: String,
    val streamIcon: String?,
    val categoryId: String,
    val rating: Float = 0.0f,
    val rating5Based: Float = 0.0f,
    val year: String? = null,
    val containerExtension: String = "mp4",
    val addedTimestamp: Long = 0L,
    val directSourceUrl: String? = null,
    val isFavorite: Boolean = false,
    val customFolderId: String? = null
)

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["title"]),
        Index(value = ["rating"]),
        Index(value = ["lastModified"])
    ]
)
data class SeriesEntity(
    @PrimaryKey
    val seriesId: Int,
    val title: String,
    val cover: String?,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: Float = 0.0f,
    val rating5Based: Float = 0.0f,
    val categoryId: String,
    val backdropUrl: String? = null,
    val lastModified: Long = 0L,
    val isFavorite: Boolean = false,
    val customFolderId: String? = null
)

@Entity(
    tableName = "series_episodes",
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["seriesId", "seasonNumber", "episodeNumber"], unique = true),
        Index(value = ["episodeNumber"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = SeriesEntity::class,
            parentColumns = ["seriesId"],
            childColumns = ["seriesId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class EpisodeEntity(
    @PrimaryKey
    val id: String, // Format: "${seriesId}_s${seasonNumber}_e${episodeNumber}"
    val seriesId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String = "mp4",
    val plot: String? = null,
    val durationSeconds: Long = 0L,
    val rating: Float = 0.0f,
    val addedTimestamp: Long = 0L,
    val directSourceUrl: String? = null
)

@Entity(
    tableName = "watch_progress",
    indices = [
        Index(value = ["lastWatchedUtc"]),
        Index(value = ["contentType"]),
        Index(value = ["isFinished"])
    ]
)
data class WatchProgressEntity(
    @PrimaryKey
    val contentId: String, // "vod_$streamId" oder "ep_$episodeId"
    val contentType: String, // "VOD" oder "SERIES_EPISODE"
    val referenceId: String,
    val seriesId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val percentage: Float = 0.0f,
    val lastWatchedUtc: Long = 0L,
    val isFinished: Boolean = false
) {
    companion object {
        const val TYPE_VOD = "VOD"
        const val TYPE_EPISODE = "SERIES_EPISODE"
        const val MIN_RESUME_PERCENTAGE = 0.03f
        const val COMPLETION_PERCENTAGE = 0.92f
    }
}
// ============================================================
// PHASE 2: VOD, SERIEN & WATCH-PROGRESS ENTITIES
// ============================================================

@Entity(
    tableName = "vod_streams",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["name"]),
        Index(value = ["rating"]),
        Index(value = ["addedTimestamp"])
    ]
)
data class VodEntity(
    @PrimaryKey
    val streamId: Int,
    val name: String,
    val streamIcon: String?,
    val categoryId: String,
    val rating: Float = 0.0f,
    val rating5Based: Float = 0.0f,
    val year: String? = null,
    val containerExtension: String = "mp4",
    val addedTimestamp: Long = 0L,
    val directSourceUrl: String? = null,
    val isFavorite: Boolean = false,
    val customFolderId: String? = null
)

@Entity(
    tableName = "series",
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["title"]),
        Index(value = ["rating"]),
        Index(value = ["lastModified"])
    ]
)
data class SeriesEntity(
    @PrimaryKey
    val seriesId: Int,
    val title: String,
    val cover: String?,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: Float = 0.0f,
    val rating5Based: Float = 0.0f,
    val categoryId: String,
    val backdropUrl: String? = null,
    val lastModified: Long = 0L,
    val isFavorite: Boolean = false,
    val customFolderId: String? = null
)

@Entity(
    tableName = "series_episodes",
    indices = [
        Index(value = ["seriesId"]),
        Index(value = ["seriesId", "seasonNumber", "episodeNumber"], unique = true),
        Index(value = ["episodeNumber"])
    ]
)
data class EpisodeEntity(
    @PrimaryKey
    val id: String, // Zusammengesetzt: "${seriesId}_s${seasonNumber}_e${episodeNumber}"
    val seriesId: Int,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val containerExtension: String = "mp4",
    val plot: String? = null,
    val durationSeconds: Long = 0L,
    val rating: Float = 0.0f,
    val addedTimestamp: Long = 0L,
    val directSourceUrl: String? = null
)

@Entity(
    tableName = "watch_progress",
    indices = [
        Index(value = ["lastWatchedUtc"]),
        Index(value = ["contentType"]),
        Index(value = ["isFinished"])
    ]
)
data class WatchProgressEntity(
    @PrimaryKey
    val contentId: String, // "vod_$streamId" oder "ep_$episodeId"
    val contentType: String, // "VOD" oder "SERIES_EPISODE"
    val referenceId: String,
    val seriesId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val title: String,
    val subtitle: String? = null,
    val posterUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val percentage: Float = 0.0f,
    val lastWatchedUtc: Long = 0L,
    val isFinished: Boolean = false
) {
    companion object {
        const val TYPE_VOD = "VOD"
        const val TYPE_EPISODE = "SERIES_EPISODE"
        const val MIN_RESUME_PERCENTAGE = 0.03f
        const val COMPLETION_PERCENTAGE = 0.92f
    }
}
