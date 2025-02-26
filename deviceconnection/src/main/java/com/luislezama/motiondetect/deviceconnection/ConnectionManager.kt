package com.luislezama.motiondetect.deviceconnection

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.measureTimeMillis


object ConnectionManager {
    const val TESTING_TIMEOUT = 10 // seconds

    lateinit var applicationContext: Context
    private var isWearOS = false
    private var sharedPreferences: SharedPreferences? = null
    private var nodeClient: NodeClient? = null

    lateinit var messageQueue: MessageQueue
    var connectedNode: PseudoNode? = null

    fun initialize(applicationContext: Context) {
        this.applicationContext = applicationContext
        isWearOS = this.applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
        sharedPreferences = this.applicationContext.getSharedPreferences("motiondetect_prefs", Context.MODE_PRIVATE)
        nodeClient = Wearable.getNodeClient(this.applicationContext)
        messageQueue = MessageQueue(Wearable.getMessageClient(this.applicationContext), null)

        CoroutineScope(Dispatchers.IO).launch {
            resetConnectedNode()
        }
    }



    // Test connection
    private var connectionResponse = CompletableDeferred<Boolean>()

    suspend fun testConnection(): Long {
        if (!isWearOS && messageQueue != null) {
            connectionResponse = CompletableDeferred()

            val timeTaken = measureTimeMillis {
                messageQueue!!.sendMessage("/test_connection") // Send message to wear device

                val success = withTimeoutOrNull((TESTING_TIMEOUT * 1000).toLong()) {
                    connectionResponse.await() // Wait for response in "/test_connection_response"
                } ?: false // If timeout, consider it a failure

                if (!success) return -1 // In case of failure, return -1
            }

            return timeTaken // Return the time taken to complete the task
        } else {
            return -1
        }
    }

    fun testConnectionResponse() {
        if (isWearOS && messageQueue != null) {
            val coords: DoubleArray = doubleArrayOf(2.3467607,4.299603,2.5082762,1.4981171,0.28894445,-0.7122414)
            var coordsString = coords.joinToString(",")
            val repeatedString = coordsString.repeat(200)
            messageQueue!!.sendMessage("/test_connection_response", repeatedString.toByteArray())
        }
    }

    fun onConnectionResponseReceived() {
        if (!isWearOS) {
            if (!connectionResponse.isCompleted) {
                connectionResponse.complete(true) // Resolve the deferred with true
            }
        }
    }




    // Sensor capture
    fun startSensorCapture(samplesPerPacket: Int) {
        if (!isWearOS && messageQueue != null) {
            messageQueue!!.sendMessage("/start_capture", samplesPerPacket)
        }
    }

    fun confirmSensorCaptureStarted() {
        if (!isWearOS && messageQueue != null) {
            messageQueue!!.sendMessage("/sensor_capture_started")
        }
    }

    fun confirmMoreSensorDataNeeded() {
        if (!isWearOS && messageQueue != null) {
            messageQueue!!.sendMessage("/more_sensor_data")
        }
    }

    fun stopSensorCapture() {
        if (!isWearOS && messageQueue != null) {
            messageQueue!!.sendMessage("/stop_capture")
        }
    }

    fun sendSensorData(data: String) {
        if (isWearOS && messageQueue != null) {
            messageQueue!!.sendMessage("/sensor_data", data.toByteArray())
        }
    }




    // Save selected wear device in shared preferences
    fun saveSelectedWearDevice(node: PseudoNode) {
        sharedPreferences?.edit()
            ?.putString("selected_wear_node", node.toString())
            ?.apply()
    }

    // Clear selected wear device in shared preferences
    fun clearSelectedWearDevice() {
        sharedPreferences?.edit()
            ?.remove("selected_wear_node")
            ?.apply()
    }

    // Get selected wear device from shared preferences and check if it's still connected
    suspend fun getSelectedNode(checkConnection: Boolean = true): PseudoNode? {
        if (isWearOS) {
            val nodes = getAllConnectedNodes() // Get connected devices
            return nodes.firstOrNull() // Return the first connected device
        } else {
            val savedNode = getSelectedNodeFromSharedPreferences() // Get saved node

            if (savedNode == null) return null // No saved node
            else {
                if (checkConnection) {
                    val nodes = getAllConnectedNodes() // Get connected devices
                    for (node in nodes) {
                        if (node.id == savedNode.id) {
                            return savedNode // Return saved node if it's still connected
                        }

                        if (node.displayName == savedNode.displayName) {
                            val newSavedNode = savedNode.updateId(node.id)
                            saveSelectedWearDevice(newSavedNode) // Save new ID
                            return newSavedNode
                        }
                    }
                    return null // Selected device is not connected
                } else {
                    return savedNode
                }
            }
        }
    }

    private fun getSelectedNodeFromSharedPreferences(): PseudoNode? {
        val savedNodeJson = sharedPreferences?.getString("selected_wear_node", null) ?: ""
        val savedNode = PseudoNode.fromJson(savedNodeJson)

        return savedNode
    }

    suspend fun getAllConnectedNodes(): List<PseudoNode> {
        try {
            val nodeList = nodeClient?.connectedNodes?.await()
            val pseudoNodeList = nodeList?.map { node -> PseudoNode.fromNode(node) }
            return pseudoNodeList ?: emptyList()
        } catch (e: Exception) {
            Log.e("WearConnectionManager", "Error getting connected nodes", e)
            return emptyList()
        }
    }

    suspend fun resetConnectedNode() {
        connectedNode = getSelectedNode(true)
        if (connectedNode != null) {
            messageQueue.setNode(connectedNode!!)
        }
    }
}