package green.mobileapps.clippervideocutter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentUris
import android.content.ContentValues
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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay // ADDED: Import delay for timing fix
import kotlin.coroutines.CoroutineContext
import androidx.activity.addCallback // Import for new back press handling
import green.mobileapps.clippervideocutter.databinding.ItemVideoFileBinding
import green.mobileapps.clippervideocutter.databinding.MainActivityBinding

// --- SORTING DEFINITIONS ---
enum class SortBy { DATE, TITLE, DURATION }
data class SortState(val by: SortBy, val ascending: Boolean)
// ---------------------------

// --- NEW: Interface for Adapter/Activity communication for editing ---
interface MusicEditListener {
    /**
     * Called by the ViewHolder on long click to initiate editing mode.
     * @param position The adapter position of the item.
     */
    fun startEditing(position: Int)

    /**
     * Called by the ViewHolder's save button or on click outside event.
     * @param videoFile The original AudioFile object.
     * @param newTitle The new title value from the EditText.
     * @param newArtist The new artist value from the EditText.
     */
    fun saveEditAndExit(videoFile: VideoFile, newTitle: String, newArtist: String)
}
// -------------------------------------------------------------------

// Helper extension function to safely get a string from a cursor
private fun Cursor.getNullableString(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getString(index) else null
}

// Helper extension function to safely get a long from a cursor
private fun Cursor.getNullableLong(columnName: String): Long? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getLong(index) else null
}

// Helper extension function to safely get an int from a cursor
private fun Cursor.getNullableInt(columnName: String): Int? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getInt(index) else null
}

// Helper extension function to safely get a boolean from a cursor (converts 0/1 to Boolean)
private fun Cursor.getNullableBoolean(columnName: String): Boolean? {
    val index = getColumnIndex(columnName)
    return if (index != -1 && !isNull(index)) getInt(index) == 1 else null
}

data class VideoFile(
    val id: Long,
    val uri: Uri,
    val title: String,
    val duration: Long,
    val size: Long,
    val resolution: String?, // e.g., "1920x1080"
    val dateAdded: Long
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readParcelable(Uri::class.java.classLoader)!!,
        parcel.readString()!!,
        parcel.readLong(),
        parcel.readLong(),
        parcel.readString(),
        parcel.readLong()
    ) {
}

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeParcelable(uri, flags)
        parcel.writeString(title)
        parcel.writeLong(duration)
        parcel.writeLong(size)
        parcel.writeString(resolution)
        parcel.writeLong(dateAdded)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<VideoFile> {
        override fun createFromParcel(parcel: Parcel): VideoFile {
            return VideoFile(parcel)
        }

        override fun newArray(size: Int): Array<VideoFile?> {
            return arrayOfNulls(size)
        }
    }
}

/**
 * Constructs the Uri for the album art image given the album ID.
 */
fun getAlbumArtUri(albumId: Long): Uri {
    return ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId
    )
}

// 1. Playlist Repository (Singleton) - Acts as the persistent store
// This is accessible by the Service, ViewModel, and Activities.
object PlaylistRepository {
    private val _videoFiles = MutableLiveData<List<VideoFile>>(emptyList())
    val videoFiles: LiveData<List<VideoFile>> = _videoFiles

    // Store the last clicked/currently playing index in the full list
    var currentTrackIndex: Int = -1

    fun setFiles(files: List<VideoFile>) {
        _videoFiles.postValue(files)
    }

    fun updateFile(updatedFile: VideoFile) {
        val currentList = _videoFiles.value.orEmpty().toMutableList() // Assuming you renamed _audioFiles to _videoFiles
        val index = currentList.indexOfFirst { it.id == updatedFile.id }
        if (index != -1) {
            currentList[index] = updatedFile
            _videoFiles.postValue(currentList)
        }
    }

    // Utility function for the service to get the current track
    fun getCurrentTrack(): VideoFile? {
        val list = _videoFiles.value
        return if (list != null && currentTrackIndex >= 0 && currentTrackIndex < list.size) {
            list[currentTrackIndex]
        } else {
            null
        }
    }

    // Utility function for the service to get the full list
    fun getFullPlaylist(): List<VideoFile> = _videoFiles.value ?: emptyList()
}


