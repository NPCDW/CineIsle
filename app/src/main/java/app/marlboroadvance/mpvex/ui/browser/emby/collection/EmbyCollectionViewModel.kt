package app.marlboroadvance.mpvex.ui.browser.emby.collection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.marlboroadvance.mpvex.domain.emby.EmbyItem
import app.marlboroadvance.mpvex.domain.emby.EmbyRepository
import app.marlboroadvance.mpvex.domain.emby.EmbyServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.inject

/** Browse desktop-created collections without any mutation API. */
class EmbyCollectionViewModel(application: Application) : AndroidViewModel(application) {
  private val repository by inject<EmbyRepository>(EmbyRepository::class.java)
  private val _collections = MutableStateFlow<List<EmbyItem>>(emptyList())
  val collections = _collections.asStateFlow()
  private val _listLoading = MutableStateFlow(false)
  val listLoading = _listLoading.asStateFlow()
  private val _items = MutableStateFlow<List<EmbyItem>>(emptyList())
  val items = _items.asStateFlow()
  private val _itemsLoading = MutableStateFlow(false)
  val itemsLoading = _itemsLoading.asStateFlow()
  private val _error = MutableStateFlow<String?>(null)
  val error = _error.asStateFlow()
  val itemCountText = _items.map { "${it.size} 项" }
    .stateIn(viewModelScope, SharingStarted.Eagerly, "0 项")
  private var listGeneration = 0
  private var itemsGeneration = 0
  private var listServer: EmbyServer? = null
  private var itemServer: EmbyServer? = null
  private var loadedCollection: String? = null

  fun loadCollections(server: EmbyServer, force: Boolean = false) {
    if (!force && listServer == server && _collections.value.isNotEmpty()) return
    val generation = ++listGeneration
    if (listServer != server) _collections.value = emptyList()
    listServer = server
    _listLoading.value = true
    _error.value = null
    viewModelScope.launch {
      try {
        val result = repository.getCollections(server)
        if (generation == listGeneration) _collections.value = result.Items
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == listGeneration) _error.value = "读取合集失败，请重试。"
      } finally {
        if (generation == listGeneration) _listLoading.value = false
      }
    }
  }

  fun loadItems(server: EmbyServer, collectionId: String) {
    val generation = ++itemsGeneration
    if (itemServer != server || loadedCollection != collectionId) _items.value = emptyList()
    itemServer = server
    loadedCollection = collectionId
    _itemsLoading.value = true
    _error.value = null
    viewModelScope.launch {
      try {
        val result = repository.getCollectionItems(server, collectionId)
        if (generation == itemsGeneration) _items.value = result.Items
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        if (generation == itemsGeneration) _error.value = "读取合集内容失败，请重试。"
      } finally {
        if (generation == itemsGeneration) _itemsLoading.value = false
      }
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory = viewModelFactory {
      initializer { EmbyCollectionViewModel(application) }
    }
  }
}
