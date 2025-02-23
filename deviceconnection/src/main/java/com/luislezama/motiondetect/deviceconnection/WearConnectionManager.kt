package com.luislezama.motiondetect.deviceconnection

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.time.withTimeoutOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis


class WearConnectionManager(val context: Context) {
    val testingTimeout = 10
    private var isWearOS = false
    private var nodeId: String? = null

    val wearMessageQueue: WearMessageQueue = WearMessageQueue(context, nodeId)

    init {
        val packageManager = context.packageManager
        isWearOS = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

        CoroutineScope(Dispatchers.Main).launch {
            nodeId = getSelectedWearDevice()
            wearMessageQueue.nodeId = nodeId
        }
    }

    suspend fun getConnectedDevices(context: Context = this.context): List<Node> {
        try {
            val nodeClient = Wearable.getNodeClient(context)
            return nodeClient.connectedNodes.await()
        } catch (e: Exception) {
            Log.e("WearConnectionManager", "Error getting connected nodes", e)
            return emptyList()
        }
    }

    // Send message to device
    /*suspend fun sendMessage(context: Context, path: String, message: String) {
        try {
            if (isWearOS) {
                val nodeClient = Wearable.getNodeClient(context)
                val nodes = nodeClient.connectedNodes.await()

                for (node in nodes) {
                    val messageClient = Wearable.getMessageClient(context)
                    messageClient.sendMessage(node.id, path, message.toByteArray()).await()
                    Log.d("WearConnectionManager", "Message sent to ${node.displayName}")
                }
            } else {
                if (nodeId == null) {
                    Log.d("WearConnectionManager", "No device selected")
                    return
                } else {
                    val messageClient = Wearable.getMessageClient(context)
                    messageClient.sendMessage(nodeId!!, path, message.toByteArray()).await()
                    Log.d("WearConnectionManager", "Message sent to ${node.displayName}")
                }
            }


            if (nodeId == null) {
                Log.d("WearConnectionManager", "No device selected")
                return
            } else {
                val messageClient = Wearable.getMessageClient(context)
                messageClient.sendMessage(nodeId!!, path, message.toByteArray()).await()
                Log.d("WearConnectionManager", "Message sent to ${/*node.displayName*/ nodeId!!}")
            }
        } catch (e: Exception) {
            Log.e("WearConnectionManager", "Failed to send message", e)
        }
    }*/


    // Test connection
    private var connectionResponse = CompletableDeferred<Boolean>()

    suspend fun testConnection(context: Context): Long {
        if (!isWearOS) {
            connectionResponse = CompletableDeferred()

            val timeTaken = measureTimeMillis {
                wearMessageQueue.sendMessage(
                    "/test_connection",
                    "Hello from mobile!".toByteArray()
                ) // Send message to wear device

                val success = withTimeoutOrNull((testingTimeout * 1000).toLong()) {
                    connectionResponse.await() // Wait for response in "/test_connection_response"
                } ?: false // If timeout, consider it a failure

                if (!success) return -1 // In case of failure, return -1
            }

            return timeTaken // Return the time taken to complete the task
        } else {
            return -1
        }
    }

    fun testConnectionResponse(context: Context) {
        if (isWearOS) {
            //sendMessage(context, "/test_connection_response", "Hello from wear!")
            val coords: DoubleArray = doubleArrayOf(2.3467607,4.299603,2.5082762,1.4981171,0.28894445,-0.7122414)
            var coordsString = coords.joinToString(",")
            coordsString += ",120\n"
            val repeatedString = coordsString.repeat(100)
            wearMessageQueue.sendMessage("/test_connection_response", repeatedString.toByteArray())
        }
    }

    fun onConnectionResponseReceived() {
        if (!connectionResponse.isCompleted) {
            connectionResponse.complete(true) // Resolve the deferred with true
        }
    }



    // Sensor capture
    fun startSensorCapture(samplesPerPacket: Int) {
        if (!isWearOS) {
            wearMessageQueue.sendMessage("/start_capture", samplesPerPacket.toString().toByteArray())
        }
    }

    fun stopSensorCapture() {
        if (!isWearOS) {
            wearMessageQueue.sendMessage("/stop_capture", "".toByteArray())
        }
    }




    // Save selected wear device in shared preferences
    fun saveSelectedWearDevice(context: Context = this.context, nodeId: String, nodeName: String) {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)

        // Delete selection in shared preferences
        if (nodeId === "-1") {
            prefs.edit()
                .remove("selected_wear_node")
                .remove("selected_wear_name")
                .apply()
            return
        }

        // Save selection in shared preferences
        prefs.edit()
            .putString("selected_wear_node", nodeId)
            .putString("selected_wear_name", nodeName)
            .apply()
    }

    // Get selected wear device from shared preferences and check if it's still connected
    suspend fun getSelectedWearDevice(context: Context = this.context, validate: Boolean = true): String? {
        if (isWearOS) {
            val nodes = getConnectedDevices(context) // Get connected devices
            return nodes.firstOrNull()?.id // Return the first connected device ID
        } else {
            val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
            val savedNodeId = prefs.getString("selected_wear_node", null)
            val savedNodeName = prefs.getString("selected_wear_name", null) ?: return null

            if (validate) {
                val nodes = getConnectedDevices(context) // Get connected devices
                for (node in nodes) {
                    if (node.id == savedNodeId) {
                        return savedNodeId // Return saved ID (still the same)
                    }

                    if (node.displayName == savedNodeName) {
                        saveSelectedWearDevice(context, node.id, node.displayName) // Save new ID
                        return node.id
                    }
                }
                return null // No valid selection
            } else {
                return savedNodeId
            }
        }
    }

    fun getSelectedWearDeviceFromSharedPreferences(context: Context = this.context): String? {
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        val savedNodeId = prefs.getString("selected_wear_node", null)
        val savedNodeName = prefs.getString("selected_wear_name", null) ?: return null

        return savedNodeId
    }
}