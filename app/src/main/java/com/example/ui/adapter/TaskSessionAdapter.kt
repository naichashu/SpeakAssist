package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.data.entity.TaskSession
import com.example.speakassist.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史会话列表适配器
 * 显示任务会话列表，每条显示用户指令、时间、状态
 */
class TaskSessionAdapter(
    private val onItemClick: (TaskSession) -> Unit
) : ListAdapter<TaskSession, TaskSessionAdapter.SessionViewHolder>(SessionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task_session, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCommand: TextView = itemView.findViewById(R.id.tvCommand)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)

        fun bind(session: TaskSession) {
            tvCommand.text = session.userCommand
            tvTime.text = formatTime(session.createdAt)

            // 设置状态标签
            when (session.status) {
                "success" -> {
                    tvStatus.text = "成功"
                    tvStatus.setBackgroundResource(R.drawable.bg_status_success)
                }
                "fail" -> {
                    tvStatus.text = "失败"
                    tvStatus.setBackgroundResource(R.drawable.bg_status_fail)
                }
                else -> {
                    tvStatus.text = "进行中"
                    tvStatus.setBackgroundResource(R.drawable.bg_status_running)
                }
            }

            itemView.setOnClickListener { onItemClick(session) }
        }

        private fun formatTime(timestamp: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }

    class SessionDiffCallback : DiffUtil.ItemCallback<TaskSession>() {
        override fun areItemsTheSame(oldItem: TaskSession, newItem: TaskSession): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TaskSession, newItem: TaskSession): Boolean {
            return oldItem == newItem
        }
    }
}
