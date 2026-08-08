package com.example.nasbrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nasbrowser.databinding.FragmentBrowserBinding
import com.example.nasbrowser.model.ConnectionParams
import com.example.nasbrowser.model.SortMode
import com.example.nasbrowser.model.TransferState
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.util.PrefsStore
import com.example.nasbrowser.util.guessMime
import com.example.nasbrowser.viewmodel.BrowserViewModel
import com.example.nasbrowser.viewmodel.OpenResult
import java.net.URLEncoder

class BrowserFragment : Fragment() {
    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: FileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FileAdapter(
            onToggleSelect = { vm.toggleSelection(it) },
            onActivate = { activateEntry(it) },
            onOpen = { openMedia(it) }
        )
        // Windows-Explorer-style single-column list (full names, one item per row).
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        // Both the top arrow and the bottom button navigate up (or back to connect at the root).
        binding.btnUp.setOnClickListener { navigateUp() }
        binding.btnUpBottom.setOnClickListener { navigateUp() }
        binding.btnRefresh.setOnClickListener { vm.reload() }

        vm.items.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val empty = list.isEmpty()
            binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
            binding.tvEmpty.text = if (vm.currentShare.value == null) {
                getString(R.string.no_shares)
            } else {
                getString(R.string.empty_folder)
            }
        }

        // Full path in the top bar, Windows style: \\host\share\path  (or \\host at the root).
        val updatePath = {
            val host = vm.connection.value?.host ?: ""
            val share = vm.currentShare.value
            val path = vm.currentPath.value ?: ""
            binding.tvPath.text = if (share == null) "\\\\$host"
            else "\\\\$host\\$share" + if (path.isEmpty()) "" else "\\${path.replace('/', '\\')}"
            val atRoot = share == null
            binding.btnUp.visibility = if (atRoot) View.GONE else View.VISIBLE
            binding.btnUpBottom.visibility = if (atRoot) View.GONE else View.VISIBLE
        }
        vm.connection.observe(viewLifecycleOwner) { updatePath() }
        vm.currentShare.observe(viewLifecycleOwner) { updatePath() }
        vm.currentPath.observe(viewLifecycleOwner) { updatePath() }

        vm.isBusy.observe(viewLifecycleOwner) { b ->
            binding.progress.visibility =
                if (b || vm.openProgress.value) View.VISIBLE else View.GONE
        }
        vm.visibleSelection.observe(viewLifecycleOwner) { sel ->
            // Only the current page's selection is highlighted / counted / downloadable.
            adapter.setSelected(sel.keys)
            binding.tvSelected.text = getString(R.string.selected_count, sel.size)
            binding.btnDownload.isEnabled = sel.isNotEmpty()
        }
        vm.error.observe(viewLifecycleOwner) { err ->
            if (!err.isNullOrEmpty()) {
                Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                vm.clearError()
            }
        }
        vm.manualShareNeeded.observe(viewLifecycleOwner) { needed ->
            if (needed) {
                vm.clearManualShare()
                showManualShareDialog()
            }
        }
        vm.transfers.observe(viewLifecycleOwner) { tr ->
            if (tr.isNotEmpty()) {
                binding.btnTransfers.visibility = View.VISIBLE
                val done = tr.count { it.state == TransferState.DONE }
                binding.btnTransfers.text = getString(R.string.open_transfers, done, tr.size)
            } else {
                binding.btnTransfers.visibility = View.GONE
            }
        }

        binding.btnDownload.setOnClickListener {
            ensureNotifyPermission()
            vm.downloadSelected()
            TransfersBottomSheet().show(parentFragmentManager, "transfers")
        }
        binding.btnTransfers.setOnClickListener {
            TransfersBottomSheet().show(parentFragmentManager, "transfers")
        }

        binding.btnSettings.setOnClickListener { showSettingsMenu() }

        // When a non-video media file has been prepared locally, hand it to the system default
        // viewer. (Videos are streamed directly via VLC over SMB and never reach this observer.)
        vm.openResult.observe(viewLifecycleOwner) { res ->
            if (res != null) {
                openWithSystem(res)
                vm.clearOpenResult()
            }
        }

        // Android's system "back" gesture: navigate up the directory tree, then return to the
        // connect screen once we are back at the server's share list (root).
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    navigateUp()
                }
            }
        )
    }

    /** Top-bar "设置" menu: edit the saved connection, or wipe it and start fresh. */
    private fun showSettingsMenu() {
        val popup = PopupMenu(requireContext(), binding.btnSettings)
        popup.menuInflater.inflate(R.menu.browser_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_sort -> {
                    showSortDialog()
                    true
                }
                R.id.menu_edit -> {
                    // Return to the connection screen (pre-filled with saved values).
                    vm.disconnectToConnect()
                    parentFragmentManager.popBackStack()
                    true
                }
                R.id.menu_clear -> {
                    PrefsStore.clear(requireContext())
                    vm.disconnectToConnect()
                    parentFragmentManager.popBackStack()
                    Toast.makeText(requireContext(), R.string.config_cleared, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Show a single-choice dialog to pick the list sort order, then apply it live. */
    private fun showSortDialog() {
        val modes = SortMode.values()
        val cur = vm.sortMode.value
        val checked = modes.indexOf(cur).coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(modes.map { it.label }.toTypedArray(), checked) { d, which ->
                vm.setSortMode(modes[which])
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Navigate up one level; at the root share list, return to the connect screen. */
    private fun navigateUp() {
        val share = vm.currentShare.value
        if (share != null) {
            vm.browseUp()
        } else {
            vm.disconnectToConnect()
            parentFragmentManager.popBackStack()
        }
    }

    /** Single click selects (highlights); double click activates (enter folder / play media). */
    private fun activateEntry(entry: SmbEntry) {
        if (entry.isDirectory) {
            val share = vm.currentShare.value
            if (share == null) vm.enterShare(entry.name) else vm.enterFolder(entry.path)
        } else if (isMedia(entry)) {
            openMedia(entry)
        }
    }

    private fun isMedia(entry: SmbEntry): Boolean {
        if (entry.isDirectory) return false
        val mime = guessMime(entry.path)
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    /**
     * Double-click handler for a media file:
     *  - video  -> stream directly via VLC over SMB (no local download).
     *  - image  -> open the swipeable in-folder gallery.
     *  - other  -> download, then hand to the system default viewer.
     */
    private fun openMedia(entry: SmbEntry) {
        val mime = guessMime(entry.path)
        when {
            mime.startsWith("video/") -> {
                val conn = vm.connection.value
                val share = vm.currentShare.value
                if (conn == null || share == null) {
                    // No live SMB session (shouldn't happen while browsing) — fall back to download.
                    Toast.makeText(requireContext(), R.string.opening_file, Toast.LENGTH_SHORT).show()
                    vm.openEntry(entry)
                } else {
                    openVideoWithVlc(buildSmbUrl(conn, share, entry.path), mime)
                }
            }
            mime.startsWith("image/") -> openImageGallery(entry)
            else -> {
                Toast.makeText(requireContext(), R.string.opening_file, Toast.LENGTH_SHORT).show()
                vm.openEntry(entry)
            }
        }
    }

    /** Open the swipeable image gallery for the current folder, starting at [entry]. */
    private fun openImageGallery(entry: SmbEntry) {
        val share = vm.currentShare.value ?: return
        val images = vm.items.value.filter { isImage(it) }
        if (images.isEmpty()) return
        val start = images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        val frag = ImageViewerFragment.newInstance(share, ArrayList(images), start)
        parentFragmentManager.beginTransaction()
            .add(R.id.container, frag, "imageViewer")
            .addToBackStack("imageViewer")
            .commit()
    }

    private fun isImage(entry: SmbEntry): Boolean {
        if (entry.isDirectory) return false
        return guessMime(entry.path).startsWith("image/")
    }

    /**
     * Open a video directly via VLC over SMB. VLC fetches and streams the file itself from the
     * `smb://` URI, so no local download is required. The URI is a remote one (not a
     * content:// FileProvider URI), so we must NOT add FLAG_GRANT_READ_URI_PERMISSION.
     */
    private fun openVideoWithVlc(smbUrl: String, mime: String) {
        val vlcPkg = "org.videolan.vlc"
        val vlcInstalled = try {
            requireContext().packageManager.getPackageInfo(vlcPkg, 0)
            true
        } catch (_: Exception) {
            false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(smbUrl), mime)
        }
        if (vlcInstalled) {
            intent.setPackage(vlcPkg)
        } else {
            Toast.makeText(requireContext(), R.string.vlc_not_installed, Toast.LENGTH_LONG).show()
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.no_player, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Build a VLC-compatible SMB URI: `smb://[[domain;]user:pass@]host/share/path`.
     * Credentials are URL-encoded; each path segment is encoded individually while the `/`
     * separators are preserved (VLC's SMB client expects forward slashes in the path).
     */
    private fun buildSmbUrl(p: ConnectionParams, share: String, path: String): String {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
        val userPart = if (p.domain.isNotBlank()) "${enc(p.domain)};${enc(p.username)}" else enc(p.username)
        val cred = "${userPart}:${enc(p.password)}@"
        val shareEnc = enc(share)
        val pathEnc = path.split('/').filter { it.isNotBlank() }.joinToString("/") { enc(it) }
        return "smb://$cred${p.host}/$shareEnc/$pathEnc"
    }

    private fun openWithSystem(res: OpenResult) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                requireContext().packageName + ".fileprovider",
                res.file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, res.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.no_player, Toast.LENGTH_LONG).show()
        }
    }

    /** Fallback when automatic share enumeration fails: let the user type a share name. */
    private fun showManualShareDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.manual_share_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manual_share_title)
            .setView(input)
            .setPositiveButton(R.string.manual_share_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) vm.enterShare(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // When returning from another app after a while, the SMB session is often dropped by the NAS
    // or the OS. Reload the current view; the ViewModel auto-reconnects a stale session once.
    private var lastPauseMs = 0L

    override fun onPause() {
        super.onPause()
        lastPauseMs = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (vm.connection.value != null && System.currentTimeMillis() - lastPauseMs > 2000) {
            vm.reload()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Best-effort request for the notification permission (Android 13+). The foreground download
     * service posts a progress notification; without this permission the notification is hidden on
     * Android 13+ (downloads still run). We request without blocking the download start.
     */
    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), REQ_NOTIFY)
            }
        }
    }

    companion object {
        private const val REQ_NOTIFY = 1001
    }
}
