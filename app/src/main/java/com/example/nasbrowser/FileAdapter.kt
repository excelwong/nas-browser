package com.example.nasbrowser

import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.nasbrowser.databinding.ItemFileBinding
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.util.formatSize
import com.example.nasbrowser.util.guessMime

/**
 * Windows-Explorer-style list row:
 *  - single tap  -> toggle selection (highlight, used for multi-select download)
 *  - double tap  -> activate: enter a folder, or play an image/video with the system player
 *
 * The gesture detector lives on [ItemFileBinding.rowMain] (the icon + name area) so the
 * checkbox and the play button keep their own click handlers. A short delay on the single tap
 * lets a following double tap cancel it, matching desktop double-click semantics.
 */
class FileAdapter(
    private val onToggleSelect: (SmbEntry) -> Unit,
    private val onActivate: (SmbEntry) -> Unit,
    private val onOpen: (SmbEntry) -> Unit
) : ListAdapter<SmbEntry, FileAdapter.VH>(DIFF) {

    private val selected = mutableSetOf<String>()

    fun setSelected(set: Set<String>) {
        selected.clear()
        selected.addAll(set)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val b: ItemFileBinding) : RecyclerView.ViewHolder(b.root) {
        private var entry: SmbEntry? = null
        private var pendingSelect: Runnable? = null

        private val gesture = GestureDetector(b.root.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                entry?.let { e2 ->
                    pendingSelect = Runnable { onToggleSelect(e2) }
                    b.root.postDelayed(pendingSelect, 250)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                pendingSelect?.let { b.root.removeCallbacks(it) }
                pendingSelect = null
                entry?.let(onActivate)
                return true
            }
        })

        init {
            b.rowMain.setOnTouchListener { _, event -> gesture.onTouchEvent(event); true }
            b.cbSelect.setOnClickListener { entry?.let(onToggleSelect) }
            b.btnOpen.setOnClickListener { entry?.let(onOpen) }
        }

        fun bind(e: SmbEntry) {
            entry = e
            b.tvName.text = e.name
            b.tvMeta.text = if (e.isDirectory) "文件夹" else formatSize(e.size)
            b.imgIcon.setImageResource(if (e.isDirectory) R.drawable.ic_folder else R.drawable.ic_file)
            b.cbSelect.isChecked = selected.contains(e.path)
            b.btnOpen.visibility = if (isMedia(e)) View.VISIBLE else View.GONE
        }

        private fun isMedia(e: SmbEntry): Boolean {
            if (e.isDirectory) return false
            val mime = guessMime(e.path)
            return mime.startsWith("image/") || mime.startsWith("video/")
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SmbEntry>() {
            override fun areItemsTheSame(a: SmbEntry, b: SmbEntry) = a.path == b.path
            override fun areContentsTheSame(a: SmbEntry, b: SmbEntry) = a == b
        }
    }
}
