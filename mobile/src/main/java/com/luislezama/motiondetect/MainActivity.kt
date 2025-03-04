package com.luislezama.motiondetect

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.NavigationUI.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.luislezama.motiondetect.data.RecognitionForegroundServiceHolder
import com.luislezama.motiondetect.data.TrainForegroundServiceHolder
import com.luislezama.motiondetect.databinding.ActivityMainBinding
import com.luislezama.motiondetect.deviceconnection.ConnectionManager
import com.luislezama.motiondetect.ui.RecognitionFragment
import com.luislezama.motiondetect.ui.TrainFragment
import com.luislezama.motiondetect.ui.TrainHistoryActivity

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private var trainHistoryMenuItem: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_recognition, R.id.navigation_train, R.id.navigation_settings
            )
        )

        setSupportActionBar(findViewById(R.id.toolbar))
        setupActionBarWithNavController(this, navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        ConnectionManager.initialize(applicationContext)

        requestNotificationPermission()


        val fragmentToOpen = intent.getStringExtra("FRAGMENT_TO_OPEN")
        when {
            fragmentToOpen == "recognition" || RecognitionForegroundServiceHolder.service != null -> {
                navView.selectedItemId = R.id.navigation_recognition
                supportFragmentManager.beginTransaction().let {
                    it.replace(R.id.nav_host_fragment_activity_main, RecognitionFragment())
                    it.commit()
                }
            }
            fragmentToOpen == "train" || TrainForegroundServiceHolder.service != null -> {
                navView.selectedItemId = R.id.navigation_train
                supportFragmentManager.beginTransaction().let {
                    it.replace(R.id.nav_host_fragment_activity_main, TrainFragment())
                    it.commit()
                }
            }
        }


        navView.setOnItemSelectedListener { item ->
            val currentDestinationId = navController.currentDestination?.id
            var continueNavigation = true

            when (item.itemId) {
                R.id.navigation_recognition -> {
                    continueNavigation =
                        (RecognitionForegroundServiceHolder.service != null && currentDestinationId != R.id.navigation_recognition) || (RecognitionForegroundServiceHolder.service == null)

                    if (TrainForegroundServiceHolder.service != null) {
                        continueNavigation = false
                        Toast.makeText(this, "Train service is running", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.navigation_train -> {
                    continueNavigation =
                        (TrainForegroundServiceHolder.service != null && currentDestinationId != R.id.navigation_train) || (TrainForegroundServiceHolder.service == null)

                    if (RecognitionForegroundServiceHolder.service != null) {
                        continueNavigation = false
                        Toast.makeText(this, "Recognition service is running", Toast.LENGTH_SHORT).show()
                    }
                }
                R.id.navigation_settings -> {
                    if (TrainForegroundServiceHolder.service != null) {
                        continueNavigation = false
                        Toast.makeText(this, "Train service is running", Toast.LENGTH_SHORT).show()
                    }

                    if (RecognitionForegroundServiceHolder.service != null) {
                        continueNavigation = false
                        Toast.makeText(this, "Recognition service is running", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (continueNavigation) {
                NavigationUI.onNavDestinationSelected(item, navController)
                true
            }
            else return@setOnItemSelectedListener false
        }
    }


    // Train history button only visible in the train fragment
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_train, menu)
        trainHistoryMenuItem = menu?.findItem(R.id.train_history_menu_btn)
        showTrainHistoryButton(false)
        return true
    }
    fun showTrainHistoryButton(show: Boolean) {
        trainHistoryMenuItem?.isVisible = show
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.train_history_menu_btn -> {
                if (TrainForegroundServiceHolder.service == null) {
                    val intent = Intent(this, TrainHistoryActivity::class.java)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Train service is running", Toast.LENGTH_SHORT).show()
                }

                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
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