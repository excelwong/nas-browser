package com.example.nasbrowser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.nasbrowser.databinding.ItemTransferBinding
import com.example.nasbrowser.model.TransferItem
import com.example.nasbrowser.model.TransferState
import com.example.nasbrowser.util.formatSize

class TransferAdapter(
    private val onDelete: (TransferItem) -> Unit
) : RecyclerView.Adapter<TransferAdapter.VH>() {

    private val items = mutableListOf<TransferItem>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()

    /**
     * Progress updates arrive dozens of times per second. A blanket [notifyDataSetChanged] would
     * make RecyclerView detach and rebuild every row on each update, which delivers ACTION_CANCEL
     * to whatever child the user's finger is on — a button would then never fire a click. So when
     * the row identities are unchanged we only rebind the rows whose content changed, via a payload,
     * which updates the existing views in place and keeps touches alive.
     */
    fun submit(list: List<TransferItem>) {
        val sameRows = list.size == items.size && list.indices.all { list[it].id == items[it].id }
        if (sameRows) {
            val changed = list.indices.filter { items[it] != list[it] }
            items.clear()
            items.addAll(list)
            changed.forEach { notifyItemChanged(it, PAYLOAD_CONTENT) }
        } else {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CONTENT)) holder.bind(items[position])
        else super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemTransferBinding) : RecyclerView.ViewHolder(b.root) {
        private var current: TransferItem? = null

        init {
            // Bound once, never re-attached during progress updates.
            b.btnDelete.setOnClickListener {
                current?.let { onDelete(it) }
            }
        }

        fun bind(t: TransferItem) {
            current = t
            b.tvName.text = t.path.substringAfterLast('/')
            val pct = if (t.size > 0) ((t.bytes * 100) / t.size).toInt() else 0
            b.progressBar.progress = pct.coerceIn(0, 100)
            b.tvStatus.text = when (t.state) {
                TransferState.QUEUED -> "排队中"
                TransferState.ACTIVE -> {
                    val base = "下载中 ${formatSize(t.bytes)} / ${formatSize(t.size)}"
                    // Show the network-retry hint without dumping a Java stack into the UI.
                    val hint = t.error?.takeIf { it.startsWith("网络中断") }
                    if (hint != null) "$base · $hint" else base
                }
                TransferState.PAUSED -> "已暂停"
                TransferState.DONE -> "已下载"
                TransferState.FAILED -> "下载失败${t.error?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""}"
            }
            // 「删除」对所有未完成任务都可用（下载中 / 排队中 / 已暂停 / 失败）。
            val canDelete = t.state != TransferState.DONE
            b.btnDelete.visibility = if (canDelete) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private companion object {
        const val PAYLOAD_CONTENT = "content"
    }
}
