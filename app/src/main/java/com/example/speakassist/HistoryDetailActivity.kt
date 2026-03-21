package com.example.speakassist

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.data.AppDatabase
import com.example.ui.adapter.TaskStepAdapter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史详情页
 * 显示某条会话的详细执行步骤
 */
class HistoryDetailActivity : AppCompatActivity() {

    private lateinit var tvCommand: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rvSteps: RecyclerView
    private lateinit var stepAdapter: TaskStepAdapter

    private val db by lazy { AppDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_detail)

        setupToolbar()
        initViews()

        val sessionId = intent.getLongExtra("session_id", -1)
        if (sessionId == -1L) {
            finish()
            return
        }

        loadSessionDetail(sessionId)
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history_detail_title)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        tvCommand = findViewById(R.id.tvCommand)
        tvTime = findViewById(R.id.tvTime)
        tvStatus = findViewById(R.id.tvStatus)
        rvSteps = findViewById(R.id.rvSteps)

        stepAdapter = TaskStepAdapter()
        rvSteps.apply {
            layoutManager = LinearLayoutManager(this@HistoryDetailActivity)
            adapter = stepAdapter
        }
    }

    private fun loadSessionDetail(sessionId: Long) {
        lifecycleScope.launch {
            // 加载会话信息
            val session = db.taskSessionDao().getById(sessionId) ?: run {
                finish()
                return@launch
            }

            tvCommand.text = session.userCommand
            tvTime.text = formatTime(session.createdAt)

            when (session.status) {
                "success" -> {
                    tvStatus.text = "成功"
                    tvStatus.setBackgroundResource(R.drawable.bg_status_success)
                }
                "fail" -> {
                    tvStatus.text = "失败"
                    tvStatus.setBackgroundResource(R.drawable.bg_status_fail)
                }
                "cancelled" -> {
                    tvStatus.text = "已取消"
                    tvStatus.setBackgroundResource(R.drawable.bg_status_running)
                }
                else -> {
                    tvStatus.text = "进行中"
                    tvStatus.setBackgroundResource(R.drawable.bg_status_running)
                }
            }

            // 加载步骤列表
            val steps = db.taskStepDao().getBySessionId(sessionId)
            stepAdapter.submitList(steps)
        }
    }

    private fun formatTime(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
