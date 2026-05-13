package de.dhde.wifilogger

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import de.dhde.wifilogger.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var adapter: WifiEventAdapter
    private lateinit var prefs: SharedPreferences

    private val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) toggleService()
        else Toast.makeText(this, "Standortberechtigung wird benötigt", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        prefs = getSharedPreferences("wifi_logger_prefs", MODE_PRIVATE)
        adapter = WifiEventAdapter()
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = this@MainActivity.adapter
            setHasFixedSize(true)
        }
        viewModel.events.observe(this) { events ->
            adapter.submitList(events)
            binding.eventCountText.text = "${events.size} Ereignisse"
            if (events.isNotEmpty()) binding.recyclerView.scrollToPosition(0)
        }
        binding.fabToggle.setOnClickListener { checkPermissionsAndToggle() }
        binding.btnClear.setOnClickListener { confirmDeleteAll() }
        updateFabState()
        
        // Die gesamte Leiste unten auch zum Starten/Stoppen nutzbar machen
        binding.bottomControlCard.setOnClickListener { checkPermissionsAndToggle() }
    }

    override fun onResume() { super.onResume(); updateFabState() }

    private fun checkPermissionsAndToggle() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) toggleService()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun toggleService() {
        if (WifiMonitorService.isRunning)
            stopService(Intent(this, WifiMonitorService::class.java).apply { action = WifiMonitorService.ACTION_STOP })
        else
            startForegroundService(Intent(this, WifiMonitorService::class.java).apply { action = WifiMonitorService.ACTION_START })
        Handler(mainLooper).postDelayed({ updateFabState() }, 300)
    }

    private fun updateFabState() {
        if (WifiMonitorService.isRunning) {
            binding.fabToggle.setImageResource(android.R.drawable.ic_media_pause)
            binding.fabToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_active)
            binding.statusBadge.text = "LOGGING AKTIV"
            binding.statusBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_active)
        } else {
            binding.fabToggle.setImageResource(android.R.drawable.ic_media_play)
            binding.fabToggle.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_inactive)
            binding.statusBadge.text = "LOGGING INAKTIV"
            binding.statusBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_inactive)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.menu_autostart)?.isChecked = prefs.getBoolean("autostart", false)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.menu_export -> { exportCsv(); true }
        R.id.menu_delete_old -> { showDeleteDialog(); true }
        R.id.menu_delete_all -> { confirmDeleteAll(); true }
        R.id.menu_autostart -> {
            val v = !item.isChecked; item.isChecked = v
            prefs.edit().putBoolean("autostart", v).apply()
            Toast.makeText(this, if (v) "Autostart aktiviert" else "Autostart deaktiviert", Toast.LENGTH_SHORT).show(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            val csv = viewModel.exportCsv()
            val file = File(cacheDir, "wifi_log_${System.currentTimeMillis()}.csv").apply { writeText(csv) }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "CSV exportieren"))
        }
    }

    private fun showDeleteDialog() {
        AlertDialog.Builder(this).setTitle("Alte Eintraege loeschen")
            .setItems(arrayOf("Aelter als 7 Tage", "Aelter als 30 Tage", "Aelter als 90 Tage")) { _, i ->
                viewModel.deleteOlderThan(intArrayOf(7, 30, 90)[i])
                Toast.makeText(this, "Eintraege geloescht", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Abbrechen", null).show()
    }

    private fun confirmDeleteAll() {
        AlertDialog.Builder(this).setTitle("Alle Eintraege loeschen?")
            .setMessage("Das kann nicht rueckgaengig gemacht werden.")
            .setPositiveButton("Loeschen") { _, _ ->
                viewModel.deleteAll()
                Toast.makeText(this, "Geloescht", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Abbrechen", null).show()
    }
}
