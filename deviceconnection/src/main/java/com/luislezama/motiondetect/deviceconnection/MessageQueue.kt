package com.luislezama.motiondetect.deviceconnection

import android.util.Log
import com.google.android.gms.wearable.MessageClient
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*

class MessageQueue(private val messageClient: MessageClient, private var node: PseudoNode?) {
    private val messageQueue: Queue<Pair<String, ByteArray>> = LinkedList()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var isSending = false

    fun sendMessage(path: String, data: ByteArray) {
        messageQueue.add(path to data)
        processQueue()
    }

    fun sendMessage(path: String, data: String) {
        sendMessage(path, data.toByteArray())
    }

    fun sendMessage(path: String, data: Int) {
        sendMessage(path, data.toString().toByteArray())
    }

    fun sendMessage(path: String) {
        sendMessage(path, "".toByteArray())
    }

    private fun processQueue() {
        if (isSending) return // Don't do anything if we're already sending

        coroutineScope.launch {
            isSending = true

            while (messageQueue.isNotEmpty()) {
                val (path, data) = messageQueue.poll() ?: continue

                try {
                    if (node == null) {
                        Log.d("WearConnectionManager", "No device selected")
                    } else {
                        messageClient.sendMessage(node!!.id, path, data).await()
                        Log.d("WearConnectionManager", "Message sent to ${node!!.displayName}")
                    }
                } catch (e: Exception) {
                    Log.e("WearMessageQueue", "Error sending message", e)
                }
            }

            isSending = false
        }
    }

    fun setNode(node: PseudoNode?) {
        this.node = node
    }
}
