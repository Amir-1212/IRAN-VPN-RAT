package com.example.systemsync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface LocationDao {
    @Insert suspend fun insert(location: LocationEntity)
    @Query("SELECT * FROM locations WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun getHistory(from: Long, to: Long): List<LocationEntity>
    @Query("SELECT * FROM locations ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastLocation(): LocationEntity?
    @Query("DELETE FROM locations WHERE timestamp < :before")
    suspend fun deleteOld(before: Long)
}
