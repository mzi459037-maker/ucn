package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CachedConversationEntity::class,
        CachedMessageEntity::class,
        PendingOutboxMessageEntity::class,
        CachedCallLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class UcnDatabase : RoomDatabase() {
    abstract fun offlineDao(): OfflineDao

    companion object {
        @Volatile
        private var INSTANCE: UcnDatabase? = null

        fun getInstance(context: Context): UcnDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    UcnDatabase::class.java,
                    "ucn_offline_vault.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
