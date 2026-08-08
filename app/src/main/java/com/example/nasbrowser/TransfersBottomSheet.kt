package com.example.nasbrowser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.example.nasbrowser.databinding.BottomSheetTransfersBinding
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
        adapter = TransferAdapter(onCancel = { vm.cancelTransfer(it.id) })
        b.recyclerTransfers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        b.recyclerTransfers.adapter = adapter
        vm.transfers.observe(viewLifecycleOwner) { adapter.submit(it) }
        b.btnClose.setOnClickListener { dismiss() }
        b.btnResumeAll.setOnClickListener { vm.resumeDownloads() }
        b.btnClear.setOnClickListener { vm.clearTransfers() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
