package com.example.smarttrackapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.smarttrackapp.models.TripHistory
import com.example.smarttrackapp.models.Vehicle

@Database(
    entities = [Vehicle::class, TripHistory::class],
    version = 5, // Incremented version
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun tripHistoryDao(): TripHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_5 = object : Migration(1, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add all migrations from version 1 to 5
                database.execSQL("ALTER TABLE vehicles ADD COLUMN deviceId TEXT DEFAULT ''")
                database.execSQL("ALTER TABLE vehicles ADD COLUMN isOnline INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE vehicles ADD COLUMN lastConnection INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smarttrack_db"
                )
                    .addMigrations(MIGRATION_1_5) // Use the combined migration
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}