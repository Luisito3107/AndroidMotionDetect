package com.luislezama.motiondetect.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.InputFilter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.luislezama.motiondetect.MainActivity
import com.luislezama.motiondetect.R
import com.luislezama.motiondetect.data.Action
import com.luislezama.motiondetect.data.HandOption
import com.luislezama.motiondetect.data.TrainForegroundService
import com.luislezama.motiondetect.data.TrainForegroundServiceHolder
import com.luislezama.motiondetect.data.TrainViewModel
import com.luislezama.motiondetect.deviceconnection.ConnectionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.Normalizer


class TrainFragment : Fragment() {
    private lateinit var viewModel: TrainViewModel

    // UI elements
    private lateinit var root: View
    private lateinit var trainDelayedStartLayout: LinearLayout
    private lateinit var trainDelayedStartCounter: TextView
    private lateinit var trainStatusInfoLayout: LinearLayout
    private lateinit var trainStatusIcon: ImageView
    private lateinit var trainStatusText: TextView
    private lateinit var actionRadioGroup: RadioGroup
    private lateinit var aliasEditText: TextInputEditText
    private lateinit var stopAfterEditText: TextInputEditText
    private lateinit var userEditText: TextInputEditText
    private lateinit var userHandEditText: AutoCompleteTextView
    private lateinit var samplesPerPacketSlider: Slider
    private lateinit var samplesPerPacketHint: TextView
    private lateinit var delayedStartSlider: Slider
    private lateinit var delayedStartHint: TextView
    private lateinit var toggleBtn: FloatingActionButton


