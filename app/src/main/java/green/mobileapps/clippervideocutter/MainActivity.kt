package green.mobileapps.clippervideocutter

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import green.mobileapps.clippervideocutter.databinding.ItemVideoFileBinding
import green.mobileapps.clippervideocutter.databinding.MainActivityBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

// --- SORTING DEFINITIONS ---
enum class SortBy { DATE, TITLE, DURATION }
data class SortState(val by: SortBy, val ascending: Boolean)
// ---------------------------

// --- Interface for Adapter/Activity communication ---
interface MusicEditListener {
    fun startEditing(position: Int)
    fun saveEditAndExit(mediaFile: MediaFile, newTitle: String, newArtist: String)
}

// --- DATA MODEL REFACTOR ---
data class MediaFile(
    val id: Long,
    val uri: Uri,
    val title: String,
    val duration: Long,
    val size: Long,
    val resolution: String?, // Null for audio
    val artist: String?,     // Null for video usually
    val dateAdded: Long,
    val isVideo: Boolean     // Flag to distinguish types
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readParcelable(Uri::class.java.classLoader)!!,
        parcel.readString()!!,
        parcel.readLong(),
        parcel.readLong(),
        parcel.readString(),
        parcel.readString(),
        parcel.readLong(),
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeParcelable(uri, flags)
        parcel.writeString(title)
        parcel.writeLong(duration)
        parcel.writeLong(size)
        parcel.writeString(resolution)
        parcel.writeString(artist)
        parcel.writeLong(dateAdded)
        parcel.writeByte(if (isVideo) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<MediaFile> {
        override fun createFromParcel(parcel: Parcel): MediaFile = MediaFile(parcel)
        override fun newArray(size: Int): Array<MediaFile?> = arrayOfNulls(size)
    }
}

// 1. Repository
object PlaylistRepository {
    private val _mediaFiles = MutableLiveData<List<MediaFile>>(emptyList())
    val mediaFiles: LiveData<List<MediaFile>> = _mediaFiles

    fun setFiles(files: List<MediaFile>) {
        _mediaFiles.postValue(files)
    }

    fun updateFile(updatedFile: MediaFile) {
        val currentList = _mediaFiles.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedFile.id }
        if (index != -1) {
            currentList[index] = updatedFile
            _mediaFiles.postValue(currentList)
        }
    }

    fun getFullPlaylist(): List<MediaFile> = _mediaFiles.value ?: emptyList()
}


// 2. ViewModel
class MusicViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val fullMediaList: LiveData<List<MediaFile>> = PlaylistRepository.mediaFiles
    private var mediaListFull: List<MediaFile> = emptyList()
    private val _filteredList = MutableLiveData<List<MediaFile>>(emptyList())
    val filteredList: LiveData<List<MediaFile>> = _filteredList

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private var currentQuery: String = ""
    private val _sortState = MutableLiveData(SortState(SortBy.DATE, false))
    val sortState: LiveData<SortState> = _sortState

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    init {
        fullMediaList.observeForever { newList ->
            mediaListFull = newList
            applySortAndFilter()
        }
    }

    fun loadMediaFiles(context: Context) {
        if (_isLoading.value == true) return

        _isLoading.postValue(true)
        _statusMessage.postValue("Scanning for media files...")

        scope.launch {
            val combinedList = mutableListOf<MediaFile>()

            // 1. Load Videos
            combinedList.addAll(loadVideos(context))
            // 2. Load Audio
            combinedList.addAll(loadAudio(context))

            PlaylistRepository.setFiles(combinedList)

            if (combinedList.isEmpty()) {
                _statusMessage.postValue("No media found.")
            } else {
                _statusMessage.postValue("Loaded ${combinedList.size} files.")
            }
            _isLoading.postValue(false)
        }
    }

    private fun loadVideos(context: Context): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RESOLUTION,
            MediaStore.Video.Media.DATE_ADDED
        )
        val selection = "${MediaStore.Video.Media.DURATION} > 0"

        try {
            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE))
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                    val resolution = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RESOLUTION))
                    val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))
                    val contentUri = ContentUris.withAppendedId(uri, id)

                    files.add(MediaFile(id, contentUri, title, duration, size, resolution, null, dateAdded, true))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return files
    }

    private fun loadAudio(context: Context): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATE_ADDED
        )
        // Exclude generic sounds/ringtones if desired by checking IS_MUSIC, but keeping simple here
        val selection = "${MediaStore.Audio.Media.DURATION} > 0"

        try {
            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
                    val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                    val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                    val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                    val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
                    val contentUri = ContentUris.withAppendedId(uri, id)

                    files.add(MediaFile(id, contentUri, title, duration, size, null, artist, dateAdded, false))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return files
    }

    fun applySortAndFilter() {
        val sortedList = applySort(mediaListFull, _sortState.value!!)
        val filteredList = applyFilter(sortedList, currentQuery)
        _filteredList.postValue(filteredList)
    }

    fun filterList(query: String) {
        currentQuery = query
        applySortAndFilter()
    }

    fun setSortState(newSortState: SortState) {
        _sortState.value = newSortState
        applySortAndFilter()
    }

    fun toggleSortDirection() {
        val current = _sortState.value ?: SortState(SortBy.DATE, false)
        _sortState.value = current.copy(ascending = !current.ascending)
        applySortAndFilter()
    }

    private fun applyFilter(list: List<MediaFile>, query: String): List<MediaFile> {
        val lowerCaseQuery = query.lowercase()
        return if (lowerCaseQuery.isBlank()) list else list.filter {
            it.title.lowercase().contains(lowerCaseQuery) || (it.artist?.lowercase()?.contains(lowerCaseQuery) == true)
        }
    }

    private fun applySort(list: List<MediaFile>, state: SortState): List<MediaFile> {
        val comparator: Comparator<MediaFile> = when (state.by) {
            SortBy.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortBy.DURATION -> compareBy { it.duration }
            SortBy.DATE -> compareBy { it.dateAdded }
        }
        val sortedList = list.sortedWith(comparator)
        return if (state.ascending) sortedList else sortedList.reversed()
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}

