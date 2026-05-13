package de.dhde.wifilogger

import android.content.Context
import androidx.room.*

@TypeConverters(Converters::class)
@Database(entities = [WifiEvent::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wifiEventDao(): WifiEventDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "wifi_events.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

class Converters {
    @TypeConverter fun fromEventType(value: EventType): String = value.name
    @TypeConverter fun toEventType(value: String): EventType = EventType.valueOf(value)
}
