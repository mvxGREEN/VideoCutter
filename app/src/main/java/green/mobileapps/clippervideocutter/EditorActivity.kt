package green.mobileapps.clippervideocutter

import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.android.material.slider.RangeSlider
import green.mobileapps.clippervideocutter.databinding.EditorActivityBinding
import java.io.File
import java.util.Formatter
import java.util.Locale

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: EditorActivityBinding
    private var exoPlayer: ExoPlayer? = null
    private var videoFile: VideoFile? = null
    private var transformer: Transformer? = null

    // Trim range in milliseconds
    private var startTimeMs: Long = 0L
    private var endTimeMs: Long = 0L

    // Handler for updating playback progress loop
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            checkPlaybackLoop()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = EditorActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Retrieve video file (handling strict parcelable for newer APIs recommended)
        @Suppress("DEPRECATION")
        videoFile = intent.getParcelableExtra("EXTRA_VIDEO_FILE")

        if (videoFile == null) {
            Toast.makeText(this, "Error loading video", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupPlayer()
        setupRangeSlider()
        setupButtons()
    }

    private fun setupPlayer() {
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer

        val uri = videoFile?.uri ?: return
        val mediaItem = MediaItem.fromUri(uri)

        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = false
    }

    private fun setupRangeSlider() {
        val duration = videoFile?.duration ?: 0L
        endTimeMs = duration

        binding.rangeSlider.valueFrom = 0f
        binding.rangeSlider.valueTo = duration.toFloat()
        binding.rangeSlider.values = listOf(0f, duration.toFloat())

        binding.textStartTime.text = formatTime(0L)
        binding.textEndTime.text = formatTime(duration)

        binding.rangeSlider.addOnChangeListener { slider, value, fromUser ->
            val values = slider.values
            val newStart = values[0].toLong()
            val newEnd = values[1].toLong()

            if (kotlin.math.abs(newStart - startTimeMs) > 100) {
                startTimeMs = newStart
                seekTo(startTimeMs)
                binding.textStartTime.text = formatTime(startTimeMs)
            } else if (kotlin.math.abs(newEnd - endTimeMs) > 100) {
                endTimeMs = newEnd
                seekTo(endTimeMs)
                binding.textEndTime.text = formatTime(endTimeMs)
            }
        }

        binding.rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {
                exoPlayer?.pause()
                handler.removeCallbacks(updateProgressRunnable)
            }

            override fun onStopTrackingTouch(slider: RangeSlider) {
                startTimeMs = slider.values[0].toLong()
                endTimeMs = slider.values[1].toLong()
                seekTo(startTimeMs)
            }
        })
    }

    private fun setupButtons() {
        binding.buttonPlayPause.setOnClickListener {
            if (exoPlayer?.isPlaying == true) {
                pauseVideo()
            } else {
                playVideo()
            }
        }

        binding.buttonSave.setOnClickListener {
            saveVideo()
        }
    }

    private fun saveVideo() {
        val sourceUri = videoFile?.uri ?: return
        pauseVideo()

        // 1. Disable UI during export
        setUiEnabled(false)
        Toast.makeText(this, "Exporting...", Toast.LENGTH_SHORT).show()

        // 2. Create a temporary file to save the trim result
        val cacheDir = externalCacheDir ?: cacheDir
        val tempFile = File(cacheDir, "temp_trim_${System.currentTimeMillis()}.mp4")

        // 3. Configure the Clipping Configuration
        val clippingConfiguration = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startTimeMs)
            .setEndPositionMs(endTimeMs)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(clippingConfiguration)
            .build()

        // 4. Build and Start Transformer
        transformer = Transformer.Builder(this)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    // Export successful: Move file to Gallery
                    saveToGallery(tempFile)
                    setUiEnabled(true)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Toast.makeText(this@EditorActivity, "Export Failed: ${exportException.localizedMessage}", Toast.LENGTH_LONG).show()
                    exportException.printStackTrace()
                    setUiEnabled(true)
                    // Cleanup temp file
                    if (tempFile.exists()) tempFile.delete()
                }
            })
            .build()

        // Start the transformation writing to the temp file path
        transformer?.start(mediaItem, tempFile.absolutePath)
    }

    private fun saveToGallery(tempFile: File) {
        try {
            val originalTitle = videoFile?.title ?: "video"
            val newTitle = "${originalTitle}_trim_${System.currentTimeMillis() / 1000}"

            // Prepare ContentValues for MediaStore
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$newTitle.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1) // Tell OS we are writing
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            // Insert into MediaStore
            val itemUri = contentResolver.insert(collection, values)

            if (itemUri != null) {
                // Copy data from temp file to MediaStore URI
                contentResolver.openOutputStream(itemUri).use { outStream ->
                    tempFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outStream!!)
                    }
                }

                // If Android Q+, mark as finished (not pending)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(itemUri, values, null, null)
                }

                // Notify User
                Toast.makeText(this, "Saved to Movies folder!", Toast.LENGTH_LONG).show()

                // Cleanup
                tempFile.delete()

                // Finish activity or reset
                finish()
            } else {
                throw Exception("Failed to create MediaStore entry")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error saving to Gallery", Toast.LENGTH_LONG).show()
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.buttonSave.isEnabled = enabled
        binding.buttonPlayPause.isEnabled = enabled
        binding.rangeSlider.isEnabled = enabled
        // Optionally show/hide a ProgressBar here
        binding.buttonSave.text = if (enabled) "Save" else "Saving..."
    }

    private fun playVideo() {
        exoPlayer?.play()
        binding.buttonPlayPause.setImageResource(R.drawable.pause_24px)
        handler.post(updateProgressRunnable)
    }

    private fun pauseVideo() {
        exoPlayer?.pause()
        binding.buttonPlayPause.setImageResource(R.drawable.play_arrow_24px)
        handler.removeCallbacks(updateProgressRunnable)
    }

    private fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    private fun checkPlaybackLoop() {
        val currentPos = exoPlayer?.currentPosition ?: 0L
        if (currentPos >= endTimeMs) {
            seekTo(startTimeMs)
        } else if (currentPos < startTimeMs) {
            seekTo(startTimeMs)
        }
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        val mFormatter = Formatter(StringBuilder(), Locale.getDefault())

        return if (hours > 0) {
            mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString()
        } else {
            mFormatter.format("%02d:%02d", minutes, seconds).toString()
        }
    }

    override fun onPause() {
        super.onPause()
        pauseVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        transformer?.cancel() // Cancel export if user closes app
        handler.removeCallbacks(updateProgressRunnable)
    }
}