    private var serviceStopReason: TrainForegroundService.ServiceStopReason? = null
    private var serviceStoppedAfterSampleCount: Int = 0
    private val localBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action.let {
                when (it) {
                    TrainForegroundService.ACTION_SERVICE_STATUS_CHANGED -> {
                        val status = intent!!.getSerializableExtra("SERVICE_STATUS", TrainForegroundService.ServiceStatus::class.java)
                        if (status != null) {
                            serviceStoppedAfterSampleCount = intent.getIntExtra("TOTAL_SENSOR_SAMPLES_CAPTURED", 0)
                            serviceStopReason = intent.getSerializableExtra("SERVICE_STOP_REASON", TrainForegroundService.ServiceStopReason::class.java)
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
        val trainLayout = inflater.inflate(R.layout.fragment_train, container, false)

        viewModel = ViewModelProvider(this)[TrainViewModel::class.java]

        initializeUI(trainLayout)

        // Register for service status changes
        val statusChangedFilter = IntentFilter(TrainForegroundService.ACTION_SERVICE_STATUS_CHANGED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(localBroadcastReceiver, statusChangedFilter)

        return trainLayout
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
            viewModel.serviceStatus.value = TrainForegroundService.getServiceStatus()
        }

        // Refresh Wear device selection in ConnectionManager
        ConnectionManager.refreshConnectedNode()

        // Show train history button
        (activity as? MainActivity)?.showTrainHistoryButton(true)
    }

    override fun onPause() {
        super.onPause()

        // Hide train history button
        (activity as? MainActivity)?.showTrainHistoryButton(false)
    }






    private fun initializeUI(layout: View) {
        root = layout.findViewById(R.id.train_scrollview)
        trainDelayedStartLayout = layout.findViewById(R.id.train_status_delayedstart_layout)
        trainDelayedStartCounter = layout.findViewById(R.id.train_status_delayedstart_counter)
        trainStatusInfoLayout = layout.findViewById(R.id.train_status_info_layout)
        trainStatusIcon = layout.findViewById(R.id.train_status_icon)
        trainStatusText = layout.findViewById(R.id.train_status_status_text)
        actionRadioGroup = layout.findViewById(R.id.train_action)
        aliasEditText = layout.findViewById(R.id.train_details_alias)
        stopAfterEditText = layout.findViewById(R.id.train_details_stopafter)
        userEditText = layout.findViewById(R.id.train_details_user)
        userHandEditText = layout.findViewById(R.id.train_details_userhand_select)
        samplesPerPacketSlider = layout.findViewById(R.id.train_details_samplesperpacket_slider)
        samplesPerPacketHint = layout.findViewById(R.id.train_details_samplesperpacket_hint)
        delayedStartSlider = layout.findViewById(R.id.train_details_delayedstart_slider)
        delayedStartHint = layout.findViewById(R.id.train_details_delayedstart_hint)
        toggleBtn = layout.findViewById(R.id.train_toggle_fab)

        loadSettingsFromSharedPreferences()
        setupActionRadioGroup()
        setupEditTexts()
        setupUserHandSelector()
        setupSamplesPerPacketSlider()
        setupDelayedStartSlider()

        viewModel.serviceStatus.observe(viewLifecycleOwner) { status ->
            if (status != null) updateUIFromServiceStatus(status)
        }

        toggleBtn.setOnClickListener {
            when (TrainForegroundService.getServiceStatus()) {
                TrainForegroundService.ServiceStatus.STOPPED -> {
                    attemptToStartTrainForegroundService()
                }
                /*TrainForegroundService.ServiceStatus.DELAYED_START -> {
                    TrainForegroundService.stop()
                }
                TrainForegroundService.ServiceStatus.WAITING -> {
                    TrainForegroundService.stop()
                }
                TrainForegroundService.ServiceStatus.RECEIVING -> {
                    TrainForegroundService.stop()
                }*/
                else -> {
                    TrainForegroundService.stop(stopReason = TrainForegroundService.ServiceStopReason.MANUAL_STOP)
                }
            }
        }


    }


    private fun attemptToStartTrainForegroundService() {
        updateStopAfterEditTextWithValidValue()

        if (ConnectionManager.connectedNode == null) {
            Snackbar.make(root, getString(R.string.train_toast_error_no_wearos), Snackbar.LENGTH_SHORT).show()
        } else if (viewModel.alias.value.isNullOrEmpty()) {
            Snackbar.make(root, getString(R.string.train_toast_error_no_alias), Snackbar.LENGTH_SHORT).show()
            aliasEditText.requestFocus()
        } else if (viewModel.user.value.isNullOrEmpty()) {
            Snackbar.make(root, getString(R.string.train_toast_error_no_user), Snackbar.LENGTH_SHORT).show()
            userEditText.requestFocus()
        } else {
            TrainForegroundService.start()
        }
    }


    private fun updateUIFromServiceStatus(status: TrainForegroundService.ServiceStatus) {
        Log.d("TrainFragment", "updateUIFromServiceStatus: $status")

        when (status) {
            TrainForegroundService.ServiceStatus.STOPPED -> {
                toggleBtn.setImageResource(R.drawable.ic_start)
                toggleEnabledStateOfInputFields(true)
            }
            else -> {
                toggleBtn.setImageResource(R.drawable.ic_stop)
                toggleEnabledStateOfInputFields(false)
            }
        }

        when (status) {
            TrainForegroundService.ServiceStatus.STOPPED -> {
                trainDelayedStartLayout.visibility = View.GONE
                trainStatusInfoLayout.visibility = View.VISIBLE
                trainStatusIcon.setImageResource(R.drawable.ic_idle)
                trainStatusText.text = getString(R.string.train_status_disabled)

                when (serviceStopReason) {
                    TrainForegroundService.ServiceStopReason.MANUAL_STOP, TrainForegroundService.ServiceStopReason.STOP_AFTER_SAMPLE_COUNT -> {
                        val toastString = if (serviceStoppedAfterSampleCount > 0) {
                            getString(R.string.train_toast_stop_after, serviceStoppedAfterSampleCount)
                        } else {
                            getString(R.string.train_toast_stop)
                        }
                        Snackbar.make(root, toastString, Snackbar.LENGTH_SHORT).show()
                    }

                    TrainForegroundService.ServiceStopReason.NO_RESPONSE_FROM_WEAROS -> {
                        Snackbar.make(root, getString(R.string.train_toast_stop_wearos_no_response, serviceStoppedAfterSampleCount, (TrainForegroundService.WEAR_MESSAGE_TIMEOUT_IN_MS / 1000)), Snackbar.LENGTH_SHORT).show()
                    }

                    /*TrainForegroundService.ServiceStopReason.WEAR_DEVICE_DISCONNECTED -> {
                        Snackbar.make(root, getString(R.string.train_toast_stop_wearos_disconnected), Snackbar.LENGTH_SHORT).show()
                    }*/

                    TrainForegroundService.ServiceStopReason.CSV_FILE_NOT_CREATED -> {
                        Snackbar.make(root, getString(R.string.train_toast_error_creating_file), Snackbar.LENGTH_SHORT).show()
                    }

                    TrainForegroundService.ServiceStopReason.CSV_FILE_ALREADY_EXISTS -> {
                        Snackbar.make(root, getString(R.string.train_toast_error_session_already_exists, viewModel.alias.value), Snackbar.LENGTH_SHORT).show()
                    }

                    else -> {}
                }
                serviceStopReason = null
                serviceStoppedAfterSampleCount = 0
            }

            TrainForegroundService.ServiceStatus.DELAYED_START -> {
                trainStatusInfoLayout.visibility = View.GONE
                trainDelayedStartLayout.visibility = View.VISIBLE
                lifecycleScope.launch {
                    TrainForegroundServiceHolder.service!!.delayedStartRemainingTime.collectLatest {
                        trainDelayedStartCounter.text = TrainForegroundServiceHolder.service!!.delayedStartRemainingTime.value.toString()
                    }
                }
            }

            TrainForegroundService.ServiceStatus.WAITING -> {
                trainDelayedStartLayout.visibility = View.GONE
                trainStatusInfoLayout.visibility = View.VISIBLE
                trainStatusIcon.setImageResource(R.drawable.ic_waitingwatch)
                trainStatusText.text = getString(R.string.train_status_waiting)
            }

            TrainForegroundService.ServiceStatus.RECEIVING -> {
                trainDelayedStartLayout.visibility = View.GONE
                trainStatusInfoLayout.visibility = View.VISIBLE
                trainStatusIcon.setImageResource(R.drawable.ic_receiving)
                trainStatusText.text = getString(R.string.train_status_receiving)
                lifecycleScope.launch {
                    TrainForegroundServiceHolder.service!!.totalSensorSamplesCaptured.collectLatest { totalSensorSamplesCaptured ->
                        trainStatusText.text = getString(R.string.train_status_receiving, totalSensorSamplesCaptured, TrainForegroundServiceHolder.service!!.getReceivedSensorDataPacketsCount())
                    }
                }
            }
        }
    }





    private fun setupActionRadioGroup() {
        val actionIdToAction = mapOf(
            R.id.train_action_standing to Action.STANDING,
            R.id.train_action_walking to Action.WALKING,
            R.id.train_action_running to Action.RUNNING,
            R.id.train_action_sitting to Action.SITTING,
            R.id.train_action_lying to Action.LYING
        )

        val actionToActionId = actionIdToAction.entries.associate { (k, v) -> v to k }

        actionRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            val selectedAction = actionIdToAction[checkedId] ?: Action.STANDING
            viewModel.action.value = selectedAction
            storeSettingInSharedPreferences()
        }

        // Set initial value from viewModel
        val initialAction = Action.entries.find { it == viewModel.action.value } ?: Action.STANDING // Retrieve the enum by the string value
        val initialActionId = actionToActionId[initialAction] ?: R.id.train_action_standing
        actionRadioGroup.check(initialActionId)
    }

    private fun setupEditTexts() {
        val filter = InputFilter { source, start, end, dest, dstart, dend ->
            val filtered = StringBuilder()
            for (i in start until end) {
                var char = source[i]

                // Normaliza el carácter para eliminar acentos
                val normalized = Normalizer.normalize(char.toString(), Normalizer.Form.NFD)
                    .replace("\\p{M}".toRegex(), "") // Elimina marcas diacríticas (acentos)

                char = normalized.firstOrNull() ?: continue // Obtiene el primer carácter después de normalizar

                when {
                    char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' -> filtered.append(char.lowercaseChar())
                    char == ' ' -> filtered.append('_') // Reemplaza espacios por guiones bajos
                }
            }
            if (filtered.isEmpty()) "" else filtered.toString()
        }

        //aliasEditText.setText(viewModel.alias.value)
        aliasEditText.filters = arrayOf(filter)
        aliasEditText.doOnTextChanged { text, start, before, count ->
            viewModel.alias.value = text.toString()
            storeSettingInSharedPreferences()
        }
        stopAfterEditText.setText(viewModel.stopAfter.value?.toString() ?: "")
        stopAfterEditText.doOnTextChanged { text, start, before, count ->
            if (!stopAfterEditTextUpdated) {
                viewModel.stopAfter.value = text.toString().toIntOrNull()
                storeSettingInSharedPreferences()
            } else {
                stopAfterEditTextUpdated = false
            }
        }
        userEditText.setText(viewModel.user.value)
        userEditText.filters = arrayOf(filter)
        userEditText.doOnTextChanged { text, start, before, count ->
            viewModel.user.value = text.toString()
            storeSettingInSharedPreferences()
        }
    }

    private var stopAfterEditTextUpdated = false
    private fun updateStopAfterEditTextWithValidValue(context: Context = ConnectionManager.applicationContext) {
        val sharedPreferences = context.getSharedPreferences("TrainSettings", Context.MODE_PRIVATE)
        val stopAfter = sharedPreferences.getInt("stopAfter", -1)
        if (stopAfter == -1) {
            stopAfterEditTextUpdated = true
            stopAfterEditText.setText("")
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
            samplesPerPacketHint.text = getString(R.string.train_details_samplesperpacket_hint, samplesPerPacket)
            storeSettingInSharedPreferences()
        }

        samplesPerPacketSlider.setLabelFormatter { value ->
            getString(R.string.train_details_samplesperpacket_hint, samplesPerPacketMap[value] ?: 10)
        }

        // Set initial value from viewModel
        val initialSamplesPerPacket = viewModel.samplesPerPacket.value ?: 10
        val initialProgress = reverseSamplesPerPacketMap[initialSamplesPerPacket] ?: 0F
        samplesPerPacketSlider.value = initialProgress
        samplesPerPacketHint.text = getString(R.string.train_details_samplesperpacket_hint, initialSamplesPerPacket)
    }

    private fun setupDelayedStartSlider() {
        delayedStartSlider.addOnChangeListener { slider, value, fromUser ->
            viewModel.delayedStart.value = value.toInt()
            delayedStartHint.text = getString(R.string.train_details_delayedstart_hint, value.toInt())
            storeSettingInSharedPreferences()
        }

        delayedStartSlider.setLabelFormatter { value ->
            getString(R.string.train_details_delayedstart_hint, value.toInt())
        }

        // Set initial value from viewModel
        val initialDelayedStart = viewModel.delayedStart.value ?: 10
        delayedStartSlider.value = initialDelayedStart.toFloat()
        delayedStartHint.text = getString(R.string.train_details_delayedstart_hint, initialDelayedStart.toInt())
    }

    private fun storeSettingInSharedPreferences(context: Context = ConnectionManager.applicationContext) {
        val sharedPreferences = context.getSharedPreferences("TrainSettings", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        editor.putString("action", viewModel.action.value!!.value)
        editor.putString("alias", viewModel.alias.value)
        editor.putString("user", viewModel.user.value)
        editor.putString("userHand", viewModel.userHand.value!!.value)
        editor.putInt("samplesPerPacket", viewModel.samplesPerPacket.value ?: 100)
        editor.putInt("delayedStartInSeconds", viewModel.delayedStart.value ?: 10)

        if ((viewModel.stopAfter.value ?: -1) <= 0) {
            editor.remove("stopAfter")
        } else {
            editor.putInt("stopAfter", viewModel.stopAfter.value ?: -1)
        }

        editor.apply()
    }

    private fun loadSettingsFromSharedPreferences(context: Context = ConnectionManager.applicationContext) {
        val sharedPreferences = context.getSharedPreferences("TrainSettings", Context.MODE_PRIVATE)
        viewModel.action.value = Action.entries.find { it.value == sharedPreferences.getString("action", "standing") } ?: Action.STANDING
        //viewModel.alias.value = sharedPreferences.getString("alias", "")
        viewModel.user.value = sharedPreferences.getString("user", "")
        viewModel.userHand.value = HandOption.entries.find { it.value == sharedPreferences.getString("userHand", "left") } ?: HandOption.LEFT
        viewModel.stopAfter.value = sharedPreferences.getInt("stopAfter", -1)
        if (viewModel.stopAfter.value == -1) {
            viewModel.stopAfter.value = null
        }
        viewModel.samplesPerPacket.value = sharedPreferences.getInt("samplesPerPacket", 10)
        viewModel.delayedStart.value = sharedPreferences.getInt("delayedStartInSeconds", 10)
    }

    private fun toggleEnabledStateOfInputFields(enabled: Boolean) {
        actionRadioGroup.children.forEach { it.isEnabled = enabled }
        aliasEditText.isEnabled = enabled
        stopAfterEditText.isEnabled = enabled
        userEditText.isEnabled = enabled
        userHandEditText.isEnabled = enabled
        samplesPerPacketSlider.isEnabled = enabled
        delayedStartSlider.isEnabled = enabled
    }
}