package green.mobileapps.clippervideocutter

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import green.mobileapps.clippervideocutter.databinding.EditorActivityBinding
import green.mobileapps.clippervideocutter.databinding.MainActivityBinding
import kotlin.jvm.java
import kotlin.text.isNullOrBlank

class EditorActivity : AppCompatActivity() {
    private lateinit var binding: EditorActivityBinding
    private val TAG = "MusicActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = EditorActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}