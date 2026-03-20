package com.example.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.data.entity.TaskStep
import com.example.speakassist.R

/**
 * 任务步骤列表适配器
 * 显示每个执行步骤的编号、操作类型、操作描述和AI思考
 */
class TaskStepAdapter : ListAdapter<TaskStep, TaskStepAdapter.StepViewHolder>(StepDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StepViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task_step, parent, false)
        return StepViewHolder(view)
    }

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class StepViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvStepNumber: TextView = itemView.findViewById(R.id.tvStepNumber)
        private val tvActionType: TextView = itemView.findViewById(R.id.tvActionType)
        private val tvActionDescription: TextView = itemView.findViewById(R.id.tvActionDescription)
        private val tvAiThinking: TextView = itemView.findViewById(R.id.tvAiThinking)

        fun bind(step: TaskStep) {
            tvStepNumber.text = step.stepNumber.toString()
            tvActionType.text = step.actionType
            tvActionDescription.text = step.actionDescription

            if (!step.aiThinking.isNullOrBlank()) {
                tvAiThinking.visibility = View.VISIBLE
                tvAiThinking.text = "AI: ${step.aiThinking}"
            } else {
                tvAiThinking.visibility = View.GONE
            }
        }
    }

    class StepDiffCallback : DiffUtil.ItemCallback<TaskStep>() {
        override fun areItemsTheSame(oldItem: TaskStep, newItem: TaskStep): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TaskStep, newItem: TaskStep): Boolean {
            return oldItem == newItem
        }
    }
}
