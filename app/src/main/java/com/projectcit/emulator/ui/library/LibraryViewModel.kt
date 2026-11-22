package com.projectcit.emulator.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.projectcit.storage.LibraryState
import com.projectcit.storage.RomLibraryRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(private val repo: RomLibraryRepository) : ViewModel() {
    val state: StateFlow<LibraryState> = repo.state

    fun onFolderPicked(uri: Uri) { viewModelScope.launch { repo.setRoot(uri) } }
    fun rescan() { viewModelScope.launch { repo.rescan() } }
}