// 2. Music ViewModel - Used by MainActivity to load and filter the data
class MusicViewModel(application: android.app.Application) : AndroidViewModel(application) {

    // The full, unfiltered list from the repository
    private val fullAudioList: LiveData<List<VideoFile>> = PlaylistRepository.videoFiles

    // The list maintained for filtering and sorting purposes (displayed in RecyclerView)
    private var musicListFull: List<VideoFile> = emptyList()
    private val _filteredList = MutableLiveData<List<VideoFile>>(emptyList())
    val filteredList: LiveData<List<VideoFile>> = _filteredList

    private val _statusMessage = MutableLiveData<String>()
    val statusMessage: LiveData<String> = _statusMessage

    // NEW: LiveData to indicate loading state
    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // NEW: LiveData to hold the current search query
    private var currentQuery: String = ""

    // NEW: LiveData to hold the current sorting state (Default: LAST_MODIFIED Descending)
    private val _sortState = MutableLiveData(SortState(SortBy.DATE, false))
    val sortState: LiveData<SortState> = _sortState

    // Coroutine setup
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    init {
        // Observe the repository's full list to manage internal list and update filtered list
        fullAudioList.observeForever { newList ->
            musicListFull = newList
            // When the full list is updated, apply the current sort and filter
            applySortAndFilter()
        }
    }

    fun loadVideoFiles(context: Context) {
        if (_isLoading.value == true) return // Prevent multiple simultaneous scans

        _isLoading.postValue(true)
        _statusMessage.postValue("Scanning for video files...")

        scope.launch {
            val audioList = loadVideoFilesFromStorage(context)
            PlaylistRepository.setFiles(audioList) // Update the repository

            if (audioList.isEmpty()) {
                _statusMessage.postValue("No video files found. Ensure you have MP4s in your local videos folder.")
            } else {
                // The filteredList observer handles the UI update
                _statusMessage.postValue("Loaded ${audioList.size} videos.")
            }

            _isLoading.postValue(false)
        }
    }

    private fun loadVideoFilesFromStorage(context: Context): List<VideoFile> {
        val files = mutableListOf<VideoFile>()

        // QUERY VIDEO URI
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.RESOLUTION, // specific to video
            MediaStore.Video.Media.DATE_ADDED
        )

        // Select only videos > 0 duration
        val selection = "${MediaStore.Video.Media.DURATION} > 0"

        context.contentResolver.query(uri, projection, selection, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE))
                val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                val resolution = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RESOLUTION))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))

                val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

                files.add(VideoFile(id, contentUri, title, duration, size, resolution, dateAdded))
            }
        }
        return files
    }

    /**
     * Applies the current search query and the current sort state to the full list.
     */
    fun applySortAndFilter() {
        val sortedList = applySort(musicListFull, _sortState.value!!)
        val filteredList = applyFilter(sortedList, currentQuery)
        _filteredList.value = filteredList
    }

    /**
     * Updates the current search query and applies sort/filter.
     */
    fun filterList(query: String) {
        currentQuery = query
        applySortAndFilter()
    }

    /**
     * Updates the sort state and reapplies sort/filter.
     */
    fun setSortState(newSortState: SortState) {
        _sortState.value = newSortState
        applySortAndFilter()
    }

    /**
     * Toggles the ascending/descending state and reapplies sort/filter.
     */
    fun toggleSortDirection() {
        val current = _sortState.value ?: SortState(SortBy.DATE, false)
        val newDirection = !current.ascending
        _sortState.value = current.copy(ascending = newDirection)
        applySortAndFilter()
    }

    private fun applyFilter(list: List<VideoFile>, query: String): List<VideoFile> {
        val lowerCaseQuery = query.lowercase()
        return if (lowerCaseQuery.isBlank()) {
            list
        } else {
            list.filter {
                it.title.lowercase().contains(lowerCaseQuery)
            }
        }
    }

    private fun applySort(list: List<VideoFile>, state: SortState): List<VideoFile> {
        val comparator: Comparator<VideoFile> = when (state.by) {
            SortBy.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortBy.DURATION -> compareBy { it.duration }
            // TODO add size
            SortBy.DATE -> compareBy { it.dateAdded ?: 0L }
        }

        val sortedList = list.sortedWith(comparator)

        // Apply the direction based on the current state
        return if (state.ascending) sortedList else sortedList.reversed()
    }

    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }
}


