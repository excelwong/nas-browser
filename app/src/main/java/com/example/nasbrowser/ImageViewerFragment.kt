package com.example.nasbrowser

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.example.nasbrowser.databinding.FragmentImageViewerBinding
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.viewmodel.BrowserViewModel

/**
 * Full-screen image viewer shown on top of the browser. It receives the list of image entries in
 * the current folder plus the start index, and lets the user swipe left/right to browse the
 * previous/next image in the same directory. Images are downloaded on demand (and cached) via the
 * shared [BrowserViewModel] so the already-open SMB connection is reused.
 */
class ImageViewerFragment : Fragment() {
    private var _binding: FragmentImageViewerBinding? = null
    private val binding get() = _binding!!
    private val vm: BrowserViewModel by activityViewModels()

    private var share: String = ""
    private var images: List<SmbEntry> = emptyList()
    private var start: Int = 0

    companion object {
        fun newInstance(share: String, images: ArrayList<SmbEntry>, start: Int): ImageViewerFragment {
            val f = ImageViewerFragment()
            f.arguments = Bundle().apply {
                putString("share", share)
                putParcelableArrayList("images", images)
                putInt("start", start)
            }
            return f
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentImageViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        share = arguments?.getString("share") ?: ""
        @Suppress("DEPRECATION")
        images = requireArguments().getParcelableArrayList<SmbEntry>("images")?.toList()
            ?: emptyList()
        start = arguments?.getInt("start", 0) ?: 0

        val adapter = ImagePagerAdapter(images, share, vm, viewLifecycleOwner.lifecycleScope)
        binding.pager.adapter = adapter
        binding.pager.offscreenPageLimit = 1
        binding.pager.setCurrentItem(start.coerceIn(0, maxOf(0, images.lastIndex)), false)
        updateTitle(start)

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateTitle(position)
        })

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Consume the system back gesture while the gallery is open so it closes the viewer
        // instead of navigating the browser underneath.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    parentFragmentManager.popBackStack()
                }
            }
        )
    }

    private fun updateTitle(pos: Int) {
        val name = images.getOrNull(pos)?.name ?: ""
        binding.toolbar.title = "${pos + 1} / ${images.size}   $name"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
