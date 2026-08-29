package com.example.nasbrowser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.example.nasbrowser.databinding.BottomSheetTransfersBinding
import com.example.nasbrowser.model.TransferState
import com.example.nasbrowser.viewmodel.BrowserViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class TransfersBottomSheet : BottomSheetDialogFragment() {
    private var _b: BottomSheetTransfersBinding? = null
    private val b get() = _b!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: TransferAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = BottomSheetTransfersBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = TransferAdapter(
            onDelete = { vm.deleteTransfer(it.id) }
        )
        b.recyclerTransfers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        b.recyclerTransfers.adapter = adapter
        vm.transfers.observe(viewLifecycleOwner) { list ->
            // Show all items including DONE; "清除已完成" button removes DONE items from the list.
            adapter.submit(list)
            // Hide "清除已完成" when there are no DONE items.
            val hasDone = list.any { it.state == TransferState.DONE }
            b.btnClearDone.visibility = if (hasDone) View.VISIBLE else View.GONE
            // The global toggle button: "暂停全部" while anything is downloading/queued,
            // "续传/重试" once everything is paused or has failed. Hidden when nothing is pending.
            val hasPending = list.any { it.state != TransferState.DONE }
            val anyActive = list.any { it.state == TransferState.ACTIVE || it.state == TransferState.QUEUED }
            b.btnToggleAll.visibility = if (hasPending) View.VISIBLE else View.GONE
            b.btnToggleAll.setText(if (anyActive) R.string.pause_all else R.string.resume_all)
        }
        b.btnClose.setOnClickListener { dismiss() }
        b.btnToggleAll.setOnClickListener { vm.toggleAllDownloads() }
        b.btnClear.setOnClickListener { vm.clearTransfers() }
        b.btnClearDone.setOnClickListener { vm.clearDoneTransfers() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
