package com.luislezama.motiondetect

import WearDataListener
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.gms.wearable.Wearable
import com.luislezama.motiondetect.databinding.ActivityMainBinding
import com.luislezama.motiondetect.deviceconnection.ConnectionManager
import com.luislezama.motiondetect.deviceconnection.WearConnectionViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val wearConnectionViewModel: WearConnectionViewModel by viewModels()
    private lateinit var wearDataListener: WearDataListener

    private var trainHistoryMenuitem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_predict, R.id.navigation_train, R.id.navigation_settings
            )
        )

        setSupportActionBar(findViewById(R.id.toolbar))
        setupActionBarWithNavController(this, navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        wearConnectionViewModel.connectionManager = ConnectionManager(this)
        wearDataListener = WearDataListener(this, wearConnectionViewModel.connectionManager)
        wearDataListener.setViewModel(wearConnectionViewModel)

        requestNotificationPermission()
    }


    // Train history button only visible in the train fragment
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_train, menu)
        trainHistoryMenuitem = menu?.findItem(R.id.train_history_menu_btn)
        return true
    }
    fun showTrainHistoryButton(show: Boolean) {
        trainHistoryMenuitem?.isVisible = show
    }
    fun disableTrainHistoryButton(enabled: Boolean) {
        trainHistoryMenuitem?.isEnabled = enabled
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.train_history_menu_btn -> {
                val intent = Intent(this, TrainHistoryActivity::class.java)
                startActivity(intent)
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }


    // Start and stop listening to wear data when the activity is started and stopped
    override fun onStart() {
        super.onStart()
        Wearable.getMessageClient(this).addListener(wearDataListener)
    }

    override fun onStop() {
        super.onStop()
        Wearable.getMessageClient(this).removeListener(wearDataListener)
    }


    // Request notification permission
    fun requestNotificationPermission() {
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
                if (isGranted) {

                } else {

                }
            }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {

        } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /*override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val currentDestination = navController.currentDestination

        // Check if the current destination is within a bottom navigation tab
        if (currentDestination?.id == R.id.navigation_predict ||
            currentDestination?.id == R.id.navigation_train ||
            currentDestination?.id == R.id.navigation_settings
        ) {
            // Handle the back press within the bottom navigation tab.
            // For example, you could pop the back stack to a specific point within the tab's flow.
            // Or, you could simply return true to consume the event and do nothing.
            if (!navController.popBackStack()) {
                // If there's nothing left to pop, do nothing or handle as you see fit.
                return false;
            }
            return true;
        }

        // Otherwise, let the navigation controller handle the back press.
        return navController.navigateUp() || super.onSupportNavigateUp()
    }*/
}