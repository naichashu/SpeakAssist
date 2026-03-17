package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.data.entity.TaskStep

@Dao
interface TaskStepDao {

    @Insert
    suspend fun insert(step: TaskStep): Long

    @Query("SELECT * FROM task_steps WHERE session_id = :sessionId ORDER BY step_number ASC")
    suspend fun getBySessionId(sessionId: Long): List<TaskStep>

    @Query("DELETE FROM task_steps WHERE session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)
}
