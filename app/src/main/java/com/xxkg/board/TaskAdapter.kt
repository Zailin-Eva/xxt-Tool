package com.xxkg.board

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TaskAdapter(
    private val onClick: (Task) -> Unit,
    private val onLongClick: (Task) -> Unit,
) : RecyclerView.Adapter<TaskAdapter.VH>() {

    private val items = ArrayList<Task>()
    private var mode = 0
    private var greenDays = 5
    private var orangeDays = 1

    fun submit(list: List<Task>, mode: Int, greenDays: Int = 5, orangeDays: Int = 1) {
        items.clear()
        items.addAll(list)
        this.mode = mode
        this.greenDays = greenDays
        this.orangeDays = orangeDays
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val sub: TextView = v.findViewById(R.id.tvSub)
        val right: TextView = v.findViewById(R.id.tvRight)
        val badge: TextView = v.findViewById(R.id.tvBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_task, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val now = System.currentTimeMillis()
        holder.title.text = t.title
        holder.badge.text = t.type.label

        val course = t.courseName.ifEmpty { "未知课程" }
        when (mode) {
            0 -> {
                val r = TimeFmt.remain(now, t.endTime, greenDays, orangeDays)
                holder.right.text = r.text
                holder.right.setTextColor(r.color)
                holder.sub.text = if (t.endTime > 0) {
                    "$course · 截止 " + TimeFmt.dateTime(t.endTime)
                } else {
                    "$course · 截止时间未知"
                }
            }

            1 -> {
                holder.right.text = "待批阅"
                holder.right.setTextColor(TimeFmt.C_SOON)
                val days = TimeFmt.daysAgo(now, t.stageTime)
                holder.sub.text = when {
                    days > 0 -> "$course · 已交 $days 天"
                    days == 0 -> "$course · 今天交的"
                    else -> course
                }
            }

            2 -> {
                if (t.status == TaskStatus.SUBMITTED) {
                    // 待批阅超过 x 天被移进来的：老师一直没批
                    holder.right.text = "超期未批"
                    holder.right.setTextColor(TimeFmt.C_SOON)
                    val days = TimeFmt.daysAgo(now, t.stageTime)
                    holder.sub.text = if (days >= 0) "$course · 已交 $days 天" else course
                } else {
                    val g = t.grade
                    holder.right.text = if (g.isNotEmpty()) "$g 分" else "已完成"
                    holder.right.setTextColor(TimeFmt.C_TEXT)
                    val d = TimeFmt.dateTime(if (t.stageTime > 0) t.stageTime else t.publishTime)
                    holder.sub.text = "$course · $d"
                }
            }

            else -> {
                if (t.endTime > 0) {
                    val r = TimeFmt.remain(now, t.endTime, greenDays, orangeDays)
                    holder.right.text = r.text
                    holder.right.setTextColor(r.color)
                    holder.sub.text = "$course · 截止 " + TimeFmt.dateTime(t.endTime)
                } else {
                    holder.right.text = "已过期"
                    holder.right.setTextColor(TimeFmt.C_EXPIRED)
                    holder.sub.text = "$course · " + if (t.type == TaskType.EXAM) "已过期未考" else "已过期未交"
                }
            }
        }

        holder.itemView.setOnClickListener { onClick(t) }
        holder.itemView.setOnLongClickListener {
            onLongClick(t)
            true
        }
    }
}