// recyclerview adapter to display the list of files
class MusicAdapter(private val activity: MainActivity, private var musicList: List<VideoFile>, private val editListener: MusicEditListener) :
    RecyclerView.Adapter<MusicAdapter.MusicViewHolder>() {

    // NEW: Property to track which item is currently in edit mode.
    private var editingPosition: Int = RecyclerView.NO_POSITION

    fun setEditingPosition(newPosition: Int) {
        val oldPosition = editingPosition
        editingPosition = newPosition
        // We only notify if the new position is different or if the old position was valid.
        if (oldPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(oldPosition)
        }
        if (newPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(newPosition)
        }
    }

    // NEW: Get the currently editing position
    fun getEditingPosition(): Int = editingPosition

    inner class MusicViewHolder(private val binding: ItemVideoFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(file: VideoFile, index: Int) {
            val isEditing = adapterPosition == editingPosition

            // alternate background colors
            if (index % 2 == 0) {
                binding.itemCard.setCardBackgroundColor(ContextCompat.getColor(binding.itemCard.context,
                    R.color.light_gray));
            } else {
                binding.itemCard.setCardBackgroundColor(ContextCompat.getColor(binding.itemCard.context,
                    R.color.white));
                binding.textTitle.alpha = 0.95f;
                binding.textArtist.alpha = 0.95f;
            }

            val fullTitleText = file.title
            val fullArtistText = "${file.size}"

            com.bumptech.glide.Glide.with(itemView.context)
                .load(file.uri) // Just pass the video content URI
                .placeholder(android.R.drawable.ic_menu_gallery) // Use a generic placeholder
                .centerCrop()
                .into(binding.imageAlbumArt)

            // Update Text
            binding.textTitle.text = file.title
            binding.textArtist.text = file.resolution ?: "Unknown Res"


            // 3. Click Listeners
            binding.root.setOnClickListener {
                if (!isEditing) {
                    // Normal playback behavior
                    activity.startVideoEditor(file, adapterPosition)
                }
                // If editing, clicking the root view does nothing, as the user must save or exit.
            }

            /* TODO Long click listener to enter edit mode
            binding.root.setOnLongClickListener {
                if (editingPosition != RecyclerView.NO_POSITION) {
                    // Already editing something, exit current edit before starting a new one
                    editListener.saveEditAndExit(musicList[editingPosition], "", "") // Pass empty strings, indicating an unsaved exit
                }
                editListener.startEditing(adapterPosition)
                true // Consume the long click event
            }

             */

            // NEW: Save button click listener
            binding.buttonSaveEdit.setOnClickListener {
                val newTitle = binding.editTextTitle.text.toString().trim()
                val newArtist = binding.editTextArtist.text.toString().trim()
                editListener.saveEditAndExit(file, newTitle, newArtist)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
        val binding = ItemVideoFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MusicViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
        holder.bind(musicList[position], position)
    }

    override fun getItemCount(): Int = musicList.size

    fun updateList(newList: List<VideoFile>) {
        // Update the displayed list from the ViewModel's observer
        musicList = newList
        notifyDataSetChanged()
    }

    // Kept for startMusicPlayback but now unnecessary if we use the Repository directly
    fun getCurrentList(): List<VideoFile> = musicList

}

// 4. Main Activity with Permission and Scanning Logic
class MainActivity : AppCompatActivity(), CoroutineScope, SearchView.OnQueryTextListener, MusicEditListener {

    // Coroutine setup
    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var binding: MainActivityBinding
    private lateinit var viewModel: MusicViewModel // New ViewModel instance

    // Adapter needs access to the activity, so it must be initialized later
    public lateinit var musicAdapter: MusicAdapter
    private lateinit var sortButton: ImageButton // Reference to the new sort criterion button
    private lateinit var sortDirectionButton: ImageButton // Reference to the new sort direction button
    private lateinit var backButton: ImageButton // NEW: Reference to the new back button

    // REMOVED: currentlyEditingItem is no longer needed since we are not using dispatchTouchEvent

    // Determine the correct permission based on Android version
    private val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    // For API 33+ (Android 13) we need to request POST_NOTIFICATIONS
    private val notificationPermission = Manifest.permission.POST_NOTIFICATIONS

    // Register the permission request contract for storage permission
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Storage granted, now check notification permission (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission()
            } else {
                viewModel.loadVideoFiles(applicationContext) // Call ViewModel to scan files
            }
        } else {
            showStatus("Storage permission denied. Cannot scan local storage.")
        }
    }

    // Register the permission request contract for notification permission
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            println("Notification permission granted.")
        } else {
            println("Notification permission denied. Media controls won't be visible in status bar.")
        }
        // Always proceed to scan regardless of notification permission outcome
        viewModel.loadVideoFiles(applicationContext) // Call ViewModel to scan files
    }

    // NEW: Activity Result Launcher for MediaStore Write Request (API 30+)
    private val requestWritePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Permission granted, now save the data (which is stored in temp properties)
            executePendingMetadataUpdate()
        } else {
            Toast.makeText(this, "Permission to modify file denied. Exiting editor.", Toast.LENGTH_SHORT).show()
            // Exit editing mode gracefully without saving
            exitEditingMode()
        }
    }

    // NEW: Temporary storage for the data to be saved after permission is granted
    private var pendingUpdateFile: VideoFile? = null
    private var pendingUpdateTitle: String? = null
    private var pendingUpdateArtist: String? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize ViewModel
        viewModel = ViewModelProvider(this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application))
            .get(MusicViewModel::class.java)

        // NEW: Initialize the sort buttons from the toolbar's ConstraintLayout
        val toolbarLayout = binding.toolbarSearch.getChildAt(0) as? ViewGroup
        sortButton = toolbarLayout?.findViewById(R.id.button_sort) ?: throw IllegalStateException("Sort button not found in toolbar layout")
        // NEW: Initialize the direction button
        sortDirectionButton = toolbarLayout.findViewById(R.id.button_sort_direction) ?: throw IllegalStateException("Sort direction button not found in toolbar layout")
        // NEW: Initialize the back button
        backButton = toolbarLayout.findViewById(R.id.button_back_edit) ?: throw IllegalStateException("Back button not found in toolbar layout")


        setupRecyclerView()
        setupSearchView()
        setupSortButton() // Setup the sort criterion button logic
        setupSortDirectionButton() // NEW: Setup the sort direction toggle logic
        setupBackButton() // NEW: Setup the back button logic for edit mode
        setupSystemBackPressHandler() // NEW: Setup system back press handler
        setupSwipeRefresh()
        setupObservers()
        checkPermissions()
    }

    // REMOVED: isTouchInsideView and dispatchTouchEvent logic

    // NEW: Function to setup the back button (in-toolbar) logic
    private fun setupBackButton() {
        backButton.setOnClickListener {
            // On back click, treat it as an implicit cancel/exit attempt, saving if changes were made.
            handleExitEditMode()
        }
    }

    // NEW: Function to handle system back presses
    private fun setupSystemBackPressHandler() {
        onBackPressedDispatcher.addCallback(this) {
            if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) {
                // We are in edit mode, handle the back press by saving/exiting
                handleExitEditMode()
            } else {
                // Not in edit mode, proceed with default back behavior (closing app/activity)
                isEnabled = false // Disable this callback temporarily
                onBackPressedDispatcher.onBackPressed() // Call system back
                isEnabled = true // Re-enable for future use
            }
        }
    }

    // NEW: Unified function to trigger the save/exit logic
    private fun handleExitEditMode() {
        val position = musicAdapter.getEditingPosition()
        if (position != RecyclerView.NO_POSITION) {
            // Get data from the view holder that is currently being edited
            val viewHolder = binding.recyclerViewMusic.findViewHolderForAdapterPosition(position) as? MusicAdapter.MusicViewHolder
            val file = musicAdapter.getCurrentList().getOrNull(position)
            val titleEditText = viewHolder?.itemView?.findViewById<EditText>(R.id.edit_text_title)
            val artistEditText = viewHolder?.itemView?.findViewById<EditText>(R.id.edit_text_artist)

            if (file != null && titleEditText != null && artistEditText != null) {
                // Pass the current EditText values to the save logic
                saveEditAndExit(file, titleEditText.text.toString().trim(), artistEditText.text.toString().trim())
            } else {
                // If we can't get the data (e.g., view recycled), just exit editing mode
                exitEditingMode()
            }
        }
    }


    // NEW: Function to setup the sort direction toggle button
    private fun setupSortDirectionButton() {
        sortDirectionButton.setOnClickListener {
            // Toggle the direction state in the ViewModel
            viewModel.toggleSortDirection()
        }
    }

    // UPDATED: Function to setup the sort criterion button and its menu
    private fun setupSortButton() {
        sortButton.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.apply {
                // Add sorting criteria (without direction toggles)
                add(0, SortBy.DATE.ordinal, 0, "Last Modified (Default)")
                add(0, SortBy.TITLE.ordinal, 1, "Title")
                add(0, SortBy.DURATION.ordinal, 2, "Duration")
            }

            popup.setOnMenuItemClickListener { item: MenuItem ->
                val currentSortState = viewModel.sortState.value ?: SortState(SortBy.DATE, false)
                val sortCriterion = SortBy.entries.find { it.ordinal == item.itemId }

                if (sortCriterion != null) {
                    // Criterion change logic:
                    // Only change the criterion. Keep the existing direction.
                    // If the criterion is the same, toggle the direction automatically as a shortcut.
                    val newAscending = if (sortCriterion == currentSortState.by) {
                        !currentSortState.ascending // Toggle direction if same is clicked
                    } else {
                        // Keep current direction if new criterion is selected, or use a default if desired
                        currentSortState.ascending
                    }

                    viewModel.setSortState(SortState(sortCriterion, newAscending))
                    true
                } else {
                    false
                }
            }
            popup.show()
        }

        // Observe the sort state to dynamically update the direction button icon
        viewModel.sortState.observe(this) { state ->
            val iconResId = if (state.ascending) {
                // Assuming R.drawable.ascending_24px exists
                R.drawable.ascending_24px
            } else {
                // Assuming R.drawable.descending_24px exists
                R.drawable.descending_24px
            }
            sortDirectionButton.setImageResource(iconResId)
        }
    }

    // NEW: Override onResume to ensure focus/keyboard are hidden when returning
    override fun onResume() {
        super.onResume()
        // Ensure the keyboard is hidden and focus is cleared when returning to the activity
        hideKeyboardAndClearFocus()
        // Ensure editing mode is reset if activity was paused during edit
        if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) {
            // Call exitEditingMode() without save logic if resumed from pause
            exitEditingMode()
        }
    }

    private fun setupObservers() {
        // Observe the filtered list and update the adapter
        viewModel.filteredList.observe(this) { audioList ->
            if (audioList.isNotEmpty()) {
                musicAdapter.updateList(audioList)
                binding.recyclerViewMusic.visibility = View.VISIBLE
                binding.textStatus.visibility = View.GONE
            } else if (PlaylistRepository.getFullPlaylist().isNotEmpty()) {
                // List is filtered to empty, but the full list exists
                musicAdapter.updateList(emptyList())
                showStatus("No tracks match your search.")
            }
        }

        // Observe status messages (e.g., scanning, permission denied)
        viewModel.statusMessage.observe(this) { message ->
            // Update status UI if needed
            if (!message.contains("Loaded")) {
                showStatus(message)
            }
        }

        // NEW: Observe loading state to control the refresh indicator
        viewModel.isLoading.observe(this) { isLoading ->
            // Assuming your layout binding has a property named `swipeRefreshLayout`
            binding.swipeRefreshLayout.isRefreshing = isLoading
        }

        // Observe status messages (e.g., scanning, permission denied)
        viewModel.statusMessage.observe(this) { message ->
            // Update status UI if needed
            if (!message.contains("Loaded")) {
                showStatus(message)
            }
        }
    }

    private fun setupRecyclerView() {
        // Pass 'this' as the MusicEditListener
        musicAdapter = MusicAdapter(this, emptyList(), this)
        binding.recyclerViewMusic.adapter = musicAdapter
    }

    private fun setupSearchView() {
        // Set up the SearchView listener
        binding.searchViewMusic.setOnQueryTextListener(this)
    }

    private fun setupSwipeRefresh() {
        // Assuming your layout binding has a property named `swipeRefreshLayout`
        binding.swipeRefreshLayout.setOnRefreshListener {
            // 1. Clear any existing list display (optional, but good for UX)
            musicAdapter.updateList(emptyList())
            exitEditingMode() // Always exit edit mode on refresh

            // 2. Trigger the scan to reload all data
            viewModel.loadVideoFiles(applicationContext)
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, mediaPermission) == PackageManager.PERMISSION_GRANTED) {
            // Storage permission granted. Check notification permission next.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotificationPermission()
            } else {
                viewModel.loadVideoFiles(applicationContext)
            }
        } else {
            // Request storage permission
            requestStoragePermissionLauncher.launch(mediaPermission)
        }
    }

    private fun requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, notificationPermission) != PackageManager.PERMISSION_GRANTED) {
            // Request notification permission
            requestNotificationPermissionLauncher.launch(notificationPermission)
        } else {
            viewModel.loadVideoFiles(applicationContext)
        }
    }

    // UPDATED: Launches the EditorActivity with the selected video
    fun startVideoEditor(file: VideoFile, filteredIndex: Int) {
        // Exit editing mode if currently active (renaming mode)
        if (musicAdapter.getEditingPosition() != RecyclerView.NO_POSITION) {
            exitEditingMode()
            return
        }

        // Hide keyboard just in case
        hideKeyboardAndClearFocus()

        // Create Intent to launch EditorActivity
        val intent = Intent(this, EditorActivity::class.java).apply {
            // VideoFile is Parcelable, so we can pass it directly
            putExtra("EXTRA_VIDEO_FILE", file)
        }
        startActivity(intent)
    }

    // --- MusicEditListener Implementation ---

    override fun startEditing(position: Int) {
        val viewHolder = binding.recyclerViewMusic.findViewHolderForAdapterPosition(position)
        if (viewHolder != null) {

            // 1. Hide search and sort controls, show back button and title
            binding.searchViewMusic.visibility = View.GONE
            binding.buttonSort.visibility = View.GONE
            binding.buttonSortDirection.visibility = View.GONE
            binding.buttonBackEdit.visibility = View.VISIBLE
            binding.textEditTitle.visibility = View.VISIBLE

            // 2. Disable search view interaction
            binding.searchViewMusic.isEnabled = false
            binding.searchViewMusic.clearFocus()

            // 3. Set editing position in adapter
            musicAdapter.setEditingPosition(position)

            // 4. Give focus to the EditText
            val editText = viewHolder.itemView.findViewById<EditText>(R.id.edit_text_title)
            editText?.requestFocus()

            // Show toast message for guidance
            Toast.makeText(this, "Editing: Click save or back to finish.", Toast.LENGTH_LONG).show()
        }
    }

    override fun saveEditAndExit(videoFile: VideoFile, newTitle: String, newArtist: String) {
        val oldTitle = videoFile.title
        val oldArtist = ""

        // 1. Check if anything was actually modified
        if (newTitle == oldTitle && newArtist == oldArtist) {
            Toast.makeText(this, "No changes detected. Exiting editor.", Toast.LENGTH_SHORT).show()
            exitEditingMode()
            return
        }

        // 2. Store the pending update
        pendingUpdateFile = videoFile
        pendingUpdateTitle = newTitle
        pendingUpdateArtist = newArtist

        // 3. Request write permission for the specific file(s)
        requestMetadataWritePermission(listOf(videoFile.uri))
    }

    // --- Metadata Write Logic ---

    private fun requestMetadataWritePermission(uris: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use MediaStore.createWriteRequest for API 30+
            val pendingIntent = MediaStore.createWriteRequest(contentResolver, uris)
            val intentSenderRequest = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            requestWritePermissionLauncher.launch(intentSenderRequest)
        } else {
            // For older APIs (29 and below), WRITE_EXTERNAL_STORAGE is required.
            // Since we rely on the modern MediaStore URI, we assume the user has the proper
            // permission (READ_EXTERNAL_STORAGE) which often implies write access to owned files
            // or we fall back to a less secure general permission request (which the app doesn't have)
            // or simply try to execute the update. We will execute directly, assuming the URI
            // is valid and permission has been handled by the system for the app's files.
            executePendingMetadataUpdate()
        }
    }

    private fun executePendingMetadataUpdate() {
        // Change type to VideoFile
        val file: VideoFile = pendingUpdateFile ?: return
        val newTitle = pendingUpdateTitle ?: return
        // Note: Artist logic is removed for Video

        // Clear pending data immediately
        pendingUpdateFile = null
        pendingUpdateTitle = null
        pendingUpdateArtist = null

        launch(Dispatchers.IO) {
            try {
                // 1. Determine MIME type (Default to video/mp4 if unknown)
                val mimeType = contentResolver.getType(file.uri) ?: "video/mp4"

                // 2. Prepare ContentValues for VIDEO
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.TITLE, newTitle)
                    // Optional: Sync the filename with the title (Uncomment if desired)
                    // put(MediaStore.Video.Media.DISPLAY_NAME, "$newTitle.mp4")

                    // Update modification date
                    put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                }

                Log.d("MainActivity", "Attempting to update Video URI: ${file.uri}")

                // CRITICAL FIX: Keep the delay to ensure permission propagation
                delay(500)

                // 3. Perform the update
                val rowsUpdated = contentResolver.update(file.uri, contentValues, null, null)

                withContext(Dispatchers.Main) {
                    if (rowsUpdated > 0) {
                        Toast.makeText(this@MainActivity, "Video updated successfully!", Toast.LENGTH_SHORT).show()

                        // Update the repository with the new VideoFile object
                        // Ensure your VideoFile data class has a .copy() method
                        val updatedFile = file.copy(title = newTitle)
                        PlaylistRepository.updateFile(updatedFile)
                    } else {
                        Log.e("MainActivity", "Update failed: Rows updated = $rowsUpdated")
                        Toast.makeText(this@MainActivity, "Update failed. Check logs.", Toast.LENGTH_LONG).show()
                    }
                    exitEditingMode()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error updating metadata for ${file.uri}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: Could not save metadata.", Toast.LENGTH_LONG).show()
                    exitEditingMode()
                }
            }
        }
    }

    private fun exitEditingMode() {
        // 1. Hide the keyboard
        hideKeyboardAndClearFocus()

        // 2. Clear the editing state in the adapter
        musicAdapter.setEditingPosition(RecyclerView.NO_POSITION)

        // 3. Restore visibility of search and sort controls
        binding.searchViewMusic.visibility = View.VISIBLE
        binding.buttonSort.visibility = View.VISIBLE
        binding.buttonSortDirection.visibility = View.VISIBLE
        binding.buttonBackEdit.visibility = View.GONE
        binding.textEditTitle.visibility = View.GONE

        // 4. Re-enable the search view interaction
        binding.searchViewMusic.isEnabled = true
    }

    // --- End MusicEditListener Implementation ---

    override fun onQueryTextSubmit(query: String?): Boolean {
        // NEW: Hide the soft keyboard and clear focus on submit
        hideKeyboardAndClearFocus()
        return true
    }

    /**
     * Called when the query text is changed by the user. This is where the filtering happens.
     */
    override fun onQueryTextChange(newText: String?): Boolean {
        // Only process text changes if the search view is visible/enabled (i.e., not in edit mode)
        if (binding.searchViewMusic.isEnabled) {
            // Filter the list using the ViewModel
            viewModel.filterList(newText.orEmpty())
        }
        return true
    }

    private fun showStatus(message: String) {
        binding.recyclerViewMusic.visibility = View.GONE
        binding.textStatus.visibility = View.VISIBLE
        binding.textStatus.text = message
    }

    // NEW: Helper function to hide the keyboard and clear focus
    private fun hideKeyboardAndClearFocus() {
        // 1. Clear focus from the SearchView to hide the cursor
        binding.searchViewMusic.clearFocus()

        // 2. Explicitly hide the keyboard using InputMethodManager
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // Use the currently focused view's window token, or the root view's if none.
        val windowToken = currentFocus?.windowToken ?: binding.root.windowToken
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        // cancel the coroutine job when the activity is destroyed
        job.cancel()
    }

    fun onRateClick(item: MenuItem) {}
    fun onHelpClick(item: MenuItem) {}
    fun showBigFrag(item: MenuItem) {}
    }