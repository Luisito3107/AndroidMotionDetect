package com.luislezama.motiondetect.deviceconnection

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
import kotlin.system.measureTimeMillis


object ConnectionManager {
    const val TESTING_TIMEOUT = 10 // seconds

    lateinit var applicationContext: Context
    var isWearOS = false
    private var sharedPreferences: SharedPreferences? = null

    @SuppressLint("StaticFieldLeak")
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
    private var connectionResponse: CompletableDeferred<Boolean>? = null

    private val listener = MessageClient.OnMessageReceivedListener { messageEvent ->
        if (messageEvent.path == "/test_connection_response") {
            connectionResponse?.complete(true)
        }
    }

    suspend fun testConnection(): Long {
        if (!isWearOS && connectedNode != null && !isTestingConnection()) {
            connectionResponse = CompletableDeferred()

            Wearable.getMessageClient(applicationContext).addListener(listener)

            val timeTaken = measureTimeMillis {
                messageQueue.sendMessage("/test_connection") // Send message to wear device

                val success = withTimeoutOrNull((TESTING_TIMEOUT * 1000).toLong()) {
                    connectionResponse!!.await() // Wait for response in "/test_connection_response"
                } ?: false // If timeout, consider it a failure

                Wearable.getMessageClient(applicationContext).removeListener(listener)
                connectionResponse = null // Reset the deferred

                if (!success) return -1 // In case of failure, return -1
            }

            return timeTaken // Return the time taken to complete the task
        } else {
            return -1
        }
    }

    fun testConnectionResponse() {
        if (isWearOS && connectedNode != null) {
            val coords: DoubleArray = doubleArrayOf(2.3467607,4.299603,2.5082762,1.4981171,0.28894445,-0.7122414)
            var coordsString = coords.joinToString(",")
            val repeatedString = coordsString.repeat(200)
            messageQueue.sendMessage("/test_connection_response", repeatedString.toByteArray())
        }
    }

    fun isTestingConnection(): Boolean {
        return connectionResponse != null
    }

    fun cancelTestConnection() {
        if (connectionResponse != null && connectionResponse?.isCompleted != true) {
            connectionResponse!!.completeExceptionally(CancellationException("Connection test cancelled"))
            connectionResponse = null
        }
        Wearable.getMessageClient(applicationContext).removeListener(listener)
    }








    // Save selected wear device in shared preferences
    fun saveSelectedWearDevice(node: PseudoNode) {
        sharedPreferences?.edit()
            ?.putString("selected_wear_node", node.toString())
            ?.apply()

        CoroutineScope(Dispatchers.IO).launch {
            resetConnectedNode()
        }
    }

    // Clear selected wear device in shared preferences
    fun clearSelectedWearDevice() {
        sharedPreferences?.edit()
            ?.remove("selected_wear_node")
            ?.apply()

        CoroutineScope(Dispatchers.IO).launch {
            resetConnectedNode()
        }
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

    // Reset connected node
    suspend fun resetConnectedNode() {
        connectedNode = getSelectedNode(true)
        messageQueue.setNode(connectedNode)
    }

    // Refresh connected node
    fun refreshConnectedNode() {
        CoroutineScope(Dispatchers.IO).launch {
            resetConnectedNode()
        }
    }
}