// 3. Adapter
class MusicAdapter(
    private val activity: MainActivity,
    private var mediaList: List<MediaFile>,
    private val editListener: MusicEditListener
) : RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    private var editingPosition: Int = RecyclerView.NO_POSITION

    fun setEditingPosition(newPosition: Int) {
        val oldPosition = editingPosition
        editingPosition = newPosition
        if (oldPosition != RecyclerView.NO_POSITION) notifyItemChanged(oldPosition)
        if (newPosition != RecyclerView.NO_POSITION) notifyItemChanged(newPosition)
    }

    fun getEditingPosition(): Int = editingPosition
    fun getCurrentList(): List<MediaFile> = mediaList

    inner class MusicViewHolder(private val binding: ItemVideoFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(file: MediaFile, index: Int) {
            val isEditing = adapterPosition == editingPosition

            if (index % 2 == 0) {
                binding.itemCard.setCardBackgroundColor(ContextCompat.getColor(binding.itemCard.context, R.color.light_gray))
            } else {
                binding.itemCard.setCardBackgroundColor(ContextCompat.getColor(binding.itemCard.context, R.color.white))
            }

            com.bumptech.glide.Glide.with(itemView.context)
                .load(file.uri)
                .placeholder(if (file.isVideo) android.R.drawable.ic_menu_gallery else android.R.drawable.ic_media_play)
                .centerCrop()
                .into(binding.imageAlbumArt)

            binding.textTitle.text = file.title

            // Display Artist for Audio, Resolution for Video
            binding.textArtist.text = if (file.isVideo) {
                file.resolution ?: "Video"
            } else {
                file.artist ?: "Unknown Artist"
            }

            binding.editTextTitle.setText(file.title)
            binding.editTextArtist.setText(if (file.isVideo) "" else file.artist)

            // Visibility logic for Edit Mode
            if (isEditing) {
                binding.textTitle.visibility = View.GONE
                binding.textArtist.visibility = View.GONE
                binding.editTextTitle.visibility = View.VISIBLE
                // Only show second edit text if it's audio (for artist), or hide for video
                binding.editTextArtist.visibility = if(file.isVideo) View.GONE else View.VISIBLE
                binding.buttonSaveEdit.visibility = View.VISIBLE
            } else {
                binding.textTitle.visibility = View.VISIBLE
                binding.textArtist.visibility = View.VISIBLE
                binding.editTextTitle.visibility = View.GONE
                binding.editTextArtist.visibility = View.GONE
                binding.buttonSaveEdit.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                if (!isEditing) activity.startVideoEditor(file, adapterPosition)
            }

            binding.buttonSaveEdit.setOnClickListener {
                val newTitle = binding.editTextTitle.text.toString().trim()
                val newArtist = binding.editTextArtist.text.toString().trim()
                editListener.saveEditAndExit(file, newTitle, newArtist)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val binding = ItemVideoFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MusicViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        holder.bind(mediaList[position], position)
    }

    override fun getItemCount(): Int = mediaList.size

    fun updateList(newList: List<MediaFile>) {
        mediaList = newList
        notifyDataSetChanged()
    }
}

// 4. Activity
class MainActivity : AppCompatActivity(), CoroutineScope, SearchView.OnQueryTextListener, MusicEditListener {

    private var job: Job = Job()
    override val coroutineContext: CoroutineContext get() = Dispatchers.Main + job

    private lateinit var binding: MainActivityBinding
    private lateinit var viewModel: MusicViewModel
    public lateinit var musicAdapter: MusicAdapter
    private lateinit var sortButton: ImageButton
    private lateinit var sortDirectionButton: ImageButton
    private lateinit var backButton: ImageButton

    // PERMISSIONS: Add Audio for Android 13+
    private val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private val notificationPermission = Manifest.permission.POST_NOTIFICATIONS

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission()
            } else {
                viewModel.loadMediaFiles(applicationContext)
            }
        } else {
            showStatus("Permissions denied. Cannot scan media.")
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        viewModel.loadMediaFiles(applicationContext)
    }

    private val requestWritePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) executePendingMetadataUpdate()
        else {
            Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show()
            exitEditingMode()
        }
    }

    private var pendingUpdateFile: MediaFile? = null
    private var pendingUpdateTitle: String? = null
    private var pendingUpdateArtist: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))
            .get(MusicViewModel::class.java)

        val toolbarLayout = binding.toolbarSearch.getChildAt(0) as? ViewGroup
        sortButton = toolbarLayout?.findViewById(R.id.button_sort)!!
        sortDirectionButton = toolbarLayout.findViewById(R.id.button_sort_direction)!!
        backButton = toolbarLayout.findViewById(R.id.button_back_edit)!!

        setupRecyclerView()
        setupSearchView()
        setupSortButton()
        setupSortDirectionButton()
        setupBackButton()
        setupSystemBackPressHandler()
        setupSwipeRefresh()
        setupObservers()
        checkPermissions()
    }

    private fun setupBackButton() {
        backButton.setOnClickListener { handleExitEditMode() }
    }

    private fun setupSystemBackPressHandler() {
        onBackPressedDispatcher.addCallback(this) {
            if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) handleExitEditMode()
            else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    private fun handleExitEditMode() {
        val position = musicAdapter.getEditingPosition()
        if (position != RecyclerView.NO_POSITION) {
            val viewHolder = binding.recyclerViewMusic.findViewHolderForAdapterPosition(position) as? MusicAdapter.MusicViewHolder
            val file = musicAdapter.getCurrentList().getOrNull(position)
            val titleEditText = viewHolder?.itemView?.findViewById<EditText>(R.id.edit_text_title)
            val artistEditText = viewHolder?.itemView?.findViewById<EditText>(R.id.edit_text_artist)

            if (file != null && titleEditText != null && artistEditText != null) {
                saveEditAndExit(file, titleEditText.text.toString().trim(), artistEditText.text.toString().trim())
            } else {
                exitEditingMode()
            }
        }
    }

    private fun setupSortDirectionButton() {
        sortDirectionButton.setOnClickListener { viewModel.toggleSortDirection() }
    }

    private fun setupSortButton() {
        sortButton.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.apply {
                add(0, SortBy.DATE.ordinal, 0, "Date Added")
                add(0, SortBy.TITLE.ordinal, 1, "Title")
                add(0, SortBy.DURATION.ordinal, 2, "Duration")
            }
            popup.setOnMenuItemClickListener { item: MenuItem ->
                val sortCriterion = SortBy.entries.find { it.ordinal == item.itemId }
                if (sortCriterion != null) {
                    val currentSortState = viewModel.sortState.value!!
                    val newAscending = if (sortCriterion == currentSortState.by) !currentSortState.ascending else currentSortState.ascending
                    viewModel.setSortState(SortState(sortCriterion, newAscending))
                    true
                } else false
            }
            popup.show()
        }
        viewModel.sortState.observe(this) { state ->
            sortDirectionButton.setImageResource(if (state.ascending) R.drawable.ascending_24px else R.drawable.descending_24px)
        }
    }

    override fun onResume() {
        super.onResume()
        hideKeyboardAndClearFocus()
        if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) exitEditingMode()
    }

    private fun setupObservers() {
        viewModel.filteredList.observe(this) { list ->
            if (list.isNotEmpty()) {
                musicAdapter.updateList(list)
                binding.recyclerViewMusic.visibility = View.VISIBLE
                binding.textStatus.visibility = View.GONE
            } else if (PlaylistRepository.getFullPlaylist().isNotEmpty()) {
                musicAdapter.updateList(emptyList())
                showStatus("No matches.")
            }
        }
        viewModel.statusMessage.observe(this) { if (!it.contains("Loaded")) showStatus(it) }
        viewModel.isLoading.observe(this) { binding.swipeRefreshLayout.isRefreshing = it }
    }

    private fun setupRecyclerView() {
        musicAdapter = MusicAdapter(this, emptyList(), this)
        binding.recyclerViewMusic.adapter = musicAdapter
    }

    private fun setupSearchView() { binding.searchViewMusic.setOnQueryTextListener(this) }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            musicAdapter.updateList(emptyList())
            exitEditingMode()
            viewModel.loadMediaFiles(applicationContext)
        }
    }

    private fun checkPermissions() {
        val needed = mediaPermissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestNotificationPermission()
            else viewModel.loadMediaFiles(applicationContext)
        } else {
            requestStoragePermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, notificationPermission) != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionLauncher.launch(notificationPermission)
        } else {
            viewModel.loadMediaFiles(applicationContext)
        }
    }

    @OptIn(UnstableApi::class)
    fun startVideoEditor(file: MediaFile, index: Int) {
        if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) {
            exitEditingMode()
            return
        }
        hideKeyboardAndClearFocus()
        val intent = Intent(this, EditorActivity::class.java).apply {
            putExtra("EXTRA_MEDIA_FILE", file) // UPDATED KEY
        }
        startActivity(intent)
    }

    override fun startEditing(position: Int) {
        val viewHolder = binding.recyclerViewMusic.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {
            binding.searchViewMusic.visibility = View.GONE
            binding.buttonSort.visibility = View.GONE
            binding.buttonSortDirection.visibility = View.GONE
            binding.buttonBackEdit.visibility = View.VISIBLE
            binding.textEditTitle.visibility = View.VISIBLE
            binding.searchViewMusic.isEnabled = false
            binding.searchViewMusic.clearFocus()

            musicAdapter.setEditingPosition(position)
            viewHolder.itemView.findViewById<EditText>(R.id.edit_text_title)?.requestFocus()
            Toast.makeText(this, "Editing metadata...", Toast.LENGTH_LONG).show()
        }
    }

    override fun saveEditAndExit(mediaFile: MediaFile, newTitle: String, newArtist: String) {
        if (newTitle == mediaFile.title && newArtist == (mediaFile.artist ?: "")) {
            exitEditingMode()
            return
        }
        pendingUpdateFile = mediaFile
        pendingUpdateTitle = newTitle
        pendingUpdateArtist = newArtist
        requestMetadataWritePermission(listOf(mediaFile.uri))
    }

    private fun requestMetadataWritePermission(uris: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createWriteRequest(contentResolver, uris)
            requestWritePermissionLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            executePendingMetadataUpdate()
        }
    }

    private fun executePendingMetadataUpdate() {
        val file = pendingUpdateFile ?: return
        val newTitle = pendingUpdateTitle ?: return
        val newArtist = pendingUpdateArtist

        pendingUpdateFile = null; pendingUpdateTitle = null; pendingUpdateArtist = null

        launch(Dispatchers.IO) {
            try {
                val mimeType = contentResolver.getType(file.uri) ?: if(file.isVideo) "video/mp4" else "audio/mp4"

                val contentValues = ContentValues().apply {
                    if (file.isVideo) {
                        put(MediaStore.Video.Media.TITLE, newTitle)
                        put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    } else {
                        put(MediaStore.Audio.Media.TITLE, newTitle)
                        if (newArtist != null) put(MediaStore.Audio.Media.ARTIST, newArtist)
                        put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    }
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }

                delay(500)
                val rows = contentResolver.update(file.uri, contentValues, null, null)

                withContext(Dispatchers.Main) {
                    if (rows > 0) {
                        Toast.makeText(this@MainActivity, "Updated!", Toast.LENGTH_SHORT).show()
                        val updatedFile = file.copy(title = newTitle, artist = newArtist)
                        PlaylistRepository.updateFile(updatedFile)
                    } else {
                        Toast.makeText(this@MainActivity, "Update failed.", Toast.LENGTH_LONG).show()
                    }
                    exitEditingMode()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { exitEditingMode() }
            }
        }
    }

    private fun exitEditingMode() {
        hideKeyboardAndClearFocus()
        musicAdapter.setEditingPosition(RecyclerView.NO_POSITION)
        binding.searchViewMusic.visibility = View.VISIBLE
        binding.buttonSort.visibility = View.VISIBLE
        binding.buttonSortDirection.visibility = View.VISIBLE
        binding.buttonBackEdit.visibility = View.GONE
        binding.textEditTitle.visibility = View.GONE
        binding.searchViewMusic.isEnabled = true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        hideKeyboardAndClearFocus()
        return true
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        if (binding.searchViewMusic.isEnabled) viewModel.filterList(newText.orEmpty())
        return true
    }

    private fun showStatus(message: String) {
        binding.recyclerViewMusic.visibility = View.GONE
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.text = message
    }

    private fun hideKeyboardAndClearFocus() {
        binding.searchViewMusic.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val windowToken = currentFocus?.windowToken ?: binding.root.windowToken
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}