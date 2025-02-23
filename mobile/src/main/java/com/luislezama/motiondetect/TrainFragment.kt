package com.luislezama.motiondetect

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.children
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.luislezama.motiondetect.deviceconnection.WearConnectionManager
import com.luislezama.motiondetect.deviceconnection.WearConnectionViewModel
import java.text.Normalizer


class TrainFragment : Fragment() {
    private val wearConnectionViewModel: WearConnectionViewModel by activityViewModels()
    private lateinit var wearConnectionManager: WearConnectionManager

    private lateinit var viewModel: TrainViewModel
    private var trainStatus = TrainStatus.IDLE

    // UI elements
    private lateinit var root: View
    private lateinit var bottomNavigationView: BottomNavigationView
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




    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val trainLayout = inflater.inflate(R.layout.fragment_train, container, false)
        wearConnectionManager = wearConnectionViewModel.wearConnectionManager

        viewModel = ViewModelProvider(this).get(TrainViewModel::class.java)

        // Initialize UI elements
        initializeUI(trainLayout, requireContext())

        return trainLayout // Return the inflated layout
    }


    private fun initializeUI(layout: View, context: Context) {
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
        userHandEditText = layout.findViewById(R.id.settings_weardevice_select)
        samplesPerPacketSlider = layout.findViewById(R.id.train_details_samplesperpacket_slider)
        samplesPerPacketHint = layout.findViewById(R.id.train_details_samplesperpacket_hint)
        delayedStartSlider = layout.findViewById(R.id.train_details_delayedstart_slider)
        delayedStartHint = layout.findViewById(R.id.train_details_delayedstart_hint)

        toggleBtn = layout.findViewById(R.id.train_toggle_fab)

        bottomNavigationView = requireActivity().findViewById(R.id.nav_view)

        loadSettingsFromSharedPreferences(context)
        setupUserHandSelector()
        setupEditTexts()
        setupActionRadioGroup()
        setupSamplesPerPacketSlider()
        setupDelayedStartSlider()
        setupBackPressHandling()

        // Observe the delayed start counter
        viewModel.remainingTime.observe(viewLifecycleOwner) { counter ->
            trainDelayedStartCounter.text = counter.toString()
        }

        // Observe if capture started in the wear view model
        wearConnectionViewModel.captureStarted.observe(viewLifecycleOwner) { started ->
            if (started) {
                wearConnectionViewModel.captureStarted.value = false
                viewModel.setStatus(TrainStatus.RECEIVING)
            }
        }

        // Observe the training state
        viewModel.status.observe(viewLifecycleOwner) { status ->
            trainStatus = status

            when (status) {
                TrainStatus.DELAYED_START -> {
                    trainStatusInfoLayout.visibility = View.GONE
                    trainDelayedStartLayout.visibility = View.VISIBLE
                }
                else -> {
                    trainDelayedStartLayout.visibility = View.GONE
                    trainStatusInfoLayout.visibility = View.VISIBLE
                }
            }

            when (status) {
                TrainStatus.IDLE -> {
                    trainStatusIcon.setImageResource(R.drawable.ic_idle)
                    trainStatusText.text = getString(R.string.train_status_disabled)
                    toggleBtn.setImageResource(R.drawable.ic_start)
                    toggleDisabledStateFromInputs(false)

                    viewModel.deleteCSVFileIfEmpty()
                    TrainingService.stopTrainingService(requireActivity().applicationContext)
                }
                TrainStatus.DELAYED_START -> {
                    toggleDisabledStateFromInputs(true)
                    toggleBtn.setImageResource(R.drawable.ic_stop)

                    wearConnectionViewModel.samplesPerPacket.value = viewModel.samplesPerPacket.value!!
                    TrainingService.startTrainingService(requireActivity().applicationContext)
                    val sessionCSVFileCreated = viewModel.createCSVFile(context)
                    if (!sessionCSVFileCreated) {
                        Snackbar.make(root, getString(R.string.train_toast_error_creating_file), Snackbar.LENGTH_SHORT).show()
                        viewModel.cancelDelayedStartCountdown()
                        wearConnectionManager.stopSensorCapture()
                    }
                }
                TrainStatus.WAITING -> {
                    toggleDisabledStateFromInputs(true)
                    trainStatusIcon.setImageResource(R.drawable.ic_waitingwatch)
                    trainStatusText.text = getString(R.string.train_status_waiting)
                    toggleBtn.setImageResource(R.drawable.ic_stop)
                    viewModel.startTimeoutChecker {
                        Snackbar.make(root, getString(R.string.train_toast_stop_wearos_no_response, viewModel.appendedDataCount.value), Snackbar.LENGTH_SHORT).show()
                    }
                }
                TrainStatus.RECEIVING -> {
                    toggleDisabledStateFromInputs(true)
                    trainStatusIcon.setImageResource(R.drawable.ic_receiving)
                    trainStatusText.text = getString(R.string.train_status_receiving, 0, 0)
                    toggleBtn.setImageResource(R.drawable.ic_stop)
                }
                else -> {}
            }
        }

        // Set click listener for the start button
        toggleBtn.setOnClickListener {
            when (trainStatus) {
                TrainStatus.IDLE -> {
                    if (wearConnectionManager.getSelectedWearDeviceFromSharedPreferences(root.context).isNullOrEmpty()) {
                        Snackbar.make(root, getString(R.string.train_toast_error_no_wearos), Snackbar.LENGTH_SHORT).show()
                    } else if (viewModel.alias.value.isNullOrEmpty()) {
                        Snackbar.make(root, getString(R.string.train_toast_error_no_alias), Snackbar.LENGTH_SHORT).show()
                        aliasEditText.requestFocus()
                    } else if (viewModel.user.value.isNullOrEmpty()) {
                        Snackbar.make(root, getString(R.string.train_toast_error_no_user), Snackbar.LENGTH_SHORT).show()
                        userEditText.requestFocus()
                    } else if (viewModel.sessionFileAlreadyExists(context)) {
                        Snackbar.make(root, getString(R.string.train_toast_error_session_already_exists, viewModel.alias.value), Snackbar.LENGTH_SHORT).show()
                    } else {
                        viewModel.startDelayedStartCountdown {
                            wearConnectionManager.startSensorCapture(viewModel.samplesPerPacket.value!!)
                            viewModel.setStatus(TrainStatus.WAITING)
                            Snackbar.make(root, getString(R.string.train_toast_start), Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                else -> {
                    Snackbar.make(root, getString(R.string.train_toast_stop_after, viewModel.appendedDataCount.value), Snackbar.LENGTH_SHORT).show()
                    viewModel.cancelDelayedStartCountdown()
                    wearConnectionManager.stopSensorCapture()
                    wearConnectionViewModel.recievedSensorMessages.value = 0
                }
            }
        }

        wearConnectionViewModel.sensorDataString.observe(viewLifecycleOwner) { sensorDataString ->
            if (sensorDataString.isEmpty()) return@observe
            else if (trainStatus !in listOf(TrainStatus.WAITING, TrainStatus.RECEIVING)) return@observe
            else if (sensorDataString.contains("|")) {
                viewModel.resetLastSensorDataTime()

                val sensorDataSamples = sensorDataString.split("|")
                for (sample in sensorDataSamples) {
                    val values = sample.split(";")

                    val acc = values[0].split(",")
                    val accData = arrayOf(acc[0].toFloat(), acc[1].toFloat(), acc[2].toFloat())
                    val gyro = values[1].split(",")
                    val gyroData = arrayOf(gyro[0].toFloat(), gyro[1].toFloat(), gyro[2].toFloat())

                    val appendedDataCount = viewModel.appendDataToCSVFile(accData, gyroData)
                    if (appendedDataCount != null) {
                        trainStatusText.text = getString(R.string.train_status_receiving, appendedDataCount, wearConnectionViewModel.recievedSensorMessages.value)

                        if ((viewModel.stopAfter.value ?: 0) > 0) {
                            if (appendedDataCount >= viewModel.stopAfter.value!!) {
                                viewModel.cancelDelayedStartCountdown()
                                wearConnectionManager.stopSensorCapture()
                                wearConnectionViewModel.recievedSensorMessages.value = 0
                                Snackbar.make(
                                    root,
                                    getString(R.string.train_toast_stop_after, appendedDataCount),
                                    Snackbar.LENGTH_SHORT
                                ).show()
                                viewModel.setStatus(TrainStatus.IDLE)
                            }
                        }
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
            viewModel.action.value = selectedAction.value // Store the string value
            storeSettingInSharedPreferences(requireContext())
        }

        // Set initial value from viewModel
        val initialAction = Action.entries.find { it.value == viewModel.action.value } ?: Action.STANDING // Retrieve the enum by the string value
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

        aliasEditText.setText(viewModel.alias.value)
        aliasEditText.filters = arrayOf(filter)
        aliasEditText.doOnTextChanged { text, start, before, count ->
            viewModel.alias.value = text.toString()
            storeSettingInSharedPreferences(requireContext())
        }
        stopAfterEditText.setText(viewModel.stopAfter.value?.toString() ?: "")
        stopAfterEditText.doOnTextChanged { text, start, before, count ->
            viewModel.stopAfter.value = text.toString().toIntOrNull()
            storeSettingInSharedPreferences(requireContext())
        }
        userEditText.setText(viewModel.user.value)
        userEditText.filters = arrayOf(filter)
        userEditText.doOnTextChanged { text, start, before, count ->
            viewModel.user.value = text.toString()
            storeSettingInSharedPreferences(requireContext())
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
                val handOptionText = getItem(position)?.displayStringRes?.let { context.getString(it) } ?: ""
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

            userHandEditText.setText(getString(selectedHandOption.displayStringRes), false)
            viewModel.userHand.value = selectedHandOption.value

            storeSettingInSharedPreferences(requireContext())
        }

        // Set initial value from viewModel
        val initialHandOption = userHandOptions.find { it.value == viewModel.userHand.value }
        if (initialHandOption != null) {
            userHandEditText.setText(getString(initialHandOption.displayStringRes), false)
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
            storeSettingInSharedPreferences(requireContext())
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
            storeSettingInSharedPreferences(requireContext())
        }

        delayedStartSlider.setLabelFormatter { value ->
            getString(R.string.train_details_delayedstart_hint, value.toInt())
        }

        // Set initial value from viewModel
        val initialDelayedStart = viewModel.delayedStart.value ?: 10
        delayedStartSlider.value = initialDelayedStart.toFloat()
        delayedStartHint.text = getString(R.string.train_details_delayedstart_hint, initialDelayedStart.toInt())
    }

    private fun storeSettingInSharedPreferences(context: Context) {
        val sharedPreferences = context.getSharedPreferences("TrainSettings", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()

        editor.putString("action", viewModel.action.value)
        editor.putString("alias", viewModel.alias.value)
        editor.putString("user", viewModel.user.value)
        editor.putString("userHand", viewModel.userHand.value)
        editor.putInt("stopAfter", viewModel.stopAfter.value ?: -1)
        editor.putInt("samplesPerPacket", viewModel.samplesPerPacket.value ?: 100)
        editor.putInt("delayedStart", viewModel.delayedStart.value ?: 10)

        editor.apply()
    }

    private fun loadSettingsFromSharedPreferences(context: Context) {
        val sharedPreferences = context.getSharedPreferences("TrainSettings", Context.MODE_PRIVATE)
        viewModel.action.value = sharedPreferences.getString("action", "standing")
        //viewModel.alias.value = sharedPreferences.getString("alias", "")
        viewModel.user.value = sharedPreferences.getString("user", "")
        viewModel.userHand.value = sharedPreferences.getString("userHand", "left")
        viewModel.stopAfter.value = sharedPreferences.getInt("stopAfter", -1)
        if (viewModel.stopAfter.value == -1) {
            viewModel.stopAfter.value = null
        }
        viewModel.samplesPerPacket.value = sharedPreferences.getInt("samplesPerPacket", 10)
        viewModel.delayedStart.value = sharedPreferences.getInt("delayedStart", 10)
    }

    private fun toggleDisabledStateFromInputs(state: Boolean) {
        actionRadioGroup.children.forEach { it.isEnabled = !state }
        aliasEditText.isEnabled = !state
        stopAfterEditText.isEnabled = !state
        userEditText.isEnabled = !state
        userHandEditText.isEnabled = !state
        samplesPerPacketSlider.isEnabled = !state
        delayedStartSlider.isEnabled = !state
        bottomNavigationView.menu.children.forEach { it.isEnabled = !state }
        (activity as? MainActivity)?.disableTrainHistoryButton(!state)
    }

    private fun setupBackPressHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(
            trainStatus == TrainStatus.IDLE
        ) {
            override fun handleOnBackPressed() {
                if (trainStatus != TrainStatus.IDLE) {
                    Snackbar.make(root, getString(R.string.train_toast_error_cant_exit), Snackbar.LENGTH_SHORT).show()
                } else {
                    // Training is not in progress, let the default back behavior happen
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }




    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.showTrainHistoryButton(true) // Mostrar botón
    }

    override fun onPause() {
        super.onPause()
        (activity as? MainActivity)?.showTrainHistoryButton(false) // Ocultar botón al salir
    }
}