import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.*
import com.luislezama.motiondetect.deviceconnection.WearConnectionManager
import com.luislezama.motiondetect.deviceconnection.WearConnectionViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WearDataListener(
    private val context: Context,
    private val wearConnectionManager: WearConnectionManager
) : MessageClient.OnMessageReceivedListener {

    private lateinit var wearConnectionViewModel: WearConnectionViewModel

    fun setViewModel(wearConnectionViewModel: WearConnectionViewModel) {
        this.wearConnectionViewModel = wearConnectionViewModel
    }

    private var sendConfirmationEachNMessages = 0

    private fun processMessage(messageEvent: MessageEvent) {
        val receivedMessage = String(messageEvent.data)
        //Log.d("WearDataListener", "Received message from selected Wear device: $receivedMessage")

        when (messageEvent.path) {
            "/test_connection_response" -> {
                wearConnectionManager.onConnectionResponseReceived()
            }

            "/capture_started" -> {
                wearConnectionViewModel.viewModelScope.launch {
                    wearConnectionViewModel.captureStarted.value = true
                }
            }

            "/sensor_data" -> {
                wearConnectionViewModel.viewModelScope.launch {
                    val samplesPerPacket = wearConnectionViewModel.samplesPerPacket.value ?: 10
                    sendConfirmationEachNMessages = (500 / samplesPerPacket).coerceAtLeast(1)

                    wearConnectionViewModel.recievedSensorMessages.value = (wearConnectionViewModel.recievedSensorMessages.value ?: 0) + 1
                    wearConnectionViewModel.sensorDataString.value = receivedMessage

                    if (wearConnectionViewModel.recievedSensorMessages.value!! % sendConfirmationEachNMessages == 0) { // Reply each N messages
                        wearConnectionManager.wearMessageQueue.sendMessage("/more_sensor_data", "".toByteArray())
                    }
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            val selectedNodeId = wearConnectionManager.getSelectedWearDevice(validate = false) // ID del reloj guardado
            val senderNodeId = messageEvent.sourceNodeId // ID del nodo que envió el mensaje

            if (selectedNodeId == null) {
                Log.d("WearDataListener", "No device selected. Ignoring message from $senderNodeId")
                return@launch
            }

            // Verificar si el nodo que envió el mensaje está en la lista de nodos conectados
            /*val connectedNodes = wearConnectionManager.getConnectedDevices()
            if (connectedNodes.none { it.id == senderNodeId }) {
                Log.d("WearDataListener", "Message ignored. Sender is not a connected device: $senderNodeId")
                return@launch
            }*/

            // Solo procesar el mensaje si proviene del nodo seleccionado
            if (senderNodeId == selectedNodeId) {
                processMessage(messageEvent)
            } else {
                Log.d("WearDataListener", "Message ignored. Sender is not the selected device: $senderNodeId")
            }
        }
    }
}
