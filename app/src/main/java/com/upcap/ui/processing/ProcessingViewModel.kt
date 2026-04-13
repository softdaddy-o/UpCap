package com.upcap.ui.processing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upcap.model.ProcessingMode
import com.upcap.model.ProcessingState
import com.upcap.service.ProcessingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProcessingViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val state: StateFlow<ProcessingState> = _state

    private var service: ProcessingService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ProcessingService.LocalBinder
            service = localBinder.getService()
            viewModelScope.launch {
                service?.processingState?.collect { state ->
                    _state.value = state
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    fun startProcessing(videoUri: String, mode: ProcessingMode) {
        val uri = Uri.parse(java.net.URLDecoder.decode(videoUri, "UTF-8"))
        val intent = Intent(context, ProcessingService::class.java).apply {
            putExtra(ProcessingService.EXTRA_VIDEO_URI, uri.toString())
            putExtra(ProcessingService.EXTRA_MODE, mode.name)
        }
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        bound = true
    }

    fun cancelProcessing() {
        service?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
    }
}
