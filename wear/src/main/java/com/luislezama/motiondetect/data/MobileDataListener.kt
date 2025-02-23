package com.luislezama.motiondetect.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.luislezama.motiondetect.deviceconnection.WearConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MobileDataListener (
    private val context: Context,
    private val wearConnectionManager: WearConnectionManager
) : MessageClient.OnMessageReceivedListener {

    private fun processMessage(messageEvent: MessageEvent) {
        val receivedMessage = String(messageEvent.data)
        Log.d("WearDataListener", "Received message from mobile device: $receivedMessage")

        val shouldProcessMessage = (ForegroundServiceHolder.service?.getServiceStatus() in listOf(WearForegroundServiceStatus.LISTENING, WearForegroundServiceStatus.RUNNING))
        if (!shouldProcessMessage) {
            Log.d("MobileDataListener", "Ignoring message because service is not running or listening.")
            return
        }

        when (messageEvent.path) {
            "/test_connection" -> {
                Log.d("MobileDataListener", "TESTING CONNECTION")
                CoroutineScope(Dispatchers.IO).launch {
                    wearConnectionManager.testConnectionResponse(context)
                }
            }

            "/start_capture" -> {
                val samplesPerPacket = String(messageEvent.data).toInt()
                Log.d(
                    "MobileDataListener",
                    "Starting sensor capture with $samplesPerPacket samples per packet"
                )
                wearConnectionManager.wearMessageQueue.sendMessage("/capture_started", "".toByteArray())

                ForegroundServiceHolder.service?.startCapturingSensors(samplesPerPacket)
            }

            "/more_sensor_data" -> {
                Log.d("MobileDataListener", "Mobile confirmed it wants more sensor data")
                ForegroundServiceHolder.service?.resetLastRequestTime()
            }

            "/stop_capture" -> {
                Log.d("MobileDataListener", "Stopping sensor capture")

                ForegroundServiceHolder.service?.stopCapturingSensors()
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        processMessage(messageEvent)
    }

    /*override fun onMessageReceived(messageEvent: MessageEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            val selectedNodeId = wearConnectionManager.getSelectedWearDevice() // ID del reloj guardado
            val senderNodeId = messageEvent.sourceNodeId // ID del nodo que envió el mensaje

            if (selectedNodeId == null) {
                Log.d("WearDataListener", "No device selected. Ignoring message from $senderNodeId")
                return@launch
            }

            // Verificar si el nodo que envió el mensaje está en la lista de nodos conectados
            val connectedNodes = wearConnectionManager.getConnectedDevices()
            if (connectedNodes.none { it.id == senderNodeId }) {
                Log.d("WearDataListener", "Message ignored. Sender is not a connected device: $senderNodeId")
                return@launch
            }

            // Solo procesar el mensaje si proviene del nodo seleccionado
            if (senderNodeId == selectedNodeId && messageEvent.path == "/test_connection") {
                val receivedMessage = String(messageEvent.data)
                Log.d("WearDataListener", "Received message from selected Wear device: $receivedMessage")

                CoroutineScope(Dispatchers.Main).launch {
                    Toast.makeText(context, "Message from Wear: $receivedMessage", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }*/
}
