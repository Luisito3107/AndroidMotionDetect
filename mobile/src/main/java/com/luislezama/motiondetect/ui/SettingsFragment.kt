package com.luislezama.motiondetect.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.luislezama.motiondetect.R
import com.luislezama.motiondetect.deviceconnection.ConnectionManager
import com.luislezama.motiondetect.deviceconnection.PseudoNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsFragment : Fragment() {
    // UI elements
    private lateinit var root: View
    private lateinit var settingsWearDeviceLoader: View
    private lateinit var settingsWearDeviceLayout: View
    private lateinit var wearDeviceSelect: AutoCompleteTextView
    private lateinit var wearDeviceClearSelectionBtn: Button
    private lateinit var wearDeviceTestConnectionBtn: Button
    private lateinit var recognitionModelEditText: TextInputEditText
    private lateinit var recognitionModelClearSelectionBtn: Button
    private lateinit var recognitionModelSelectNewBtn: Button
    private lateinit var recognitionModelSelectLauncher: ActivityResultLauncher<String>

    private var wearableNodes: List<PseudoNode> = emptyList()
    private lateinit var wearableSelectAdapter: WearableSelectAdapter

    private val sharedPreferences = ConnectionManager.applicationContext.getSharedPreferences("motiondetect_prefs", Context.MODE_PRIVATE)


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val settingsLayout = inflater.inflate(R.layout.fragment_settings, container, false)

        // Initialize UI elements
        initializeUI(settingsLayout)
        setupRecognitionModelSelectLauncher()

        return settingsLayout // Return the inflated layout
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
        ConnectionManager.cancelTestConnection()
    }


    // Initialize UI elements
    private fun initializeUI(layout: View) {
        root = layout.findViewById(R.id.settings_scrollview)
        settingsWearDeviceLoader = layout.findViewById(R.id.settings_weardevice_loader)
        settingsWearDeviceLayout = layout.findViewById(R.id.settings_weardevice_layout)
        wearDeviceSelect = layout.findViewById(R.id.settings_weardevice_select)
        wearDeviceClearSelectionBtn = layout.findViewById(R.id.settings_weardevice_clear_btn)
        wearDeviceTestConnectionBtn = layout.findViewById(R.id.settings_weardevice_test_btn)
        recognitionModelEditText = layout.findViewById(R.id.settings_recognitionmodel_selected)
        recognitionModelClearSelectionBtn = layout.findViewById(R.id.settings_recognitionmodel_clear_btn)
        recognitionModelSelectNewBtn = layout.findViewById(R.id.settings_recognitionmodel_selectnew_btn)

        settingsWearDeviceLayout.visibility = View.GONE
        settingsWearDeviceLoader.visibility = View.VISIBLE
        wearDeviceTestConnectionBtn.isEnabled = false


        setupWearableSelector()
        setupWearableClearSelectionButton()
        setupWearableTestConnectionButton()

        setupRecognitionModelSelectNewButton()
        setupRecognitionModelClearButton()
        setupRecognitionModelEditText()
    }


    // Adapter for the wearable selector
    private fun setupWearableSelector() {
        // Initialize empty list and adapter
        wearableSelectAdapter = WearableSelectAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, wearableNodes)
        wearDeviceSelect.setAdapter(wearableSelectAdapter)


        // Load connected devices and fill the wearable selector in a separate coroutine
        viewLifecycleOwner.lifecycleScope.launch {
            wearableNodes = ConnectionManager.getAllConnectedNodes()
            val selectedNode = ConnectionManager.getSelectedNode()

            withContext(Dispatchers.Main) { // Run in the main thread
                // Set the current selection in the wearable selector
                if (selectedNode != null) {
                    wearDeviceSelect.setText(selectedNode.displayName)
                    wearDeviceTestConnectionBtn.isEnabled = true
                }

                // Fill the wearable selector with the connected devices
                wearableSelectAdapter = WearableSelectAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, wearableNodes)
                wearDeviceSelect.setAdapter(wearableSelectAdapter)

                // Set the listener for the wearable selector
                wearDeviceSelect.setOnItemClickListener { parent, view, position, id ->
                    val newSelectedNode = wearableNodes[position]

                    wearDeviceSelect.setText(newSelectedNode.displayName) // Update the selected text

                    ConnectionManager.saveSelectedWearDevice(newSelectedNode) // Save the selection
                    Snackbar.make(root, R.string.settings_weardevice_select_saved, Snackbar.LENGTH_SHORT).show() // Show a toast

                    wearDeviceTestConnectionBtn.isEnabled = true
                }

                // Hide the loader and show the wearable selector
                settingsWearDeviceLoader.visibility = View.GONE
                settingsWearDeviceLayout.visibility = View.VISIBLE
            }
        }
    }

    class WearableSelectAdapter(context: Context, resource: Int, objects: List<PseudoNode>) : ArrayAdapter<PseudoNode>(context, resource, objects) {
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


    // Setup Wear OS device clear selection button
    private fun setupWearableClearSelectionButton() {
        wearDeviceClearSelectionBtn.setOnClickListener {
            ConnectionManager.clearSelectedWearDevice()
            wearDeviceSelect.setText("", false)
            wearDeviceTestConnectionBtn.isEnabled = false
            Snackbar.make(root, R.string.settings_weardevice_select_cleared, Snackbar.LENGTH_SHORT).show()
        }
    }


    // Wear OS device test connection button
    private fun setupWearableTestConnectionButton() {
        wearDeviceTestConnectionBtn.setOnClickListener {
            wearDeviceTestConnectionBtn.isEnabled = false
            wearDeviceClearSelectionBtn.isEnabled = false

            val timeout = ConnectionManager.TESTING_TIMEOUT
            wearDeviceTestConnectionBtn.text = getString(R.string.settings_weardevice_select_test_inprogress, timeout)

            viewLifecycleOwner.lifecycleScope.launch {
                val countdownJob = launch {
                    for (i in timeout downTo 1) {
                        wearDeviceTestConnectionBtn.text = getString(R.string.settings_weardevice_select_test_inprogress, i)
                        delay(1000) // Update number every second
                    }
                }

                val responseTime = ConnectionManager.testConnection()

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


    private fun setupRecognitionModelEditText() {
        val selectedModelPath = getRecognitionModelPath()
        if (selectedModelPath != null) {
            val fileName = selectedModelPath.substringAfterLast("/")
            recognitionModelEditText.setText(fileName)
        } else {
            recognitionModelEditText.setText(R.string.settings_recognitionmodel_selected_none)
        }
    }
    private fun setupRecognitionModelSelectLauncher() {
        recognitionModelSelectLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                val context = requireContext()
                val contentResolver = context.contentResolver
                var fileName: String? = null

                // Get selected file name from ContentResolver
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && it.moveToFirst()) {
                        fileName = it.getString(nameIndex)
                    }
                }
                if (fileName == null) fileName = ""

                // Validate file name and extension before copying to app storage
                if (fileName!!.endsWith(".tflite", ignoreCase = true)) {
                    storeRecognitionModelInAppStorage(uri, fileName!!)
                } else {
                    Snackbar.make(root, getString(R.string.settings_recognitionmodel_select_error_invalid_file), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun storeRecognitionModelInAppStorage(uri: Uri, fileName: String) {
        val context = requireContext()

        // Show a loading dialog before starting the copy
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_loading, null)
        val loadingTextView = dialogView.findViewById<TextView>(R.id.dialog_loading_text)
        loadingTextView.text = getString(R.string.settings_recognitionmodel_select_copying)
        val progressDialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val modelsDir = File(context.filesDir, "models")
                if (!modelsDir.exists()) {
                    modelsDir.mkdirs() // Create model directory if it doesn't exist
                }
                val outputFile = File(modelsDir, fileName)

                inputStream?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                // Delete previous file if it exists
                deleteRecognitionModelFile()

                // Store the file path in SharedPreferences
                saveRecognitionModelPath(outputFile.absolutePath)

                // Hide dialog in the main thread
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    setupRecognitionModelEditText()
                    Snackbar.make(root, getString(R.string.settings_recognitionmodel_select_saved), Snackbar.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Snackbar.make(root, getString(R.string.settings_recognitionmodel_select_error_copy_failed), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun saveRecognitionModelPath(path: String) {
        sharedPreferences.edit().putString("selected_model_path", path).apply()
    }
    private fun getRecognitionModelPath(): String? {
        return sharedPreferences.getString("selected_model_path", null)
    }
    private fun setupRecognitionModelSelectNewButton() {
        recognitionModelSelectNewBtn.setOnClickListener {
            recognitionModelSelectLauncher.launch("application/octet-stream")
        }
    }
    private fun deleteRecognitionModelFile() {
        val selectedModelPath = getRecognitionModelPath()
        if (selectedModelPath != null) {
            val file = File(selectedModelPath)
            if (file.exists()) {
                file.delete()
            }
        }
    }
    private fun setupRecognitionModelClearButton() {
        recognitionModelClearSelectionBtn.setOnClickListener {
            // Delete file from app storage
            deleteRecognitionModelFile()

            sharedPreferences.edit().remove("selected_model_path").apply()
            setupRecognitionModelEditText()
            Snackbar.make(root, getString(R.string.settings_recognitionmodel_select_cleared), Snackbar.LENGTH_SHORT).show()
        }
    }
}