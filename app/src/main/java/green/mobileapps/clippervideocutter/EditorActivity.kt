package green.mobileapps.clippervideocutter

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.slider.RangeSlider
import green.mobileapps.clippervideocutter.databinding.EditorActivityBinding
import java.util.Formatter
import java.util.Locale

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: EditorActivityBinding
    private var exoPlayer: ExoPlayer? = null
    private var videoFile: VideoFile? = null

    // Trim range in milliseconds
    private var startTimeMs: Long = 0L
    private var endTimeMs: Long = 0L

    // Handler for updating playback progress loop
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            checkPlaybackLoop()
            handler.postDelayed(this, 100) // Check every 100ms
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = EditorActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Retrieve the video file passed from MainActivity
        // Note: getParcelableExtra specific for API 33+ is safer, but this works for general compatibility
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
        exoPlayer?.playWhenReady = false // Start paused
    }

    private fun setupRangeSlider() {
        val duration = videoFile?.duration ?: 0L
        endTimeMs = duration

        // Initialize Slider
        binding.rangeSlider.valueFrom = 0f
        binding.rangeSlider.valueTo = duration.toFloat()
        binding.rangeSlider.values = listOf(0f, duration.toFloat())

        // Initial Text
        binding.textStartTime.text = formatTime(0L)
        binding.textEndTime.text = formatTime(duration)

        // Listener for changes (dragging)
        binding.rangeSlider.addOnChangeListener { slider, value, fromUser ->
            val values = slider.values
            val newStart = values[0].toLong()
            val newEnd = values[1].toLong()

            // Determine which handle changed to seek for preview
            if (kotlin.math.abs(newStart - startTimeMs) > 100) {
                // Start handle moved
                startTimeMs = newStart
                seekTo(startTimeMs)
                binding.textStartTime.text = formatTime(startTimeMs)
            } else if (kotlin.math.abs(newEnd - endTimeMs) > 100) {
                // End handle moved
                endTimeMs = newEnd
                seekTo(endTimeMs) // Preview the end cut
                binding.textEndTime.text = formatTime(endTimeMs)
            }
        }

        // Listener for touch stop (when user lets go of the handle)
        binding.rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {
                exoPlayer?.pause()
                handler.removeCallbacks(updateProgressRunnable)
            }

            override fun onStopTrackingTouch(slider: RangeSlider) {
                // When dragging stops, update global variables and ensure bounds
                startTimeMs = slider.values[0].toLong()
                endTimeMs = slider.values[1].toLong()

                // Seek to start of the new clip range
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
            // Here you would trigger the actual ffmpeg/transcoding logic
            val duration = endTimeMs - startTimeMs
            Toast.makeText(this, "Saving clip: ${formatTime(duration)} duration", Toast.LENGTH_SHORT).show()
            // TODO: Implement actual saving logic
        }
    }

    private fun playVideo() {
        exoPlayer?.play()
        binding.buttonPlayPause.setImageResource(R.drawable.pause_24px) // Ensure you have a pause icon
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

    /**
     * Checks if the video player has exceeded the 'End' handle of the range slider.
     * If so, loops back to the 'Start' handle.
     */
    private fun checkPlaybackLoop() {
        val currentPos = exoPlayer?.currentPosition ?: 0L

        // If we passed the end time, loop back to start
        if (currentPos >= endTimeMs) {
            seekTo(startTimeMs)
            // Optional: Pause at the end instead of looping?
            // pauseVideo()
        }
        // Logic to ensure we don't play before the start time (if user sought manually)
        else if (currentPos < startTimeMs) {
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
        handler.removeCallbacks(updateProgressRunnable)
    }
}