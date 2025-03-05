package com.luislezama.motiondetect.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.luislezama.motiondetect.R
import com.luislezama.motiondetect.data.Action
import com.luislezama.motiondetect.data.CSVLine
import com.luislezama.motiondetect.data.TrainForegroundService
import com.luislezama.motiondetect.databinding.ActivityTrainHistoryBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TrainHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrainHistoryBinding
    var fileList: MutableList<File> = mutableListOf()
    private lateinit var adapter: CsvFileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTrainHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar: MaterialToolbar = findViewById(R.id.train_history_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        val recyclerView = findViewById<RecyclerView>(R.id.train_history_recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val subfolder = File(filesDir, TrainForegroundService.SESSIONS_STORED_IN_SUBFOLDER)
        val fileList = subfolder.listFiles { file -> file.extension == "csv" }?.toMutableList() ?: mutableListOf()
        adapter = CsvFileAdapter(fileList) { file, position ->
            showFileOptionsDialog(this, file) {
                fileList.removeAt(position)
                adapter.notifyItemRemoved(position)
            }
        }
        recyclerView.adapter = adapter

        deleteSharedZipFileIfOld(this)
    }



    class CsvFileAdapter(
        private val files: List<File>,
        private val onItemClick: (File, Int) -> Unit
    ) : RecyclerView.Adapter<CsvFileAdapter.CsvFileViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CsvFileViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.train_history_file, parent, false)
            return CsvFileViewHolder(view)
        }

        override fun onBindViewHolder(holder: CsvFileViewHolder, position: Int) {
            val file = files[position]
            holder.bind(file)
            holder.itemView.setOnClickListener { onItemClick(file, position) }
        }

        override fun getItemCount(): Int = files.size

        class CsvFileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val sessionNameTextView: TextView = itemView.findViewById(R.id.train_history_session)
            private val sessionActionIcon: ImageView = itemView.findViewById(R.id.train_history_action_icon)
            private val sessionActionLabel: TextView = itemView.findViewById(R.id.train_history_action_label)
            private val sessionDateTextView: TextView = itemView.findViewById(R.id.train_history_timestamp)
            private val sessionUserTextView: TextView = itemView.findViewById(R.id.train_history_user)
            private val sessionCountTextView: TextView = itemView.findViewById(R.id.train_history_count)
            private val sessionFilesizeTextView: TextView = itemView.findViewById(R.id.train_history_filesize)

            fun bind(file: File) {
                val fileName = file.nameWithoutExtension
                val parts = fileName.split("_")
                val timestamp = (parts.getOrNull(0)?.toLongOrNull() ?: 0L)*1000
                val sessionName = parts.drop(1).joinToString("_")

                // Timestamp to readable date
                val formattedDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
                    Date(timestamp)
                )

                val fileLines = file.readLines()

                // Find action icon and label inside the first line of the file
                fileLines.firstOrNull()?.let { line ->
                    val csvLine = CSVLine.fromCSVString(line)

                    // Action icon and label
                    sessionActionIcon.setImageResource(csvLine.action.drawableResource)
                    sessionActionLabel.text = itemView.context.getString(csvLine.action.stringResource)

                    // User name
                    sessionUserTextView.text = csvLine.sessionUserName
                }

                // Count samples in file
                val recordCount = fileLines.size //- 1 // -1 If header present
                sessionCountTextView.text = itemView.context.getString(R.string.train_history_session_count, recordCount)

                // Calculate file size in MB
                sessionFilesizeTextView.text = "%.2f MB".format(file.length() / (1024.0 * 1024.0))

                sessionNameTextView.text = sessionName
                sessionDateTextView.text = formattedDate
            }
        }
    }



    // Session management
    private fun showFileOptionsDialog(context: Context, file: File, onDelete: () -> Unit) {
        val options = arrayOf(getString(R.string.train_history_session_dialog_action_open), getString(
            R.string.train_history_session_dialog_action_share
        ), getString(R.string.train_history_session_dialog_action_delete))

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.train_history_session_dialog_title, file.name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openCsvFile(context, file)
                    1 -> shareCsvFile(context, file)
                    2 -> confirmDeleteFile(context, file, onDelete)
                }
            }
            .show()
    }

    private fun openCsvFile(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/csv")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shareCsvFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, getString(R.string.train_history_session_dialog_action_share)))
    }

    private fun confirmDeleteFile(context: Context, file: File, onDelete: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.train_history_session_delete_title, file.name))
            .setMessage(getString(R.string.train_history_session_delete_message))
            .setPositiveButton(getString(R.string.train_history_session_delete_confirm)) { _, _ ->
                if (file.delete()) {
                    Snackbar.make(binding.root, getString(R.string.train_history_session_delete_success), Snackbar.LENGTH_SHORT).show()
                    onDelete()
                } else {
                    Snackbar.make(binding.root, getString(R.string.train_history_session_delete_error), Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.train_history_session_delete_cancel), null)
            .show()
    }

    private fun confirmDeleteAllFiles(context: Context, onDelete: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(getString(R.string.train_history_delete_all))
            .setMessage(getString(R.string.train_history_delete_all_message))
            .setPositiveButton(getString(R.string.train_history_delete_all_confirm)) { _, _ ->
                val filesDir = context.filesDir
                val csvFiles = filesDir.listFiles { file -> file.extension == "csv" }
                csvFiles?.forEach { it.delete() }
                Snackbar.make(binding.root, getString(R.string.train_history_delete_all_success), Snackbar.LENGTH_SHORT).show()
                onDelete()
            }
            .setNegativeButton(getString(R.string.train_history_delete_all_cancel), null)
            .show()
    }

    fun shareAllSessionFiles(context: Context) {
        val subfolder = File(context.filesDir, TrainForegroundService.SESSIONS_STORED_IN_SUBFOLDER)
        val fileList = subfolder.listFiles { file -> file.extension == "csv" }?.toList() ?: emptyList()

        if (fileList.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.train_history_share_all_as_zip_error_no_sessions), Snackbar.LENGTH_SHORT).show()
            return
        }

        // Delete existent ZIP file (there is only one)
        val existentZipFile = context.filesDir.listFiles { file -> file.extension == "zip" }?.firstOrNull()
        if (existentZipFile?.exists() == true) existentZipFile.delete()


        // Create ZIP file in the app data
        val timestamp = (System.currentTimeMillis() / 1000)
        val zipFile = File(context.filesDir, "${timestamp}_training_history.zip")


        // Show a loading dialog before starting the copy
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_loading, null)
        val loadingTextView = dialogView.findViewById<TextView>(R.id.dialog_loading_text)
        loadingTextView.text = getString(R.string.train_history_share_all_as_zip_message)
        val progressDialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton(getString(R.string.train_history_share_all_as_zip_cancel)) { dialog, _ ->
                zipFile.delete() // Delete zip file if cancelled
                dialog.dismiss()
            }
            .create()
        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                    fileList.forEach { file ->
                        FileInputStream(file).use { fis ->
                            val entry = ZipEntry(file.name)
                            zipOut.putNextEntry(entry)
                            fis.copyTo(zipOut)
                            zipOut.closeEntry()
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    shareZipFile(context, zipFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Snackbar.make(binding.root, getString(R.string.train_history_share_all_as_zip_error), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareZipFile(context: Context, zipFile: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, getString(R.string.train_history_share_all_as_zip)))
    }

    companion object {
        // Delete file if created more than an hour ago
        fun deleteSharedZipFileIfOld(context: Context) {
            val existentZipFile = context.filesDir.listFiles { file -> file.extension == "zip" }?.firstOrNull()
            if (existentZipFile?.exists() == true) {
                val currentTime = System.currentTimeMillis()
                val fileTime = existentZipFile.lastModified()
                if (currentTime - fileTime > 60 * 60 * 1000) {
                    existentZipFile.delete()
                }
            }
        }
    }




    // Train history button only visible in the train fragment
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_train_history, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.train_history_share_all_btn -> {
                shareAllSessionFiles(this)
                true
            }
            R.id.train_history_delete_all_btn -> {
                confirmDeleteAllFiles(this) {
                    fileList.clear()
                    adapter.notifyDataSetChanged()
                }
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }
}