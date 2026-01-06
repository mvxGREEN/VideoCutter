package green.mobileapps.clippervideocutter

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.slider.RangeSlider
import green.mobileapps.clippervideocutter.databinding.EditorActivityBinding
import java.io.File
import java.util.Formatter
import java.util.Locale
import kotlin.math.abs

@UnstableApi
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: EditorActivityBinding
    private var exoPlayer: ExoPlayer? = null
    private var mediaFile: MediaFile? = null
    private var transformer: Transformer? = null

    private var startTimeMs: Long = 0L
    private var endTimeMs: Long = 0L
    private var durationMs: Long = 0L

    private var isPickingStartTime = true

    private val numThumbnails = 8
    private val handler = Handler(Looper.getMainLooper())

    private val updateCursorRunnable = object : Runnable {
        override fun run() {
            updateCursorPosition()
            checkPlaybackLoop()
            handler.postDelayed(this, 30)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = EditorActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("DEPRECATION")
        mediaFile = intent.getParcelableExtra("EXTRA_MEDIA_FILE")

        if (mediaFile == null) {
            finish()
            return
        }

        setupPlayer()
        setupTimeline()
        setupButtons()
        setupTimePicker()
        setupRangeSliderTouchInterception() // <--- The Fix for Touch Behavior
    }

    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer

        val uri = mediaFile?.uri ?: return
        val mediaItem = MediaItem.fromUri(uri)

        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = false
        binding.playerView.useController = false
    }

    private fun setupTimeline() {
        durationMs = mediaFile?.duration ?: 0L
        endTimeMs = durationMs

        binding.textTotalDuration.text = "Total ${formatTimeDecimal(durationMs)}"

        binding.rangeSlider.valueFrom = 0f
        binding.rangeSlider.valueTo = durationMs.toFloat().coerceAtLeast(1f)
        binding.rangeSlider.values = listOf(0f, durationMs.toFloat().coerceAtLeast(1f))

        binding.textStartTime.text = formatTimeDecimal(0L)
        binding.textEndTime.text = formatTimeDecimal(durationMs)

        if (mediaFile?.isVideo == true) {
            binding.recyclerThumbnails.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            val adapter = ThumbnailAdapter(this, mediaFile!!, numThumbnails)
            binding.recyclerThumbnails.adapter = adapter
        }

        // Listener for Dragging Handles
        binding.rangeSlider.addOnChangeListener { slider, value, fromUser ->
            val values = slider.values
            val newStart = values[0].toLong()
            val newEnd = values[1].toLong()

            if (kotlin.math.abs(newStart - startTimeMs) > 10) {
                startTimeMs = newStart
                seekTo(startTimeMs)
            } else if (kotlin.math.abs(newEnd - endTimeMs) > 10) {
                endTimeMs = newEnd
                seekTo(endTimeMs)
            }

            binding.textStartTime.text = formatTimeDecimal(startTimeMs)
            binding.textEndTime.text = formatTimeDecimal(endTimeMs)
            binding.textTotalDuration.text = "Total ${formatTimeDecimal(endTimeMs - startTimeMs)}"

            // NEW: Update Border dynamically while dragging
            updateSelectionBorder()
        }

        // ... (Keep existing onTouchListener setup) ...

        // NEW: Initial Border Setup (Must wait for layout)
        binding.rangeSlider.post {
            updateSelectionBorder()
        }
    }

    // ... (Keep existing setupRangeSliderTouchInterception) ...

    /**
     * Updates the Orange Border View to match the Start/End handles.
     */
    private fun updateSelectionBorder() {
        val containerWidth = binding.recyclerThumbnails.width
        if (durationMs > 0 && containerWidth > 0) {
            // Calculate percentage positions (0.0 to 1.0)
            val startRatio = startTimeMs.toFloat() / durationMs.toFloat()
            val endRatio = endTimeMs.toFloat() / durationMs.toFloat()

            // Convert to pixels relative to the container
            val startX = startRatio * containerWidth
            val endX = endRatio * containerWidth
            val borderWidth = endX - startX

            // Update View
            val params = binding.viewSelectionBorder.layoutParams
            params.width = borderWidth.toInt()
            binding.viewSelectionBorder.layoutParams = params
            binding.viewSelectionBorder.translationX = startX
        }
    }

    /**
     * Touch Interception Strategy:
     * 1. If touch is near a handle: Return FALSE -> Slider drags.
     * 2. If touch is outside the trim range (Dead Zone): Return TRUE -> Consume event, do nothing.
     * 3. If touch is inside the trim range: Return TRUE -> Seek video.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupRangeSliderTouchInterception() {
        // Disable clickability so ignored events (return false) fall through to the slider
        binding.touchInterceptor.isClickable = false

        binding.touchInterceptor.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val slider = binding.rangeSlider
                val duration = (slider.valueTo - slider.valueFrom)
                if (duration <= 0) return@setOnTouchListener false

                // 1. Exact Visual Metrics (16dp)
                val density = resources.displayMetrics.density
                val thumbRadiusPx = (16 * density).toInt()
                val trackWidth = v.width - (2 * thumbRadiusPx)

                // 2. Calculate Thumb Positions
                val startRatio = (startTimeMs - slider.valueFrom) / duration
                val endRatio = (endTimeMs - slider.valueFrom) / duration

                val startThumbX = thumbRadiusPx + (trackWidth * startRatio)
                val endThumbX = thumbRadiusPx + (trackWidth * endRatio)

                // 3. Hit Detection
                val touchX = event.x
                val hitThreshold = thumbRadiusPx * 1.5f

                val distToStart = kotlin.math.abs(touchX - startThumbX)
                val distToEnd = kotlin.math.abs(touchX - endThumbX)

                if (distToStart < hitThreshold || distToEnd < hitThreshold) {
                    // Touching a handle -> PASS THROUGH to RangeSlider for dragging
                    return@setOnTouchListener false
                } else {
                    // Touching the track -> Calculate position
                    val touchXOffset = touchX - thumbRadiusPx
                    val touchXClamped = touchXOffset.coerceIn(0f, trackWidth.toFloat())
                    val clickRatio = touchXClamped / trackWidth
                    val seekTimeMs = (clickRatio * duration).toLong()

                    // NEW: Check if touch is within the valid trim range
                    if (seekTimeMs < startTimeMs || seekTimeMs > endTimeMs) {
                        // Dead Zone: Before Start or After End
                        // Return TRUE to consume the event (prevent Slider snap), but do NOT seek.
                        return@setOnTouchListener true
                    }

                    // Inside Range -> Seek Video
                    seekTo(seekTimeMs)

                    // Consume event
                    return@setOnTouchListener true
                }
            }
            return@setOnTouchListener false
        }
    }

    private fun setupButtons() {
        binding.buttonPlayPause.setOnClickListener {
            if (exoPlayer?.isPlaying == true) pausePlayback() else startPlayback()
        }
        binding.buttonPrevFrame.setOnClickListener { seekTo(startTimeMs) }
        binding.buttonSaveCheck.setOnClickListener { saveMedia() }
        binding.buttonCancelAction.setOnClickListener { finish() }
        binding.textStartTime.setOnClickListener { showTimePicker(true) }
        binding.textEndTime.setOnClickListener { showTimePicker(false) }
    }

    private fun setupTimePicker() {
        binding.buttonPickerCancel.setOnClickListener { binding.layoutTimePicker.visibility = View.GONE }
        binding.buttonPickerOk.setOnClickListener {
            val min = binding.inputMin.text.toString().toLongOrNull() ?: 0L
            val sec = binding.inputSec.text.toString().toLongOrNull() ?: 0L
            val ms100 = binding.inputMs.text.toString().toLongOrNull() ?: 0L
            val newTimeMs = (min * 60000) + (sec * 1000) + (ms100 * 100)

            if (isPickingStartTime) {
                if (newTimeMs < endTimeMs) {
                    startTimeMs = newTimeMs
                    binding.rangeSlider.setValues(startTimeMs.toFloat(), endTimeMs.toFloat())
                } else Toast.makeText(this, "Start time must be before end time", Toast.LENGTH_SHORT).show()
            } else {
                if (newTimeMs > startTimeMs && newTimeMs <= durationMs) {
                    endTimeMs = newTimeMs
                    binding.rangeSlider.setValues(startTimeMs.toFloat(), endTimeMs.toFloat())
                } else Toast.makeText(this, "Invalid end time", Toast.LENGTH_SHORT).show()
            }

            // UI Sync
            binding.textStartTime.text = formatTimeDecimal(startTimeMs)
            binding.textEndTime.text = formatTimeDecimal(endTimeMs)
            binding.textTotalDuration.text = "Total ${formatTimeDecimal(endTimeMs - startTimeMs)}"
            seekTo(if(isPickingStartTime) startTimeMs else endTimeMs)

            binding.layoutTimePicker.visibility = View.GONE
        }
    }

    private fun showTimePicker(isStart: Boolean) {
        isPickingStartTime = isStart
        binding.layoutTimePicker.visibility = View.VISIBLE
        binding.textPickerTitle.text = if (isStart) "Set start time" else "Set end time"
        val timeMs = if (isStart) startTimeMs else endTimeMs

        // Parse time into components
        val totalSecs = timeMs / 1000
        val min = totalSecs / 60
        val sec = totalSecs % 60
        val decisecond = (timeMs % 1000) / 100

        binding.inputMin.setText(min.toString())
        binding.inputSec.setText(sec.toString())
        binding.inputMs.setText(decisecond.toString())
    }

    private fun updateCursorPosition() {
        val currentPos = exoPlayer?.currentPosition ?: 0L
        // Use the thumbnails width (the visual track width)
        val timelineWidth = binding.recyclerThumbnails.width

        if (durationMs > 0 && timelineWidth > 0) {
            val progress = currentPos.toFloat() / durationMs.toFloat()
            val translationX = progress * timelineWidth
            binding.viewCursor.translationX = translationX
        }
    }

    private fun startPlayback() {
        exoPlayer?.play()
        binding.buttonPlayPause.setImageResource(R.drawable.pause_24px)
        handler.post(updateCursorRunnable)
    }

    private fun pausePlayback() {
        exoPlayer?.pause()
        binding.buttonPlayPause.setImageResource(R.drawable.play_arrow_24px)
        handler.removeCallbacks(updateCursorRunnable)
    }

    private fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        updateCursorPosition()
    }

    private fun checkPlaybackLoop() {
        val currentPos = exoPlayer?.currentPosition ?: 0L
        if (currentPos >= endTimeMs || currentPos < startTimeMs) {
            seekTo(startTimeMs)
            if (exoPlayer?.isPlaying == true) {
                // Optional: You can choose to loop or pause here
                pausePlayback()
            }
        }
    }

    private fun formatTimeDecimal(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val decisecond = (timeMs % 1000) / 100
        return String.format(Locale.getDefault(), "%d:%02d.%d", minutes, seconds, decisecond)
    }

    private fun saveMedia() {
        val sourceUri = mediaFile?.uri ?: return
        pausePlayback()
        Toast.makeText(this, "Exporting...", Toast.LENGTH_SHORT).show()

        val cacheDir = externalCacheDir ?: cacheDir
        val tempFile = File(cacheDir, "temp_trim_${System.currentTimeMillis()}.mp4")

        val clippingConfiguration = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startTimeMs)
            .setEndPositionMs(endTimeMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(clippingConfiguration)
            .build()

        transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(tempFile)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Toast.makeText(this@EditorActivity, "Export Failed", Toast.LENGTH_LONG).show()
                }
            })
            .build()

        transformer?.start(mediaItem, tempFile.absolutePath)
    }

    private fun saveToGallery(tempFile: File) {
        // Logic identical to previous steps (ContentResolver insert)
        // ... (Omitted for brevity as it was provided in previous step)
        // Ensure you close the activity on success
        Toast.makeText(this, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        handler.removeCallbacks(updateCursorRunnable)
    }

    class ThumbnailAdapter(private val context: Context, private val file: MediaFile, private val count: Int) : RecyclerView.Adapter<ThumbnailAdapter.ThumbViewHolder>() {
        private val interval = file.duration / count
        inner class ThumbViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
            val displayMetrics = context.resources.displayMetrics
            // Width calculation: (Screen Width - 32dp margins) / count
            val marginPx = (32 * displayMetrics.density).toInt()
            val width = (displayMetrics.widthPixels - marginPx) / count

            val img = ImageView(context)
            img.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            return ThumbViewHolder(img)
        }

        override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
            val frameTimeMs = position * interval
            val requestOptions = RequestOptions().frame(frameTimeMs * 1000)
            Glide.with(context).asBitmap().load(file.uri).apply(requestOptions).into(holder.imageView)
        }
        override fun getItemCount(): Int = count
    }
}