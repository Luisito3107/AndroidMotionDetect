package com.luislezama.motiondetect.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.luislezama.motiondetect.R
import com.luislezama.motiondetect.data.HandOption
import com.luislezama.motiondetect.data.RecognitionForegroundService
import com.luislezama.motiondetect.data.RecognitionForegroundServiceHolder
import com.luislezama.motiondetect.data.RecognitionViewModel
import com.luislezama.motiondetect.deviceconnection.ConnectionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class RecognitionFragment : Fragment() {
    private lateinit var viewModel: RecognitionViewModel

    // UI elements
    private lateinit var root: View
    private lateinit var recognitionDelayedStartLayout: LinearLayout
    private lateinit var recognitionDelayedStartCounter: TextView
    private lateinit var recognitionStatusInfoLayout: LinearLayout
    private lateinit var recognitionStatusIcon: ImageView
    private lateinit var recognitionStatusText: TextView
    private lateinit var userHandEditText: AutoCompleteTextView
    private lateinit var samplesPerPacketSlider: Slider
    private lateinit var samplesPerPacketHint: TextView
    private lateinit var delayedStartSlider: Slider
    private lateinit var delayedStartHint: TextView
    private lateinit var toggleBtn: FloatingActionButton


    private var serviceStopReason: RecognitionForegroundService.ServiceStopReason? = null
    //private var serviceStoppedAfterSampleCount: Int = 0
    private val localBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action.let {
                when (it) {
                    "RECOGNITION_SERVICE_STATUS_CHANGED" -> {
                        val status = intent!!.getSerializableExtra("SERVICE_STATUS", RecognitionForegroundService.ServiceStatus::class.java)
                        if (status != null) {
                            //serviceStoppedAfterSampleCount = intent.getIntExtra("TOTAL_SENSOR_SAMPLES_CAPTURED", 0)
                            serviceStopReason = intent.getSerializableExtra("SERVICE_STOP_REASON", RecognitionForegroundService.ServiceStopReason::class.java)
                            viewModel.serviceStatus.value = status
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val recognitionLayout = inflater.inflate(R.layout.fragment_recognition, container, false)

        viewModel = ViewModelProvider(this)[RecognitionViewModel::class.java]

        initializeUI(recognitionLayout)

        // Register for service status changes
        val statusChangedFilter = IntentFilter(RecognitionForegroundService.ACTION_SERVICE_STATUS_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(localBroadcastReceiver, statusChangedFilter)

        return recognitionLayout
    }

    override fun onDestroyView() {
        super.onDestroyView()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(localBroadcastReceiver)
    }

    override fun onResume() {
        super.onResume()

        setupUserHandSelector()

        // Try to get service status from service
        if (::viewModel.isInitialized) {
            viewModel.serviceStatus.value = RecognitionForegroundService.getServiceStatus()
        }

        // Refresh Wear device selection in ConnectionManager
        ConnectionManager.refreshConnectedNode()
    }



    private fun initializeUI(layout: View) {
        root = layout.findViewById(R.id.recognition_scrollview)
        recognitionDelayedStartLayout = layout.findViewById(R.id.recognition_status_delayedstart_layout)
        recognitionDelayedStartCounter = layout.findViewById(R.id.recognition_status_delayedstart_counter)
        recognitionStatusInfoLayout = layout.findViewById(R.id.recognition_status_info_layout)
        recognitionStatusIcon = layout.findViewById(R.id.recognition_status_icon)
        recognitionStatusText = layout.findViewById(R.id.recognition_status_status_text)
        userHandEditText = layout.findViewById(R.id.recognition_details_userhand_select)
        samplesPerPacketSlider = layout.findViewById(R.id.recognition_details_samplesperpacket_slider)
        samplesPerPacketHint = layout.findViewById(R.id.recognition_details_samplesperpacket_hint)
        delayedStartSlider = layout.findViewById(R.id.recognition_details_delayedstart_slider)
        delayedStartHint = layout.findViewById(R.id.recognition_details_delayedstart_hint)
        toggleBtn = layout.findViewById(R.id.recognition_toggle_fab)

        loadSettingsFromSharedPreferences()
        setupUserHandSelector()
        setupSamplesPerPacketSlider()
        setupDelayedStartSlider()

        viewModel.serviceStatus.observe(viewLifecycleOwner) { status ->
            if (status != null) updateUIFromServiceStatus(status)
        }

        toggleBtn.setOnClickListener {
            when (RecognitionForegroundService.getServiceStatus()) {
                RecognitionForegroundService.ServiceStatus.STOPPED -> {
                    attemptToStartRecognitionForegroundService()
                }
                else -> {
                    RecognitionForegroundService.stop(stopReason = RecognitionForegroundService.ServiceStopReason.MANUAL_STOP)
                }
            }
        }
    }

    private fun attemptToStartRecognitionForegroundService() {
        if (ConnectionManager.connectedNode == null) {
            Snackbar.make(root, getString(R.string.recognition_toast_error_no_wearos), Snackbar.LENGTH_SHORT).show()
        } else if (!RecognitionForegroundService.selectedModelFileExists()) {
            Snackbar.make(root, getString(R.string.recognition_toast_error_no_model), Snackbar.LENGTH_SHORT).show()
        } else {
            RecognitionForegroundService.start()
        }
    }


    private fun updateUIFromServiceStatus(status: RecognitionForegroundService.ServiceStatus) {
        Log.d("RecognitionFragment", "updateUIFromServiceStatus: $status")

        when (status) {
            RecognitionForegroundService.ServiceStatus.STOPPED -> {
                toggleBtn.setImageResource(R.drawable.ic_start)
                toggleEnabledStateOfInputFields(true)
            }
            else -> {
                toggleBtn.setImageResource(R.drawable.ic_stop)
                toggleEnabledStateOfInputFields(false)
            }
        }

        when (status) {
            RecognitionForegroundService.ServiceStatus.STOPPED -> {
                recognitionDelayedStartLayout.visibility = View.GONE
                recognitionStatusInfoLayout.visibility = View.VISIBLE
                recognitionStatusIcon.setImageResource(R.drawable.ic_idle)
                recognitionStatusText.text = getString(R.string.recognition_status_disabled)

                when (serviceStopReason) {
                    RecognitionForegroundService.ServiceStopReason.MANUAL_STOP -> {
                        Snackbar.make(root, getString(R.string.recognition_toast_stop), Snackbar.LENGTH_SHORT).show()
                    }

                    RecognitionForegroundService.ServiceStopReason.NO_RESPONSE_FROM_WEAROS -> {
                        Snackbar.make(root, getString(R.string.recognition_toast_stop_wearos_no_response, (RecognitionForegroundService.WEAR_MESSAGE_TIMEOUT_IN_MS / 1000)), Snackbar.LENGTH_SHORT).show()
                    }

                    RecognitionForegroundService.ServiceStopReason.MODEL_NOT_COMPATIBLE -> {
                        Snackbar.make(root, getString(R.string.recognition_toast_error_model_not_compatible), Snackbar.LENGTH_SHORT).show()
                    }

                    RecognitionForegroundService.ServiceStopReason.RECOGNITION_ERROR -> {
                        Snackbar.make(root, getString(R.string.recognition_toast_stop_recognition_error), Snackbar.LENGTH_SHORT).show()
                    }

                    /*RecognitionForegroundService.ServiceStopReason.WEAR_DEVICE_DISCONNECTED -> {
                        Snackbar.make(root, getString(R.string.recognition_toast_stop_wearos_disconnected), Snackbar.LENGTH_SHORT).show()
                    }*/

                    else -> {}
                }
                serviceStopReason = null
            }

            RecognitionForegroundService.ServiceStatus.DELAYED_START -> {
                recognitionStatusInfoLayout.visibility = View.GONE
                recognitionDelayedStartLayout.visibility = View.VISIBLE
                lifecycleScope.launch {
                    RecognitionForegroundServiceHolder.service!!.delayedStartRemainingTime.collectLatest {
                        recognitionDelayedStartCounter.text = RecognitionForegroundServiceHolder.service!!.delayedStartRemainingTime.value.toString()
                    }
                }
            }

            RecognitionForegroundService.ServiceStatus.WAITING -> {
                recognitionDelayedStartLayout.visibility = View.GONE
                recognitionStatusInfoLayout.visibility = View.VISIBLE
                recognitionStatusIcon.setImageResource(R.drawable.ic_waitingwatch)
                recognitionStatusText.text = getString(R.string.recognition_status_waiting)
            }

            RecognitionForegroundService.ServiceStatus.RECEIVING -> {
                recognitionDelayedStartLayout.visibility = View.GONE
                recognitionStatusInfoLayout.visibility = View.VISIBLE
                lifecycleScope.launch {
                    RecognitionForegroundServiceHolder.service!!.currentRecognizedAction.collectLatest { action ->
                        if (action == null) {
                            recognitionStatusIcon.setImageResource(R.drawable.ic_receiving)
                            recognitionStatusText.text = getString(R.string.recognition_status_receiving_waiting)
                        } else {
                            recognitionStatusIcon.setImageResource(action.drawableResource)
                            recognitionStatusText.text = getString(R.string.recognition_status_receiving_recognizing, getString(action.stringResource))
                        }
                    }
                }
            }
        }
    }

    private fun setupUserHandSelector() {
        class UserHandSelectAdapter(context: Context, resource: Int, objects: List<HandOption>) : ArrayAdapter<HandOption>(context, resource, objects) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createViewFromResource(position, convertView, parent)
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return createViewFromResource(position, convertView, parent)
            }

            private fun createViewFromResource(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_spinner_dropdown_item, parent, false)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                val handOptionText = getItem(position)?.stringResource?.let { context.getString(it) } ?: ""
                textView.text = handOptionText
                return view
            }
        }

        // Initialize hand list and adapter
        val userHandOptions = HandOption.entries
        val userHandSelectAdapter = UserHandSelectAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, userHandOptions)
        userHandEditText.setAdapter(userHandSelectAdapter)

        userHandEditText.setOnItemClickListener { parent, view, position, id ->
            val selectedHandOption = userHandOptions[position]

            userHandEditText.setText(getString(selectedHandOption.stringResource), false)
            viewModel.userHand.value = selectedHandOption

            storeSettingInSharedPreferences()
        }

        // Set initial value from viewModel
        val initialHandOption = userHandOptions.find { it == viewModel.userHand.value }
        if (initialHandOption != null) {
            userHandEditText.setText(getString(initialHandOption.stringResource), false)
        }
    }

    private fun setupSamplesPerPacketSlider() {
        val samplesPerPacketMap = mapOf(
            0F to 10,
            1F to 25,
            2F to 50,
            3F to 100
        )

        val reverseSamplesPerPacketMap = samplesPerPacketMap.entries.associate { (k, v) -> v to k }

        samplesPerPacketSlider.addOnChangeListener { slider, value, fromUser ->
            val samplesPerPacket = samplesPerPacketMap[value] ?: 10
            viewModel.samplesPerPacket.value = samplesPerPacket
            samplesPerPacketHint.text = getString(R.string.recognition_details_samplesperpacket_hint, samplesPerPacket)
            storeSettingInSharedPreferences()
        }

        samplesPerPacketSlider.setLabelFormatter { value ->
            getString(R.string.recognition_details_samplesperpacket_hint, samplesPerPacketMap[value] ?: 10)
        }

        // Set initial value from viewModel
        val initialSamplesPerPacket = viewModel.samplesPerPacket.value ?: 10
        val initialProgress = reverseSamplesPerPacketMap[initialSamplesPerPacket] ?: 0F
        samplesPerPacketSlider.value = initialProgress
        samplesPerPacketHint.text = getString(R.string.recognition_details_samplesperpacket_hint, initialSamplesPerPacket)
    }

    private fun setupDelayedStartSlider() {
        delayedStartSlider.addOnChangeListener { slider, value, fromUser ->
            viewModel.delayedStart.value = value.toInt()
            delayedStartHint.text = getString(R.string.recognition_details_delayedstart_hint, value.toInt())
            storeSettingInSharedPreferences()
        }

        delayedStartSlider.setLabelFormatter { value ->
            getString(R.string.recognition_details_delayedstart_hint, value.toInt())
        }

        // Set initial value from viewModel
        val initialDelayedStart = viewModel.delayedStart.value ?: 10
        delayedStartSlider.value = initialDelayedStart.toFloat()
        delayedStartHint.text = getString(R.string.recognition_details_delayedstart_hint, initialDelayedStart.toInt())
    }

    private fun storeSettingInSharedPreferences(context: Context = ConnectionManager.applicationContext) {
        val sharedPreferences = context.getSharedPreferences("RecognitionSettings", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        editor.putString("userHand", viewModel.userHand.value!!.value)
        editor.putInt("samplesPerPacket", viewModel.samplesPerPacket.value ?: 100)
        editor.putInt("delayedStartInSeconds", viewModel.delayedStart.value ?: 10)

        editor.apply()
    }

    private fun loadSettingsFromSharedPreferences(context: Context = ConnectionManager.applicationContext) {
        val sharedPreferences = context.getSharedPreferences("RecognitionSettings", Context.MODE_PRIVATE)
        viewModel.userHand.value = HandOption.entries.find { it.value == sharedPreferences.getString("userHand", "left") } ?: HandOption.LEFT
        viewModel.samplesPerPacket.value = sharedPreferences.getInt("samplesPerPacket", 10)
        viewModel.delayedStart.value = sharedPreferences.getInt("delayedStartInSeconds", 10)
    }

    private fun toggleEnabledStateOfInputFields(enabled: Boolean) {
        userHandEditText.isEnabled = enabled
        samplesPerPacketSlider.isEnabled = enabled
        delayedStartSlider.isEnabled = enabled
    }
}