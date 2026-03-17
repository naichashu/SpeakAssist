package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.dao.TaskSessionDao
import com.example.data.dao.TaskStepDao
import com.example.data.entity.TaskSession
import com.example.data.entity.TaskStep

@Database(
    entities = [TaskSession::class, TaskStep::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun taskSessionDao(): TaskSessionDao
    abstract fun taskStepDao(): TaskStepDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "speakassist.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
