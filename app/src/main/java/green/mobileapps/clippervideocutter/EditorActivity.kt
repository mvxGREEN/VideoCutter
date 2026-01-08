package green.mobileapps.clippervideocutter

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.slider.RangeSlider
import green.mobileapps.clippervideocutter.databinding.EditorActivityBinding
import java.io.File
import java.io.FileOutputStream
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
     * 1. Handles: Pass through to RangeSlider (return false).
     * 2. Track: Intercept (return true).
     * - Down/Move: Seek video, Show Tooltip, Update Tooltip Position.
     * - Up/Cancel: Hide Tooltip.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupRangeSliderTouchInterception() {
        binding.touchInterceptor.isClickable = false

        binding.touchInterceptor.setOnTouchListener { v, event ->
            val slider = binding.rangeSlider
            val duration = (slider.valueTo - slider.valueFrom)
            if (duration <= 0) return@setOnTouchListener false

            // Metrics
            val density = resources.displayMetrics.density
            val thumbRadiusPx = (16 * density).toInt()
            val trackWidth = v.width - (2 * thumbRadiusPx)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Check if touching a handle
                    val startRatio = (startTimeMs - slider.valueFrom) / duration
                    val endRatio = (endTimeMs - slider.valueFrom) / duration
                    val startThumbX = thumbRadiusPx + (trackWidth * startRatio)
                    val endThumbX = thumbRadiusPx + (trackWidth * endRatio)

                    val touchX = event.x
                    val hitThreshold = thumbRadiusPx * 1.5f

                    if (abs(touchX - startThumbX) < hitThreshold || abs(touchX - endThumbX) < hitThreshold) {
                        return@setOnTouchListener false // Pass to Slider
                    } else {
                        // Start Scrubbing
                        updateScrubbing(touchX, thumbRadiusPx, trackWidth, duration)
                        return@setOnTouchListener true // Intercept
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // Continue Scrubbing
                    // Note: We only get here if we returned true in ACTION_DOWN
                    updateScrubbing(event.x, thumbRadiusPx, trackWidth, duration)
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Stop Scrubbing
                    binding.textCursorLabel.visibility = View.GONE
                    return@setOnTouchListener true
                }
            }
            return@setOnTouchListener false
        }
    }

    /**
     * Helper to handle the seeking logic and tooltip updates during a scrub.
     */
    private fun updateScrubbing(touchX: Float, thumbOffset: Int, trackWidth: Int, duration: Float) {
        // 1. Calculate Time
        val touchXOffset = touchX - thumbOffset
        val touchXClamped = touchXOffset.coerceIn(0f, trackWidth.toFloat())
        val clickRatio = touchXClamped / trackWidth
        val seekTimeMs = (clickRatio * duration).toLong()

        // 2. Check Dead Zone (Optional: prevent seeking outside bounds)
        if (seekTimeMs < startTimeMs || seekTimeMs > endTimeMs) {
            // If outside bounds, maybe just hide cursor label or show boundary time?
            // For now, we just don't seek, but we ensure label is hidden/updates cleanly
            // binding.textCursorLabel.visibility = View.GONE
            // return
        }

        // 3. Seek
        seekTo(seekTimeMs)

        // 4. Update Tooltip Label
        binding.textCursorLabel.visibility = View.VISIBLE
        binding.textCursorLabel.text = formatTimeDecimal(seekTimeMs)

        // Center label on the cursor
        // We use the same 'touchXClamped' logic relative to the thumbnail container for translation
        // Note: The thumbnail container starts at x=0 (visually) inside the frame, which matches our 'touchXClamped' calculation frame roughly.
        // Actually, simpler is to align with view_cursor's translation:

        val cursorX = binding.viewCursor.translationX
        val labelWidth = binding.textCursorLabel.width.toFloat()
        // Center the label: CursorPos - HalfLabelWidth
        // Ensure we measure it if width is 0 (first frame)
        if (labelWidth == 0f) {
            binding.textCursorLabel.measure(0, 0)
        }

        // We clamp translation so tooltip doesn't fly off screen edges
        val maxTrans = binding.layoutTimelineContainer.width - binding.textCursorLabel.measuredWidth
        val safeTrans = (cursorX - (binding.textCursorLabel.measuredWidth / 2)).coerceIn(0f, maxTrans.toFloat())

        binding.textCursorLabel.translationX = safeTrans
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
        // 1. Calculate Max Time Components
        val maxTotalSeconds = durationMs / 1000
        val maxMin = (maxTotalSeconds / 60).toInt()
        val maxSecRemain = (maxTotalSeconds % 60).toInt()
        val maxMsRemain = ((durationMs % 1000) / 100).toInt()

        // 2. Initialize Pickers
        binding.pickerMin.minValue = 0
        binding.pickerMin.maxValue = maxMin
        binding.pickerMin.wrapSelectorWheel = false // <--- Disable wrapping
        binding.pickerMin.setFormatter { i -> String.format("%02d", i) }

        binding.pickerSec.minValue = 0
        binding.pickerSec.maxValue = 59
        binding.pickerSec.wrapSelectorWheel = false // <--- Disable wrapping
        binding.pickerSec.setFormatter { i -> String.format("%02d", i) }

        binding.pickerMs.minValue = 0
        binding.pickerMs.maxValue = 9
        binding.pickerMs.wrapSelectorWheel = false // <--- Disable wrapping

        // Helper to update limits based on currently selected values
        fun updateLimits() {
            val currentMin = binding.pickerMin.value

            // Logic: If we are at the max minute, cap the seconds.
            if (currentMin == maxMin) {
                binding.pickerSec.maxValue = maxSecRemain
                // If the old second value is now too high, clamp it
                if (binding.pickerSec.value > maxSecRemain) {
                    binding.pickerSec.value = maxSecRemain
                }

                // If we are at max min AND max sec, cap the ms
                if (binding.pickerSec.value == maxSecRemain) {
                    binding.pickerMs.maxValue = maxMsRemain
                    if (binding.pickerMs.value > maxMsRemain) {
                        binding.pickerMs.value = maxMsRemain
                    }
                } else {
                    binding.pickerMs.maxValue = 9
                }
            } else {
                // Not at max minute, so seconds allow 0-59 and ms 0-9
                binding.pickerSec.maxValue = 59
                binding.pickerMs.maxValue = 9
            }
        }

        // 3. Add Listeners to trigger updates
        binding.pickerMin.setOnValueChangedListener { _, _, _ -> updateLimits() }
        binding.pickerSec.setOnValueChangedListener { _, _, _ -> updateLimits() }

        // 4. Button Listeners
        binding.buttonPickerCancel.setOnClickListener { binding.layoutTimePicker.visibility = View.GONE }

        binding.buttonPickerOk.setOnClickListener {
            val min = binding.pickerMin.value.toLong()
            val sec = binding.pickerSec.value.toLong()
            val ms100 = binding.pickerMs.value.toLong()

            val newTimeMs = (min * 60000) + (sec * 1000) + (ms100 * 100)
            val safeTimeMs = newTimeMs.coerceAtMost(durationMs)

            if (isPickingStartTime) {
                if (safeTimeMs < endTimeMs) {
                    startTimeMs = safeTimeMs
                    binding.rangeSlider.setValues(startTimeMs.toFloat(), endTimeMs.toFloat())
                } else {
                    Toast.makeText(this, "Start time must be before end time", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            } else {
                if (safeTimeMs > startTimeMs) {
                    endTimeMs = safeTimeMs
                    binding.rangeSlider.setValues(startTimeMs.toFloat(), endTimeMs.toFloat())
                } else {
                    Toast.makeText(this, "End time must be after start time", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

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
        val min = (totalSecs / 60).toInt()
        val sec = (totalSecs % 60).toInt()
        val decisecond = ((timeMs % 1000) / 100).toInt()

        // Set Picker Values
        binding.pickerMin.value = min
        binding.pickerSec.value = sec
        binding.pickerMs.value = decisecond
    }

    private fun updateCursorPosition() {
        val currentPos = exoPlayer?.currentPosition ?: 0L
        val timelineWidth = binding.recyclerThumbnails.width

        if (durationMs > 0 && timelineWidth > 0) {
            val progress = currentPos.toFloat() / durationMs.toFloat()
            val translationX = progress * timelineWidth

            // 1. Move the Playback Cursor
            binding.viewCursor.translationX = translationX

            // 2. NEW: Update Tooltip Text and Position
            binding.textCursorLabel.text = formatTimeDecimal(currentPos)

            // Measure the label if it hasn't been drawn yet so we can center it
            if (binding.textCursorLabel.width == 0) {
                binding.textCursorLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            }

            val labelWidth = binding.textCursorLabel.measuredWidth
            val containerWidth = binding.layoutTimelineContainer.width

            // Calculate Centered Position: CursorX - (LabelWidth / 2)
            // We clamp it so it doesn't fly off the left or right edges
            val maxTrans = (containerWidth - labelWidth).toFloat().coerceAtLeast(0f)
            val tooltipX = (translationX - (labelWidth / 2)).coerceIn(0f, maxTrans)

            binding.textCursorLabel.translationX = tooltipX
        }
    }

    private fun startPlayback() {
        exoPlayer?.play()
        binding.buttonPlayPause.setImageResource(R.drawable.pause_24px)

        // NEW: Show tooltip during playback
        binding.textCursorLabel.visibility = View.VISIBLE

        handler.post(updateCursorRunnable)
    }

    private fun pausePlayback() {
        exoPlayer?.pause()
        binding.buttonPlayPause.setImageResource(R.drawable.play_arrow_24px)

        // NEW: Hide tooltip when paused (optional, keeps UI clean)
        binding.textCursorLabel.visibility = View.GONE

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

        // 1. NEW: Copy the remote file to a local cache file we fully own
        // This solves the 'avc: denied dmabuf' error by giving us a clean file descriptor
        val localInputFile = copyUriToCache(this, sourceUri)
        if (localInputFile == null) {
            Toast.makeText(this, "Failed to load video file", Toast.LENGTH_SHORT).show()
            return
        }

        val tempFile = File(externalCacheDir ?: cacheDir, "temp_trim_${System.currentTimeMillis()}.mp4")

        // 2. Configure Clipping
        val clippingConfiguration = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startTimeMs)
            .setEndPositionMs(endTimeMs)
            .build()

        // 3. Build MediaItem using the LOCAL FILE, not the original URI
        val mediaItem = MediaItem.Builder()
            .setUri(localInputFile.toURI().toString()) // Use local path
            .setClippingConfiguration(clippingConfiguration)
            .build()

        // 4. Force Transmuxing (Passthrough)
        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
        val sequence = EditedMediaItemSequence.Builder(editedMediaItem).build()

        val composition = Composition.Builder(listOf(sequence))
            .setTransmuxVideo(true)
            .setTransmuxAudio(true)
            .build()

        transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    saveToGallery(tempFile)
                    localInputFile.delete() // Clean up the input copy
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    exportException.printStackTrace()
                    localInputFile.delete() // Clean up on error
                    Toast.makeText(this@EditorActivity, "Export Failed: ${exportException.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            })
            .build()

        transformer?.start(composition, tempFile.absolutePath)
    }

    private fun copyUriToCache(context: Context, sourceUri: android.net.Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(sourceUri) ?: return null
            val outputFile = File(context.cacheDir, "input_cache_${System.currentTimeMillis()}.mp4")
            val outputStream = FileOutputStream(outputFile)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveToGallery(tempFile: File) {
        try {
            val originalTitle = mediaFile?.title ?: "media"
            val timestamp = System.currentTimeMillis() / 1000
            val isVideo = mediaFile?.isVideo == true

            // 1. Determine Extension and Mime Type
            // Note: Transformer outputs MP4 container. For audio, we use .m4a
            val extension = if (isVideo) "mp4" else "m4a"
            val mimeType = if (isVideo) "video/mp4" else "audio/mp4"
            val directory = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC

            val newTitle = "${originalTitle}_trim_$timestamp"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$newTitle.$extension") // <--- Fixed Extension
                put(MediaStore.MediaColumns.DATE_ADDED, timestamp)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, directory) // <--- Fixed Directory
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            // ... (Rest of your existing insert/copy logic remains the same) ...

            val collection = if (isVideo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val itemUri = contentResolver.insert(collection, values)

            if (itemUri != null) {
                contentResolver.openOutputStream(itemUri).use { outStream ->
                    tempFile.inputStream().use { inputStream -> inputStream.copyTo(outStream!!) }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    contentResolver.update(itemUri, values, null, null)
                }

                Toast.makeText(this, "Saved to ${if(isVideo) "Movies" else "Music"}", Toast.LENGTH_LONG).show()
                tempFile.delete()
                finish()
            } else {
                throw Exception("Failed to create MediaStore entry")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error saving file", Toast.LENGTH_LONG).show()
        }
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