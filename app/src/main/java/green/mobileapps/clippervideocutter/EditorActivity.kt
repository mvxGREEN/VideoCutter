package green.mobileapps.clippervideocutter

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.getDrawable
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
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

@UnstableApi
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: EditorActivityBinding
    private var exoPlayer: ExoPlayer? = null
    private var mediaFile: MediaFile? = null
    private var transformer: Transformer? = null

    private var startTimeMs: Long = 0L
    private var endTimeMs: Long = 0L

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

        @Suppress("DEPRECATION")
        mediaFile = intent.getParcelableExtra("EXTRA_MEDIA_FILE")

        if (mediaFile == null) {
            Toast.makeText(this, "Error loading media", Toast.LENGTH_SHORT).show()
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

        val uri = mediaFile?.uri ?: return
        val mediaItem = MediaItem.fromUri(uri)

        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = false

        // Set a default artwork for audio if needed,
        // though PlayerView might handle metadata from Uri automatically.
        if (mediaFile?.isVideo == false) {
            binding.playerView.defaultArtwork = getDrawable(this, R.drawable.musi_note_432px)
        }
    }

    private fun setupRangeSlider() {
        val duration = mediaFile?.duration ?: 0L
        endTimeMs = duration

        binding.rangeSlider.valueFrom = 0f
        binding.rangeSlider.valueTo = duration.toFloat().coerceAtLeast(1f) // Prevent 0 duration crash
        binding.rangeSlider.values = listOf(0f, duration.toFloat().coerceAtLeast(1f))

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
            if (exoPlayer?.isPlaying == true) pauseVideo() else playVideo()
        }
        binding.buttonSave.setOnClickListener { saveMedia() }
    }

    private fun saveMedia() {
        val sourceUri = mediaFile?.uri ?: return
        pauseVideo()
        setUiEnabled(false)
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
                    setUiEnabled(true)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Toast.makeText(this@EditorActivity, "Export Failed: ${exportException.localizedMessage}", Toast.LENGTH_LONG).show()
                    setUiEnabled(true)
                    if (tempFile.exists()) tempFile.delete()
                }
            })
            .build()

        transformer?.start(mediaItem, tempFile.absolutePath)
    }

    private fun saveToGallery(tempFile: File) {
        try {
            val originalTitle = mediaFile?.title ?: "media"
            val timestamp = System.currentTimeMillis() / 1000
            val newTitle = "${originalTitle}_trim_$timestamp"
            val isVideo = mediaFile?.isVideo == true

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$newTitle.mp4") // Transformer output is usually MP4/M4A
                put(MediaStore.MediaColumns.DATE_ADDED, timestamp)

                if (isVideo) {
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                } else {
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4") // Audio in MP4 container
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                    }
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

                Toast.makeText(this, if(isVideo) "Saved to Movies!" else "Saved to Music!", Toast.LENGTH_LONG).show()
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

    private fun setUiEnabled(enabled: Boolean) {
        binding.buttonSave.isEnabled = enabled
        binding.buttonPlayPause.isEnabled = enabled
        binding.rangeSlider.isEnabled = enabled
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

    private fun seekTo(positionMs: Long) { exoPlayer?.seekTo(positionMs) }

    private fun checkPlaybackLoop() {
        val currentPos = exoPlayer?.currentPosition ?: 0L
        if (currentPos >= endTimeMs || currentPos < startTimeMs) seekTo(startTimeMs)
    }

    private fun formatTime(timeMs: Long): String {
        val totalSeconds = timeMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        val mFormatter = Formatter(StringBuilder(), Locale.getDefault())
        return if (hours > 0) mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString()
        else mFormatter.format("%02d:%02d", minutes, seconds).toString()
    }

    override fun onPause() {
        super.onPause()
        pauseVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        transformer?.cancel()
        handler.removeCallbacks(updateProgressRunnable)
    }
}