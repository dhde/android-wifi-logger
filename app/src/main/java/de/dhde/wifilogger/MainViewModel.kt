package de.dhde.wifilogger

import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = WifiEventRepository(app)
    val events = repo.allEvents
    fun deleteAll() = viewModelScope.launch { repo.deleteAll() }
    fun deleteOlderThan(days: Int) = viewModelScope.launch { repo.deleteOlderThan(days) }
    suspend fun exportCsv(): String = repo.exportCsv(getApplication())
}
