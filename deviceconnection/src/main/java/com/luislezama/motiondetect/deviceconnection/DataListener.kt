package com.luislezama.motiondetect.deviceconnection

import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent

class DataListener(val connectionManager: ConnectionManager) : MessageClient.OnMessageReceivedListener {
    private var callbacks = mutableMapOf<String, (String?) -> Unit>()

    fun registerCallback(path: String, callback: (String?) -> Unit) : DataListener {
        callbacks[path] = callback
        return this
    }

    fun registerCallbacks(callbacks: Map<String, (String?) -> Unit>) : DataListener {
        this.callbacks.putAll(callbacks)
        return this
    }

    override fun onMessageReceived(p0: MessageEvent) {
        if (connectionManager.connectedNode == null) return

        val path = p0.path
        val data = String(p0.data)

        callbacks[path]?.invoke(data)
    }
}