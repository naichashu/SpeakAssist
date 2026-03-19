package com.example.ui.adapter

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.speakassist.R
import com.example.ui.adapter.ChatMessageAdapter.ChatMessageItem

/**
 * 聊天消息适配器
 * 用于在RecyclerView中显示用户消息和系统消息
 *
 * 消息类型：
 * - 用户消息：右对齐，蓝色背景(#2196F3)
 * - 系统消息：左对齐，灰色背景(#F0F0F0)
 */
class ChatMessageAdapter : ListAdapter<ChatMessageItem, ChatMessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    /**
     * 聊天消息数据类
     * @param content 消息文本内容
     * @param isUser 是否为用户发送的消息，true表示用户消息，false表示系统消息
     * @param timestamp 消息时间戳（可选）
     */
    data class ChatMessageItem(
        val content: String,
        val isUser: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /**
     * 消息ViewHolder
     * 负责绑定消息数据到视图，并根据消息类型设置不同的样式
     */
    class MessageViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        // 消息内容文本框
        private val tvMessageContent: android.widget.TextView = itemView.findViewById(R.id.tvMessageContent)

        /**
         * 绑定消息数据到视图
         * 根据isUser参数决定消息的显示样式（位置、背景色、文字颜色）
         *
         * @param item 聊天消息数据项
         */
        fun bind(item: ChatMessageItem) {
            tvMessageContent.text = item.content

            // 获取父容器的LayoutParams来调整对齐方式
            val layoutParams = tvMessageContent.layoutParams as? android.widget.FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )

            if (item.isUser) {
                // 用户消息：右对齐，蓝色背景，白色文字
                layoutParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                tvMessageContent.setBackgroundResource(R.drawable.bg_user_message)
                tvMessageContent.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.white))
            } else {
                // 系统消息：左对齐，灰色背景，深色文字
                layoutParams.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                tvMessageContent.setBackgroundResource(R.drawable.bg_system_message)
                tvMessageContent.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.black))
            }

            tvMessageContent.layoutParams = layoutParams
        }
    }

    /**
     * DiffUtil回调类
     * 用于计算列表差异，提高RecyclerView更新效率
     */
    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessageItem>() {
        override fun areItemsTheSame(oldItem: ChatMessageItem, newItem: ChatMessageItem): Boolean {
            // 使用时间戳判断是否为同一项
            return oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(oldItem: ChatMessageItem, newItem: ChatMessageItem): Boolean {
            // 判断内容是否相同
            return oldItem == newItem
        }
    }
}
