package com.luislezama.motiondetect

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.luislezama.motiondetect.deviceconnection.WearConnectionViewModel
import com.google.android.gms.wearable.Node
import com.google.android.material.snackbar.Snackbar
import com.luislezama.motiondetect.deviceconnection.WearConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private val wearConnectionViewModel: WearConnectionViewModel by activityViewModels()

    // UI elements
    private lateinit var root: View
    private lateinit var settingsLoader: View
    private lateinit var settingsWearDeviceLayout: View
    private lateinit var wearDeviceSelect: AutoCompleteTextView
    private lateinit var wearDeviceClearSelectionBtn: Button
    private lateinit var wearDeviceTestConnectionBtn: Button


    private lateinit var wearConnectionManager: WearConnectionManager
    private lateinit var wearableNodes: List<Node>
    private lateinit var wearableSelectAdapter: WearableSelectAdapter


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val settingsLayout = inflater.inflate(R.layout.fragment_settings, container, false)
        wearConnectionManager = wearConnectionViewModel.wearConnectionManager

        // Initialize UI elements
        initializeUI(settingsLayout)
        setupWearableSelector()

        return settingsLayout // Return the inflated layout
    }
    

    // Initialize UI elements
    private fun initializeUI(layout: View) {
        root = layout.findViewById(R.id.settings_scrollview)
        settingsLoader = layout.findViewById(R.id.settings_weardevice_loader)
        settingsWearDeviceLayout = layout.findViewById(R.id.settings_weardevice_layout)
        wearDeviceSelect = layout.findViewById(R.id.settings_weardevice_select)
        wearDeviceClearSelectionBtn = layout.findViewById(R.id.settings_weardevice_clear_btn)
        wearDeviceTestConnectionBtn = layout.findViewById(R.id.settings_weardevice_test_btn)

        settingsWearDeviceLayout.visibility = View.GONE
        settingsLoader.visibility = View.VISIBLE
        wearDeviceTestConnectionBtn.isEnabled = false


        // Set click listener for the clear selection button
        wearDeviceClearSelectionBtn.setOnClickListener {
            wearConnectionManager.saveSelectedWearDevice(nodeId = "-1", nodeName = "")
            wearDeviceSelect.setText("")
            wearDeviceTestConnectionBtn.isEnabled = false
            Snackbar.make(root, R.string.settings_weardevice_select_cleared, Snackbar.LENGTH_SHORT).show()
        }


        // Set click listener for the test connection button
        wearDeviceTestConnectionBtn.setOnClickListener {
            wearDeviceTestConnectionBtn.isEnabled = false
            wearDeviceClearSelectionBtn.isEnabled = false

            val timeout = wearConnectionManager.testingTimeout
            wearDeviceTestConnectionBtn.text = getString(R.string.settings_weardevice_select_test_inprogress, timeout)

            viewLifecycleOwner.lifecycleScope.launch {
                val countdownJob = launch {
                    for (i in timeout downTo 1) {
                        wearDeviceTestConnectionBtn.text = getString(R.string.settings_weardevice_select_test_inprogress, i)
                        delay(1000) // Update number every second
                    }
                }

                val responseTime = wearConnectionManager.testConnection(layout.context)

                countdownJob.cancel()

                if (responseTime == -1L) {
                    Snackbar.make(root, getString(R.string.settings_weardevice_select_test_failure, timeout), Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(root, getString(R.string.settings_weardevice_select_test_success, responseTime), Snackbar.LENGTH_SHORT).show()
                }

                wearDeviceTestConnectionBtn.isEnabled = true
                wearDeviceClearSelectionBtn.isEnabled = true
                wearDeviceTestConnectionBtn.text = getString(R.string.settings_weardevice_select_test) // Restore button text
            }
        }
    }


    // Adapter for the wearable selector
    private fun setupWearableSelector() {
        // Initialize empty list and adapter
        wearableNodes = emptyList()
        wearableSelectAdapter = WearableSelectAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, wearableNodes)
        wearDeviceSelect.setAdapter(wearableSelectAdapter)


        // Load connected devices and fill the wearable selector in a separate coroutine
        viewLifecycleOwner.lifecycleScope.launch {
            wearableNodes = wearConnectionManager.getConnectedDevices()
            val selectedNodeId = wearConnectionManager.getSelectedWearDevice()

            withContext(Dispatchers.Main) { // Run in the main thread
                // Set the current selection in the wearable selector
                if (selectedNodeId != null) {
                    val selectedNode = wearableNodes.find { it.id == selectedNodeId }
                    if (selectedNode != null) {
                        wearDeviceSelect.setText(selectedNode.displayName)
                        wearDeviceTestConnectionBtn.isEnabled = true
                    }
                }

                // Fill the wearable selector with the connected devices
                wearableSelectAdapter = WearableSelectAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, wearableNodes)
                wearDeviceSelect.setAdapter(wearableSelectAdapter)

                // Set the listener for the wearable selector
                wearDeviceSelect.setOnItemClickListener { parent, view, position, id ->
                    val selectedNode = wearableNodes[position]
                    val selectedId = selectedNode.id

                    wearDeviceSelect.setText(selectedNode.displayName) // Update the selected text

                    wearConnectionManager.saveSelectedWearDevice(nodeId = selectedId, nodeName = selectedNode.displayName) // Save the selection
                    Snackbar.make(root, R.string.settings_weardevice_select_saved, Snackbar.LENGTH_SHORT).show() // Show a toast

                    wearDeviceTestConnectionBtn.isEnabled = true
                }

                // Hide the loader and show the wearable selector
                settingsLoader.visibility = View.GONE
                settingsWearDeviceLayout.visibility = View.VISIBLE
            }
        }
    }

    class WearableSelectAdapter(context: Context, resource: Int, objects: List<Node>) : ArrayAdapter<Node>(context, resource, objects) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createViewFromResource(position, convertView, parent)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return createViewFromResource(position, convertView, parent)
        }

        private fun createViewFromResource(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_spinner_dropdown_item, parent, false)
            val textView = view.findViewById<TextView>(android.R.id.text1)
            textView.text = getItem(position)?.displayName
            return view
        }
    }
}