package de.dhde.wifilogger

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface WifiEventDao {
    @Insert suspend fun insert(event: WifiEvent): Long
    @Query("SELECT * FROM wifi_events ORDER BY timestamp DESC")
    fun getAllEvents(): LiveData<List<WifiEvent>>
    @Query("SELECT * FROM wifi_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 500): List<WifiEvent>
    @Query("SELECT * FROM wifi_events WHERE timestamp >= :from ORDER BY timestamp DESC")
    suspend fun getEventsSince(from: Long): List<WifiEvent>
    @Query("DELETE FROM wifi_events WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)
    @Query("DELETE FROM wifi_events") suspend fun deleteAll()
    @Query("SELECT COUNT(*) FROM wifi_events") suspend fun count(): Int
}
