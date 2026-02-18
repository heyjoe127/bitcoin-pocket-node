package com.pocketnode.ui.mempool

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketnode.mempool.MempoolService
import com.pocketnode.mempool.TransactionSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TransactionSearchViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "TxSearchViewModel"
    }

    private var mempoolService: MempoolService? = null
    private var serviceBound = false

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _searchResult = MutableStateFlow<TransactionSearchUiResult?>(null)
    val searchResult: StateFlow<TransactionSearchUiResult?> = _searchResult.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mempoolService = (service as MempoolService.MempoolBinder).getService()
            serviceBound = true
        }
        override fun onServiceDisconnected(className: ComponentName) {
            mempoolService = null
            serviceBound = false
        }
    }

    init {
        val context = getApplication<Application>()
        context.bindService(Intent(context, MempoolService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onCleared() {
        super.onCleared()
        if (serviceBound) { getApplication<Application>().unbindService(serviceConnection); serviceBound = false }
    }

    fun updateSearchText(text: String) {
        _searchText.value = text.lowercase().replace(Regex("[^a-f0-9]"), "")
    }

    fun searchTransaction() {
        val txid = _searchText.value
        if (txid.length != 64) {
            _searchResult.value = TransactionSearchUiResult.Error("Transaction ID must be 64 characters")
            return
        }

        _isLoading.value = true
        _searchResult.value = null

        viewModelScope.launch {
            try {
                val service = mempoolService
                if (service == null) {
                    _searchResult.value = TransactionSearchUiResult.Error("Mempool service not available")
                    return@launch
                }

                when (val result = service.searchTransaction(txid)) {
                    is TransactionSearchResult.InMempool -> {
                        val timeInMempool = calculateTimeInMempool(result.entry.time)
                        _searchResult.value = TransactionSearchUiResult.Found(
                            TransactionDetails(
                                txid = txid,
                                feeRate = result.entry.fee / result.entry.vsize,
                                vsize = result.entry.vsize,
                                fee = result.entry.fee,
                                timeInMempool = timeInMempool,
                                projectedBlockPosition = result.projectedBlockPosition,
                                isWatched = service.isWatched(txid)
                            )
                        )
                    }
                    is TransactionSearchResult.Confirmed -> {
                        _searchResult.value = TransactionSearchUiResult.Found(
                            TransactionDetails(
                                txid = txid, feeRate = 0.0, vsize = 0, fee = 0.0,
                                timeInMempool = "Confirmed", projectedBlockPosition = null,
                                isWatched = false, confirmations = result.confirmations, blockHeight = null
                            )
                        )
                    }
                    is TransactionSearchResult.NotFound -> _searchResult.value = TransactionSearchUiResult.NotFound
                    is TransactionSearchResult.Error -> _searchResult.value = TransactionSearchUiResult.Error(result.message)
                }
            } catch (e: Exception) {
                _searchResult.value = TransactionSearchUiResult.Error("Search failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun calculateTimeInMempool(unixTime: Long): String {
        val seconds = System.currentTimeMillis() / 1000 - unixTime
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }

    fun watchTransaction() {
        val current = _searchResult.value
        if (current is TransactionSearchUiResult.Found) {
            val txid = current.transaction.txid
            if (current.transaction.isWatched) mempoolService?.unwatchTransaction(txid)
            else mempoolService?.watchTransaction(txid)
            _searchResult.value = current.copy(transaction = current.transaction.copy(isWatched = !current.transaction.isWatched))
        }
    }
}

sealed class TransactionSearchUiResult {
    data class Found(val transaction: TransactionDetails) : TransactionSearchUiResult()
    object NotFound : TransactionSearchUiResult()
    data class Error(val message: String) : TransactionSearchUiResult()
}

data class TransactionDetails(
    val txid: String, val feeRate: Double, val vsize: Int, val fee: Double,
    val timeInMempool: String, val projectedBlockPosition: Int?,
    val isWatched: Boolean = false, val confirmations: Int = 0, val blockHeight: Int? = null
)
