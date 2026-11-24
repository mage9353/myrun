package com.myrunsync

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.myrunsync.health.HealthConnectRunningReader
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.supabase.SupabaseRunningRepository
import com.myrunsync.sync.RunningSyncManager
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var btnPermissions: Button
    private lateinit var btnSync: Button

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
        btnPermissions = findViewById(R.id.btnPermissions)
        btnSync = findViewById(R.id.btnSync)

        if (!HealthConnectRunningReader.isAvailable(this)) {
            tvStatus.text = "Health Connect is not available on this device."
            btnPermissions.isEnabled = false
            btnSync.isEnabled = false
            return
        }

        btnPermissions.setOnClickListener {
            permissionLauncher.launch(HealthConnectRunningReader.REQUIRED_PERMISSIONS)
        }

        btnSync.setOnClickListener {
            syncRecentRuns()
        }
    }

    private fun syncRecentRuns() {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            tvStatus.text = "Set SUPABASE_URL and SUPABASE_ANON_KEY in local.properties first."
            return
        }

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
                    supabaseUrl = BuildConfig.SUPABASE_URL,
                    supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY
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
