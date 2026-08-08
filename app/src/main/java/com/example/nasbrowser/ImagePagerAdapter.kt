package com.example.nasbrowser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.example.nasbrowser.databinding.ItemImagePageBinding
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.viewmodel.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewPager2 adapter for the image gallery. Each page downloads its own image on demand from the
 * NAS (via the shared [BrowserViewModel.fetchToCache]) and decodes it downscaled so large photos
 * don't OOM. Swiping left/right moves to the previous/next image in the same folder.
 */
class ImagePagerAdapter(
    private val items: List<SmbEntry>,
    private val share: String,
    private val vm: BrowserViewModel,
    private val scope: LifecycleCoroutineScope
) : RecyclerView.Adapter<ImagePagerAdapter.VH>() {

    inner class VH(val b: ItemImagePageBinding) : RecyclerView.ViewHolder(b.root)

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemImagePageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        loadImage(holder, position, items[position])
    }

    private fun loadImage(holder: VH, position: Int, item: SmbEntry) {
        holder.b.progress.visibility = View.VISIBLE
        holder.b.error.visibility = View.GONE
        // Detach any previously shown drawable (and stop its animation) before loading a new one.
        (holder.b.image.drawable as? AnimatedImageDrawable)?.stop()
        holder.b.image.setImageDrawable(null)
        val ctx = holder.b.image.context
        scope.launch {
            val drawable = withContext(Dispatchers.IO) {
                val file = vm.fetchToCache(share, item)
                if (file != null) loadDrawable(ctx, file) else null
            }
            withContext(Dispatchers.Main) {
                // The holder may have been recycled for a different page while we were
                // downloading; only apply the result if it still shows this position.
                if (holder.bindingAdapterPosition != position) return@withContext
                if (drawable != null) {
                    holder.b.image.setImageDrawable(drawable)
                    // Animated GIF/WebP drawables only play once started (API 28+).
                    if (drawable is AnimatedImageDrawable) drawable.start()
                } else {
                    holder.b.error.setText(R.string.gallery_load_failed)
                    holder.b.error.visibility = View.VISIBLE
                }
                holder.b.progress.visibility = View.GONE
            }
        }
    }

    companion object {
        /**
         * Load an image file as a Drawable. On API 28+ uses [ImageDecoder], which returns an
         * [AnimatedImageDrawable] for animated GIF/WebP (so motion is preserved) and applies EXIF
         * rotation automatically. The image is decoded at its NATIVE resolution for maximum
         * sharpness; only pictures larger than [MAX_DIM] px are downsampled, and then by an
         * integer power-of-two factor so edges stay crisp instead of being blurred by arbitrary
         * scaling. On older APIs it falls back to a static Bitmap decode.
         *
         * @param maxDim the largest side (px) allowed before downsampling kicks in (a cap to
         *        avoid OOM on gigapixel images, not a target size).
         */
        private const val MAX_DIM = 4096

        fun loadDrawable(ctx: Context, file: File): Drawable? {
            return if (Build.VERSION.SDK_INT >= 28) {
                try {
                    val source = ImageDecoder.createSource(file)
                    ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        if (w > 0 && h > 0) {
                            val raw = maxOf(w, h)
                            if (raw > MAX_DIM) {
                                // Integer power-of-two downsample → crisp, no blurry interpolation.
                                var sample = 1
                                while (raw / (sample * 2) >= MAX_DIM) sample *= 2
                                decoder.setTargetSize(w / sample, h / sample)
                            }
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            } else {
                val bmp = decodeSampled(file, MAX_DIM)
                bmp?.let { BitmapDrawable(ctx.resources, it) }
            }
        }

        /** Decode a file into a bitmap, downsampling only when a side exceeds [maxDim] px. */
        fun decodeSampled(file: File, maxDim: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            val raw = maxOf(w, h)
            var sample = 1
            while (raw / (sample * 2) >= maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                // Prefer quality over speed on the (rare) downscale path.
                inPreferQualityOverSpeed = true
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            return rotateForExif(bmp, file)
        }

        private fun rotateForExif(bmp: Bitmap, file: File): Bitmap {
            return try {
                val exif = ExifInterface(file.absolutePath)
                val deg = when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
                if (deg == 0) bmp else {
                    val m = Matrix().apply { postRotate(deg.toFloat()) }
                    Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
            } catch (_: Exception) {
                bmp
            }
        }
    }
}
