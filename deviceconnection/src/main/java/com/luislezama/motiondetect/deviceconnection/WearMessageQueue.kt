package com.luislezama.motiondetect.deviceconnection

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*

class WearMessageQueue(private val context: Context, var nodeId: String?) {
    private val messageQueue: Queue<Pair<String, ByteArray>> = LinkedList()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var isSending = false

    fun sendMessage(path: String, data: ByteArray) {
        messageQueue.add(path to data)
        processQueue()
    }

    private fun processQueue() {
        if (isSending) return // Don't do anything if we're already sending

        coroutineScope.launch {
            isSending = true

            while (messageQueue.isNotEmpty()) {
                val (path, data) = messageQueue.poll() ?: continue

                try {
                    if (nodeId == null) {
                        Log.d("WearConnectionManager", "No device selected")
                    } else {
                        val messageClient = Wearable.getMessageClient(context)
                        messageClient.sendMessage(nodeId!!, path, data).await()
                        Log.d("WearConnectionManager", "Message sent to ${/*node.displayName*/ nodeId!!}")
                    }

                    /*val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                    for (node in nodes) {
                        Log.d("WearMessageQueue", "Sending message to ${node.displayName}")
                        Tasks.await(Wearable.getMessageClient(context).sendMessage(node.id, path, data))
                    }*/
                } catch (e: Exception) {
                    Log.e("WearMessageQueue", "Error sending message", e)
                }
            }

            isSending = false
        }
    }
}
