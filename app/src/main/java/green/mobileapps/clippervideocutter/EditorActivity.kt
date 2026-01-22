package green.mobileapps.clippervideocutter

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.style.UnderlineSpan
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
import androidx.media3.transformer.Transformer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import green.mobileapps.clippervideocutter.databinding.EditorActivityBinding
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        // --- UPDATED: Handle Share/View Intents ---
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            val uri = if (intent.action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            } else {
                intent.data
            }

            if (uri != null) {
                mediaFile = loadMediaFileFromUri(uri)
            }
        } else {
            // Standard launch from Main Activity
            @Suppress("DEPRECATION")
            mediaFile = intent.getParcelableExtra("EXTRA_MEDIA_FILE")
        }
        // ------------------------------------------

        if (mediaFile == null) {
            Toast.makeText(this, "Could not load file", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupPlayer()
        setupTimeline()
        setupButtons()
        setupTimePicker()
        setupRangeSliderTouchInterception()
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

        // underline start and end times
        val startTimeSpan = SpannableString(formatTimeDecimal(0L))
        val endTimeSpan = SpannableString(formatTimeDecimal(durationMs))
        startTimeSpan.setSpan(UnderlineSpan(),
            0,
            startTimeSpan.length,
            0)

        endTimeSpan.setSpan(UnderlineSpan(),
            0,
            endTimeSpan.length,
            0)

        binding.textStartTime.text = startTimeSpan
        binding.textEndTime.text = endTimeSpan

        if (mediaFile?.isVideo == true) {
            binding.recyclerThumbnails.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            val adapter = ThumbnailAdapter(this, mediaFile!!, numThumbnails)
            binding.recyclerThumbnails.adapter = adapter
        }

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

            // underline start and end times
            val startTimeSpan = SpannableString(formatTimeDecimal(startTimeMs))
            val endTimeSpan = SpannableString(formatTimeDecimal(endTimeMs))
            startTimeSpan.setSpan(UnderlineSpan(),
                0,
                startTimeSpan.length,
                0)

            endTimeSpan.setSpan(UnderlineSpan(),
                0,
                endTimeSpan.length,
                0)

            binding.textStartTime.text = startTimeSpan
            binding.textEndTime.text = endTimeSpan

            binding.textTotalDuration.text = "Total ${formatTimeDecimal(endTimeMs - startTimeMs)}"

            updateSelectionBorder()
        }

        binding.rangeSlider.post {
            updateSelectionBorder()
        }
    }

    private fun updateSelectionBorder() {
        val containerWidth = binding.recyclerThumbnails.width
        if (durationMs > 0 && containerWidth > 0) {
            val startRatio = startTimeMs.toFloat() / durationMs.toFloat()
            val endRatio = endTimeMs.toFloat() / durationMs.toFloat()

            val startX = startRatio * containerWidth
            val endX = endRatio * containerWidth
            val borderWidth = endX - startX

            val params = binding.viewSelectionBorder.layoutParams
            params.width = borderWidth.toInt()
            binding.viewSelectionBorder.layoutParams = params
            binding.viewSelectionBorder.translationX = startX
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupRangeSliderTouchInterception() {
        binding.touchInterceptor.isClickable = false

        binding.touchInterceptor.setOnTouchListener { v, event ->
            val slider = binding.rangeSlider
            val duration = (slider.valueTo - slider.valueFrom)
            if (duration <= 0) return@setOnTouchListener false

            val density = resources.displayMetrics.density
            val thumbRadiusPx = (16 * density).toInt()
            val trackWidth = v.width - (2 * thumbRadiusPx)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val startRatio = (startTimeMs - slider.valueFrom) / duration
                    val endRatio = (endTimeMs - slider.valueFrom) / duration
                    val startThumbX = thumbRadiusPx + (trackWidth * startRatio)
                    val endThumbX = thumbRadiusPx + (trackWidth * endRatio)

                    val touchX = event.x
                    val hitThreshold = thumbRadiusPx * 1.5f

                    if (abs(touchX - startThumbX) < hitThreshold || abs(touchX - endThumbX) < hitThreshold) {
                        return@setOnTouchListener false
                    } else {
                        updateScrubbing(touchX, thumbRadiusPx, trackWidth, duration)
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    updateScrubbing(event.x, thumbRadiusPx, trackWidth, duration)
                    return@setOnTouchListener true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.textCursorLabel.visibility = View.GONE
                    return@setOnTouchListener true
                }
            }
            return@setOnTouchListener false
        }
    }

    private fun updateScrubbing(touchX: Float, thumbOffset: Int, trackWidth: Int, duration: Float) {
        val touchXOffset = touchX - thumbOffset
        val touchXClamped = touchXOffset.coerceIn(0f, trackWidth.toFloat())
        val clickRatio = touchXClamped / trackWidth
        val seekTimeMs = (clickRatio * duration).toLong()

        seekTo(seekTimeMs)

        binding.textCursorLabel.visibility = View.VISIBLE
        binding.textCursorLabel.text = formatTimeDecimal(seekTimeMs)

        if (binding.textCursorLabel.width == 0f.toInt()) {
            binding.textCursorLabel.measure(0, 0)
        }

        val cursorX = binding.viewCursor.translationX
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
        val maxTotalSeconds = durationMs / 1000
        val maxMin = (maxTotalSeconds / 60).toInt()
        val maxSecRemain = (maxTotalSeconds % 60).toInt()
        val maxMsRemain = ((durationMs % 1000) / 100).toInt()

        binding.pickerMin.minValue = 0
        binding.pickerMin.maxValue = maxMin
        binding.pickerMin.wrapSelectorWheel = false
        binding.pickerMin.setFormatter { i -> String.format("%02d", i) }

        binding.pickerSec.minValue = 0
        binding.pickerSec.maxValue = 59
        binding.pickerSec.wrapSelectorWheel = false
        binding.pickerSec.setFormatter { i -> String.format("%02d", i) }

        binding.pickerMs.minValue = 0
        binding.pickerMs.maxValue = 9
        binding.pickerMs.wrapSelectorWheel = false

        // Helper to update limits based on max duration
        fun updateLimits() {
            val currentMin = binding.pickerMin.value

            if (currentMin == maxMin) {
                binding.pickerSec.maxValue = maxSecRemain
                if (binding.pickerSec.value > maxSecRemain) {
                    binding.pickerSec.value = maxSecRemain
                }

                if (binding.pickerSec.value == maxSecRemain) {
                    binding.pickerMs.maxValue = maxMsRemain
                    if (binding.pickerMs.value > maxMsRemain) {
                        binding.pickerMs.value = maxMsRemain
                    }
                } else {
                    binding.pickerMs.maxValue = 9
                }
            } else {
                binding.pickerSec.maxValue = 59
                binding.pickerMs.maxValue = 9
            }
        }

        // --- NEW: Helper to seek player while scrolling ---
        fun previewFromPicker() {
            val min = binding.pickerMin.value.toLong()
            val sec = binding.pickerSec.value.toLong()
            val ms100 = binding.pickerMs.value.toLong()

            val newTimeMs = (min * 60000) + (sec * 1000) + (ms100 * 100)
            val safeTimeMs = newTimeMs.coerceAtMost(durationMs)

            // Seek immediately to preview, but do NOT save to variables yet
            seekTo(safeTimeMs)
        }

        // --- UPDATED LISTENERS ---
        // Create one listener for all 3 pickers
        val changeListener = android.widget.NumberPicker.OnValueChangeListener { _, _, _ ->
            updateLimits()
            previewFromPicker() // Trigger seek whenever a value changes
        }

        binding.pickerMin.setOnValueChangedListener(changeListener)
        binding.pickerSec.setOnValueChangedListener(changeListener)
        binding.pickerMs.setOnValueChangedListener(changeListener)

        binding.buttonPickerCancel.setOnClickListener {
            binding.layoutTimePicker.visibility = View.GONE
            // Revert preview to the actual saved time if they cancel
            seekTo(if (isPickingStartTime) startTimeMs else endTimeMs)
        }

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
                    seekTo(startTimeMs) // Revert preview to valid start time
                    return@setOnClickListener
                }
            } else {
                if (safeTimeMs > startTimeMs) {
                    endTimeMs = safeTimeMs
                    binding.rangeSlider.setValues(startTimeMs.toFloat(), endTimeMs.toFloat())
                } else {
                    Toast.makeText(this, "End time must be after start time", Toast.LENGTH_SHORT).show()
                    seekTo(endTimeMs) // Revert preview to valid end time
                    return@setOnClickListener
                }
            }

            // Update UI text
            val startTimeSpan = SpannableString(formatTimeDecimal(startTimeMs))
            val endTimeSpan = SpannableString(formatTimeDecimal(endTimeMs))
            startTimeSpan.setSpan(UnderlineSpan(), 0, startTimeSpan.length, 0)
            endTimeSpan.setSpan(UnderlineSpan(), 0, endTimeSpan.length, 0)

            binding.textStartTime.text = startTimeSpan
            binding.textEndTime.text = endTimeSpan
            binding.textTotalDuration.text = "Total ${formatTimeDecimal(endTimeMs - startTimeMs)}"

            // Ensure player is at the final confirmed time
            seekTo(if(isPickingStartTime) startTimeMs else endTimeMs)

            binding.layoutTimePicker.visibility = View.GONE
        }
    }

    private fun showTimePicker(isStart: Boolean) {
        isPickingStartTime = isStart
        binding.layoutTimePicker.visibility = View.VISIBLE
        binding.textPickerTitle.text = if (isStart) "Set start time" else "Set end time"

        val timeMs = if (isStart) startTimeMs else endTimeMs

        val totalSecs = timeMs / 1000
        val min = (totalSecs / 60).toInt()
        val sec = (totalSecs % 60).toInt()
        val decisecond = ((timeMs % 1000) / 100).toInt()

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

            binding.viewCursor.translationX = translationX

            binding.textCursorLabel.text = formatTimeDecimal(currentPos)

            if (binding.textCursorLabel.width == 0) {
                binding.textCursorLabel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            }

            val labelWidth = binding.textCursorLabel.measuredWidth
            val containerWidth = binding.layoutTimelineContainer.width

            val maxTrans = (containerWidth - labelWidth).toFloat().coerceAtLeast(0f)
            val tooltipX = (translationX - (labelWidth / 2)).coerceIn(0f, maxTrans)

            binding.textCursorLabel.translationX = tooltipX
        }
    }

    private fun startPlayback() {
        exoPlayer?.play()
        binding.buttonPlayPause.setImageResource(R.drawable.pause_24px)
        binding.textCursorLabel.visibility = View.VISIBLE
        handler.post(updateCursorRunnable)
    }

    private fun pausePlayback() {
        exoPlayer?.pause()
        binding.buttonPlayPause.setImageResource(R.drawable.play_arrow_24px)
        //binding.textCursorLabel.visibility = View.GONE
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

        // --- FIX 1: RELEASE PLAYER RESOURCES ---
        // Pause isn't enough. We must release the player to free up the
        // decoder and RAM for the heavy Transformer operation.
        exoPlayer?.release()
        exoPlayer = null

        // Show the blocking overlay
        binding.layoutLoadingOverlay.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Move file copying to background thread (from previous fix)
            val localInputFile = withContext(Dispatchers.IO) {
                copyUriToCache(this@EditorActivity, sourceUri)
            }

            if (localInputFile == null) {
                binding.layoutLoadingOverlay.visibility = View.GONE
                Toast.makeText(this@EditorActivity, "Failed to load media file", Toast.LENGTH_SHORT).show()
                // If we failed, we might want to re-init the player here so the user isn't staring at a black screen,
                // but usually they will just exit or try again.
                return@launch
            }

            val tempFile = File(externalCacheDir ?: cacheDir, "temp_trim_${System.currentTimeMillis()}.mp4")

            val clippingConfiguration = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startTimeMs)
                .setEndPositionMs(endTimeMs)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(localInputFile.toURI().toString())
                .setClippingConfiguration(clippingConfiguration)
                .build()

            val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
            val sequence = EditedMediaItemSequence.Builder(editedMediaItem).build()

            val composition = Composition.Builder(listOf(sequence))
                //.setTransmuxVideo(true) // Try to pass-through without encoding
                .setTransmuxAudio(true)
                .build()

            transformer = Transformer.Builder(this@EditorActivity)
                .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        saveToGallery(tempFile)
                        localInputFile.delete()
                        // Activity finishes here, so no need to re-init player
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        binding.layoutLoadingOverlay.visibility = View.GONE
                        exportException.printStackTrace()
                        localInputFile.delete()
                        Toast.makeText(this@EditorActivity, "Export Failed: ${exportException.localizedMessage}", Toast.LENGTH_LONG).show()

                        // Optional: Re-initialize player here if you want the user to be able to preview again
                        // setupPlayer()
                    }
                })
                .build()

            transformer?.start(composition, tempFile.absolutePath)
        }
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

            val extension = if (isVideo) "mp4" else "m4a"
            val mimeType = if (isVideo) "video/mp4" else "audio/mp4"
            val directory = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC

            val newTitle = "${originalTitle}_trim_$timestamp"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$newTitle.$extension")
                put(MediaStore.MediaColumns.DATE_ADDED, timestamp)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

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

    // --- NEW: Helper method to construct MediaFile from Uri ---
    private fun loadMediaFileFromUri(uri: Uri): MediaFile? {
        var title = "Unknown"
        var duration = 0L
        var size = 0L
        var isVideo = false
        var resolution: String? = null
        var artist: String? = null

        try {
            // 1. Try to get filename from ContentResolver
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) title = it.getString(nameIndex)
                    if (sizeIndex != -1) size = it.getLong(sizeIndex)
                }
            }
            // Remove extension from title for cleaner UI
            title = title.substringBeforeLast(".")

            // 2. Use MediaMetadataRetriever for critical data (Duration, Type)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                duration = durationStr?.toLongOrNull() ?: 0L

                val hasVideoStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                isVideo = hasVideoStr != null

                if (isVideo) {
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (w != null && h != null) resolution = "${w}x${h}"
                } else {
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                }

                // If retriever didn't confirm video but mime type in intent said video, fallback to checking Mime
                if (!isVideo) {
                    val mime = contentResolver.getType(uri) ?: ""
                    if (mime.startsWith("video/")) isVideo = true
                }

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }

            return MediaFile(
                id = System.currentTimeMillis(), // Fake ID
                uri = uri,
                title = title,
                duration = duration,
                size = size,
                resolution = resolution,
                artist = artist,
                dateAdded = System.currentTimeMillis() / 1000,
                isVideo = isVideo
            )

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    class ThumbnailAdapter(private val context: Context, private val file: MediaFile, private val count: Int) : RecyclerView.Adapter<ThumbnailAdapter.ThumbViewHolder>() {
        private val interval = file.duration / count
        inner class ThumbViewHolder(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
            val displayMetrics = context.resources.displayMetrics
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