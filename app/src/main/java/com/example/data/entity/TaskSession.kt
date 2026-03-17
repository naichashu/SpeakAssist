package com.example.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_sessions")
data class TaskSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "user_command")
    val userCommand: String,

    // "running" | "success" | "fail"
    val status: String = "running",

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
