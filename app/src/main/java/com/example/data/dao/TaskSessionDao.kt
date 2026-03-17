package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.data.entity.TaskSession

@Dao
interface TaskSessionDao {

    @Insert
    suspend fun insert(session: TaskSession): Long

    @Update
    suspend fun update(session: TaskSession)

    @Query("UPDATE task_sessions SET status = :status WHERE id = :sessionId")
    suspend fun updateStatus(sessionId: Long, status: String)

    @Query("SELECT * FROM task_sessions ORDER BY created_at DESC")
    suspend fun getAll(): List<TaskSession>

    @Query("SELECT * FROM task_sessions WHERE id = :id")
    suspend fun getById(id: Long): TaskSession?

    @Query("DELETE FROM task_sessions")
    suspend fun deleteAll()
}
