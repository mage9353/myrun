package com.myrunsync

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.myrunsync.config.SupabaseConfigStore
import com.myrunsync.config.SupabaseConfigValidator
import com.myrunsync.health.HealthConnectRunningReader
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.supabase.SupabaseRunningRepository
import com.myrunsync.sync.RunningSyncManager
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var etSupabaseUrl: EditText
    private lateinit var etSupabaseAnonKey: EditText
    private lateinit var cbShowAnonKey: CheckBox
    private lateinit var btnSaveSettings: Button
    private lateinit var btnPermissions: Button
    private lateinit var btnSync: Button

    private val configStore by lazy { SupabaseConfigStore(this) }
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(this) }
    private val healthReader by lazy { HealthConnectRunningReader(healthConnectClient) }

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val missing = HealthConnectRunningReader.REQUIRED_PERMISSIONS - granted
        if (missing.isEmpty()) {
            tvStatus.text = "All Health Connect permissions granted. Ready to sync."
        } else {
            tvStatus.text = "Missing ${missing.size} Health Connect permission(s)."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        etSupabaseUrl = findViewById(R.id.etSupabaseUrl)
        etSupabaseAnonKey = findViewById(R.id.etSupabaseAnonKey)
        cbShowAnonKey = findViewById(R.id.cbShowAnonKey)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnSync = findViewById(R.id.btnSync)

        val initialConfig = configStore.load()
        etSupabaseUrl.setText(initialConfig.url)
        etSupabaseAnonKey.setText(initialConfig.anonKey)

        cbShowAnonKey.setOnCheckedChangeListener { _, isChecked ->
            etSupabaseAnonKey.inputType = if (isChecked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etSupabaseAnonKey.setSelection(etSupabaseAnonKey.text?.length ?: 0)
        }

        btnSaveSettings.setOnClickListener {
            val rawUrl = etSupabaseUrl.text?.toString().orEmpty()
            val rawKey = etSupabaseAnonKey.text?.toString().orEmpty()
            val validationError = SupabaseConfigValidator.validate(rawUrl, rawKey)
            if (validationError != null) {
                tvStatus.text = validationError
                return@setOnClickListener
            }

            val saved = configStore.save(rawUrl, rawKey)
            etSupabaseUrl.setText(saved.url)
            etSupabaseAnonKey.setText(saved.anonKey)
            tvStatus.text = getString(R.string.status_settings_saved)
        }

        if (!HealthConnectRunningReader.isAvailable(this)) {
            tvStatus.text = "Health Connect is not available on this device."
            btnPermissions.isEnabled = false
            btnSync.isEnabled = false
            return
        }

        if (!initialConfig.isComplete()) {
            tvStatus.text = getString(R.string.status_missing_credentials)
        }

        btnPermissions.setOnClickListener {
            permissionLauncher.launch(HealthConnectRunningReader.REQUIRED_PERMISSIONS)
        }

        btnSync.setOnClickListener {
            syncRecentRuns()
        }
    }

    private fun syncRecentRuns() {
        val storedConfig = configStore.load()
        val rawUrl = etSupabaseUrl.text?.toString().orEmpty().ifBlank { storedConfig.url }
        val rawKey = etSupabaseAnonKey.text?.toString().orEmpty().ifBlank { storedConfig.anonKey }

        val validationError = SupabaseConfigValidator.validate(rawUrl, rawKey)
        if (validationError != null) {
            tvStatus.text = validationError
            return
        }

        val savedConfig = configStore.save(rawUrl, rawKey)
        etSupabaseUrl.setText(savedConfig.url)
        etSupabaseAnonKey.setText(savedConfig.anonKey)

        lifecycleScope.launch {
            btnSync.isEnabled = false
            try {
                if (!healthReader.hasAllPermissions()) {
                    tvStatus.text = "Health Connect permissions required before syncing."
                    permissionLauncher.launch(HealthConnectRunningReader.REQUIRED_PERMISSIONS)
                    return@launch
                }

                tvStatus.text = "Reading Health Connect runs and uploading to Supabase..."
                val supabaseClient = SupabaseRunningRepository.createSupabaseClient(
                    supabaseUrl = savedConfig.url,
                    supabaseAnonKey = savedConfig.anonKey
                )
                val repository = SupabaseRunningRepository(supabaseClient)
                val syncManager = RunningSyncManager(healthReader, repository)

                val endTime = Instant.now()
                val startTime = endTime.minus(Duration.ofDays(30))
                val zone2 = HeartRateZone2Config.fromMaxHeartRate(maxHrBpm = 190)

                val result = syncManager.syncRuns(
                    startTime = startTime,
                    endTime = endTime,
                    zone2Config = zone2
                )

                val latest = result.syncedRecords.maxByOrNull { it.runDate }
                tvStatus.text = if (latest == null) {
                    "No running sessions found in the last 30 days."
                } else {
                    "Synced ${result.sessionsSynced} run(s) to running_logs.\n" +
                        "Latest: ${latest.runDate} (${latest.workoutType}) · ${latest.distanceKm} km · " +
                        "HR ${latest.avgHr ?: "-"} bpm · Z2 ${latest.zone2Pct ?: "-"}% · " +
                        "${latest.avgCadence ?: "-"} spm"
                }
            } catch (e: Exception) {
                tvStatus.text = "Sync failed: ${e.localizedMessage ?: e::class.java.simpleName}"
            } finally {
                btnSync.isEnabled = true
            }
        }
    }
}
