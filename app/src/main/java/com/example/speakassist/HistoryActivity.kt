package com.example.speakassist

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.data.AppDatabase
import com.example.ui.adapter.TaskSessionAdapter
import kotlinx.coroutines.launch

/**
 * 历史记录列表页
 * 从Room查询所有task_sessions，按时间倒序显示
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var rvSessions: RecyclerView
    private lateinit var emptyState: View
    private lateinit var sessionAdapter: TaskSessionAdapter

    private val db by lazy { AppDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        setupToolbar()
        setupRecyclerView()
        loadSessions()
    }

    private fun setupToolbar() {
        setupBackToolbar(findViewById(R.id.toolbar), getString(R.string.nav_history))
    }

    private fun setupRecyclerView() {
        rvSessions = findViewById(R.id.rvSessions)
        emptyState = findViewById(R.id.emptyState)

        sessionAdapter = TaskSessionAdapter { session ->
            // 点击跳转到详情页
            val intent = Intent(this, HistoryDetailActivity::class.java)
            intent.putExtra("session_id", session.id)
            startActivity(intent)
        }

        rvSessions.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = sessionAdapter
        }
    }

    private fun loadSessions() {
        lifecycleScope.launch {
            val sessions = db.taskSessionDao().getAll()
            sessionAdapter.submitList(sessions)
            updateEmptyState(sessions.isEmpty())
        }
    }

    private fun updateEmptyState(empty: Boolean) {
        emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        rvSessions.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // 返回时刷新列表
        lifecycleScope.launch {
            val sessions = db.taskSessionDao().getAll()
            sessionAdapter.submitList(sessions)
            updateEmptyState(sessions.isEmpty())
        }
    }
}
