package com.aether.stream.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(
    entities = [
        ChannelEntity::class,
        CategoryEntity::class,
        CustomFolderEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        WatchProgressEntity::class
    ],
    version = 2,
    exportSchema = false
)
@ConstructedBy(AetherDatabaseConstructor::class)
abstract class AetherDatabase : RoomDatabase() {
    // Live TV DAOs
    abstract fun channelDao(): ChannelDao
    abstract fun categoryDao(): CategoryDao
    abstract fun customFolderDao(): CustomFolderDao

    // Phase 2 VOD & Serien DAOs
    abstract fun vodDao(): VodDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun watchProgressDao(): WatchProgressDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AetherDatabaseConstructor : RoomDatabaseConstructor<AetherDatabase>
