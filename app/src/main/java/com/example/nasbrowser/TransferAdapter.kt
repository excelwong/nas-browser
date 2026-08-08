package com.example.nasbrowser

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.nasbrowser.databinding.ItemTransferBinding
import com.example.nasbrowser.model.TransferItem
import com.example.nasbrowser.model.TransferState
import com.example.nasbrowser.util.formatSize

class TransferAdapter(
    private val onCancel: (TransferItem) -> Unit
) : RecyclerView.Adapter<TransferAdapter.VH>() {

    private val items = mutableListOf<TransferItem>()

    fun submit(list: List<TransferItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemTransferBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(t: TransferItem) {
            b.tvName.text = t.path.substringAfterLast('/')
            val pct = if (t.size > 0) ((t.bytes * 100) / t.size).toInt() else 0
            b.progressBar.progress = pct.coerceIn(0, 100)
            b.tvStatus.text = when (t.state) {
                TransferState.QUEUED -> "排队中"
                TransferState.ACTIVE -> "下载中 ${formatSize(t.bytes)} / ${formatSize(t.size)}"
                TransferState.PAUSED -> "已暂停（可继续）"
                TransferState.DONE -> "完成"
                TransferState.FAILED -> "失败：${t.error ?: ""}"
            }
            val canCancel = t.state == TransferState.ACTIVE ||
                t.state == TransferState.QUEUED ||
                t.state == TransferState.PAUSED
            b.btnCancel.visibility = if (canCancel) android.view.View.VISIBLE else android.view.View.GONE
            b.btnCancel.setOnClickListener { onCancel(t) }
        }
    }
}
