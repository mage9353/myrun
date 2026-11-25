package com.myrunsync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.myrunsync.config.SupabaseConfig
import com.myrunsync.config.SupabaseConfigStore
import com.myrunsync.config.SupabaseConfigValidator
import com.myrunsync.config.SyncDataSource
import com.myrunsync.config.SyncPreferencesStore
import com.myrunsync.config.SyncScheduleConfig
import com.myrunsync.health.HealthConnectRunningReader
import com.myrunsync.health.RunningTelemetryReader
import com.myrunsync.health.SamsungHealthRunningReader
import com.myrunsync.model.HeartRateZone2Config
import com.myrunsync.supabase.SupabaseRunningRepository
import com.myrunsync.sync.BackgroundSyncScheduler
import com.myrunsync.sync.RunningSyncManager
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var layoutSupabaseSummary: LinearLayout
    private lateinit var tvSupabaseSummary: TextView
    private lateinit var btnEditSettings: Button
    private lateinit var layoutSupabaseEditor: LinearLayout
    private lateinit var etSupabaseUrl: EditText
    private lateinit var etSupabaseAnonKey: EditText
    private lateinit var cbShowAnonKey: CheckBox
    private lateinit var btnSaveSettings: Button
    private lateinit var btnCancelEdit: Button
    private lateinit var etZone2MinBpm: EditText
    private lateinit var etZone2MaxBpm: EditText
    private lateinit var btnSaveZone2: Button
    private lateinit var layoutDataSourceRow: LinearLayout
    private lateinit var spinnerDataSource: Spinner
    private lateinit var switchAutoSyncOnOpen: SwitchCompat
    private lateinit var switchBgAutoSync: SwitchCompat
    private lateinit var spinnerSyncInterval: Spinner
    private lateinit var btnPermissions: Button
    private lateinit var btnOpenHcSettings: Button
    private lateinit var btnSync: Button

    private val configStore by lazy { SupabaseConfigStore(this) }
    private val syncPrefs by lazy { SyncPreferencesStore(this) }
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(this) }
    private val healthReader by lazy { HealthConnectRunningReader(healthConnectClient) }
    private val samsungReader by lazy { SamsungHealthRunningReader(this) }

    private var isUpdatingUi = false
    private var pendingEnableBackgroundSync = false
    private var isSyncing = false

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        val missingForeground = HealthConnectRunningReader.REQUIRED_PERMISSIONS - granted
        val bgSupported = healthReader.isBackgroundReadSupported()
        val bgGranted = HealthConnectRunningReader.BACKGROUND_PERMISSION in granted

        if (pendingEnableBackgroundSync) {
            pendingEnableBackgroundSync = false
            if (missingForeground.isEmpty() && bgGranted) {
                val interval = syncPrefs.load().backgroundIntervalMinutes
                syncPrefs.setBackgroundAutoSyncEnabled(true)
                setBgSwitchChecked(true)
                spinnerSyncInterval.isEnabled = true
                BackgroundSyncScheduler.schedule(this, interval)
                tvStatus.text = "Background auto-sync enabled (${SyncScheduleConfig.labelForMinutes(interval)})."
            } else {
                syncPrefs.setBackgroundAutoSyncEnabled(false)
                setBgSwitchChecked(false)
                spinnerSyncInterval.isEnabled = false
                tvStatus.text = if (missingForeground.isNotEmpty()) {
                    "Missing ${missingForeground.size} Health Connect permission(s)."
                } else {
                    "READ_HEALTH_DATA_IN_BACKGROUND permission is required for background auto-sync. You can also allow it in Health Connect Settings."
                }
            }
            return@registerForActivityResult
        }

        tvStatus.text = when {
            missingForeground.isEmpty() && (!bgSupported || bgGranted) ->
                "All Health Connect permissions granted. Ready to sync."
            missingForeground.isEmpty() ->
                "Running read permissions granted. Background read permission is not granted yet."
            else ->
                "Missing ${missingForeground.size} Health Connect permission(s)."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLastSync = findViewById(R.id.tvLastSync)
        layoutSupabaseSummary = findViewById(R.id.layoutSupabaseSummary)
        tvSupabaseSummary = findViewById(R.id.tvSupabaseSummary)
        btnEditSettings = findViewById(R.id.btnEditSettings)
        layoutSupabaseEditor = findViewById(R.id.layoutSupabaseEditor)
        etSupabaseUrl = findViewById(R.id.etSupabaseUrl)
        etSupabaseAnonKey = findViewById(R.id.etSupabaseAnonKey)
        cbShowAnonKey = findViewById(R.id.cbShowAnonKey)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        etZone2MinBpm = findViewById(R.id.etZone2MinBpm)
        etZone2MaxBpm = findViewById(R.id.etZone2MaxBpm)
        btnSaveZone2 = findViewById(R.id.btnSaveZone2)
        layoutDataSourceRow = findViewById(R.id.layoutDataSourceRow)
        spinnerDataSource = findViewById(R.id.spinnerDataSource)
        switchAutoSyncOnOpen = findViewById(R.id.switchAutoSyncOnOpen)
        switchBgAutoSync = findViewById(R.id.switchBgAutoSync)
        spinnerSyncInterval = findViewById(R.id.spinnerSyncInterval)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnOpenHcSettings = findViewById(R.id.btnOpenHcSettings)
        btnSync = findViewById(R.id.btnSync)

        val initialConfig = configStore.load()
        etSupabaseUrl.setText(initialConfig.url)
        etSupabaseAnonKey.setText(initialConfig.anonKey)
        etZone2MinBpm.setText(initialConfig.zone2MinBpm.toString())
        etZone2MaxBpm.setText(initialConfig.zone2MaxBpm.toString())
        updateSupabaseSectionVisibility(editing = false, config = initialConfig)

        val initialSyncSettings = syncPrefs.load()
        val dataSources = SyncDataSource.entries
        val sourceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            dataSources.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerDataSource.adapter = sourceAdapter
        layoutDataSourceRow.visibility = if (SamsungHealthRunningReader.isSupported()) View.VISIBLE else View.GONE

        val intervalLabels = SyncScheduleConfig.INTERVAL_OPTIONS.map { it.label }
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            intervalLabels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerSyncInterval.adapter = spinnerAdapter

        isUpdatingUi = true
        spinnerDataSource.setSelection(dataSources.indexOf(initialSyncSettings.dataSource).coerceAtLeast(0), false)
        switchAutoSyncOnOpen.isChecked = initialSyncSettings.autoSyncOnOpen
        switchBgAutoSync.isChecked = initialSyncSettings.backgroundAutoSyncEnabled
        spinnerSyncInterval.setSelection(
            SyncScheduleConfig.indexForMinutes(initialSyncSettings.backgroundIntervalMinutes),
            false
        )
        spinnerSyncInterval.isEnabled = initialSyncSettings.backgroundAutoSyncEnabled
        isUpdatingUi = false

        refreshLastSyncDisplay()
        applyDataSourceUi(initialSyncSettings.dataSource)

        cbShowAnonKey.setOnCheckedChangeListener { _, isChecked ->
            etSupabaseAnonKey.inputType = if (isChecked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            etSupabaseAnonKey.setSelection(etSupabaseAnonKey.text?.length ?: 0)
        }

        btnEditSettings.setOnClickListener {
            val currentConfig = configStore.load()
            etSupabaseUrl.setText(currentConfig.url)
            etSupabaseAnonKey.setText(currentConfig.anonKey)
            updateSupabaseSectionVisibility(editing = true, config = currentConfig)
        }

        btnCancelEdit.setOnClickListener {
            val currentConfig = configStore.load()
            etSupabaseUrl.setText(currentConfig.url)
            etSupabaseAnonKey.setText(currentConfig.anonKey)
            updateSupabaseSectionVisibility(editing = false, config = currentConfig)
        }

        btnSaveSettings.setOnClickListener {
            val rawUrl = etSupabaseUrl.text?.toString().orEmpty()
            val rawKey = etSupabaseAnonKey.text?.toString().orEmpty()
            val validationError = SupabaseConfigValidator.validate(rawUrl, rawKey)
            if (validationError != null) {
                tvStatus.text = validationError
                return@setOnClickListener
            }

            val current = configStore.load()
            val saved = configStore.save(
                url = rawUrl,
                anonKey = rawKey,
                zone2MinBpm = current.zone2MinBpm,
                zone2MaxBpm = current.zone2MaxBpm
            )
            etSupabaseUrl.setText(saved.url)
            etSupabaseAnonKey.setText(saved.anonKey)
            updateSupabaseSectionVisibility(editing = false, config = saved)
            tvStatus.text = getString(R.string.status_settings_saved)
        }

        btnSaveZone2.setOnClickListener {
            val minRaw = etZone2MinBpm.text?.toString().orEmpty()
            val maxRaw = etZone2MaxBpm.text?.toString().orEmpty()
            val zone2Error = SupabaseConfigValidator.validateZone2(minRaw, maxRaw)
            if (zone2Error != null) {
                tvStatus.text = zone2Error
                return@setOnClickListener
            }
            val (minBpm, maxBpm) = SupabaseConfigValidator.parseZone2(minRaw, maxRaw)
                ?: return@setOnClickListener
            val current = configStore.load()
            val saved = configStore.save(
                url = current.url,
                anonKey = current.anonKey,
                zone2MinBpm = minBpm,
                zone2MaxBpm = maxBpm
            )
            etZone2MinBpm.setText(saved.zone2MinBpm.toString())
            etZone2MaxBpm.setText(saved.zone2MaxBpm.toString())
            tvStatus.text = getString(R.string.status_zone2_saved, saved.zone2MinBpm, saved.zone2MaxBpm)
        }

        spinnerDataSource.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isUpdatingUi) return
                val selected = dataSources[position]
                val current = syncPrefs.load().dataSource
                if (selected != current) {
                    syncPrefs.setDataSource(selected)
                    applyDataSourceUi(selected)
                    tvStatus.text = "Data source switched to ${selected.label}."
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        switchAutoSyncOnOpen.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            syncPrefs.setAutoSyncOnOpen(isChecked)
            tvStatus.text = if (isChecked) {
                "Auto-sync when opening app enabled."
            } else {
                "Auto-sync when opening app disabled."
            }
        }

        spinnerSyncInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (isUpdatingUi) return
                val selected = SyncScheduleConfig.INTERVAL_OPTIONS[position]
                val current = syncPrefs.load()
                if (selected.minutes != current.backgroundIntervalMinutes) {
                    syncPrefs.setBackgroundIntervalMinutes(selected.minutes)
                    if (current.backgroundAutoSyncEnabled) {
                        BackgroundSyncScheduler.schedule(this@MainActivity, selected.minutes)
                        tvStatus.text = "Background auto-sync interval updated to ${selected.label}."
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        switchBgAutoSync.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            if (!isChecked) {
                syncPrefs.setBackgroundAutoSyncEnabled(false)
                BackgroundSyncScheduler.cancel(this)
                spinnerSyncInterval.isEnabled = false
                tvStatus.text = "Background periodic auto-sync disabled."
                return@setOnCheckedChangeListener
            }

            val currentSource = syncPrefs.load().dataSource
            lifecycleScope.launch {
                if (currentSource == SyncDataSource.SAMSUNG_HEALTH && SamsungHealthRunningReader.isSupported()) {
                    try {
                        val hasPerms = samsungReader.hasRequiredPermissions()
                        val ready = if (hasPerms) {
                            true
                        } else {
                            samsungReader.requestPermissions(this@MainActivity)
                            samsungReader.hasRequiredPermissions()
                        }
                        if (ready) {
                            val interval = syncPrefs.load().backgroundIntervalMinutes
                            syncPrefs.setBackgroundAutoSyncEnabled(true)
                            spinnerSyncInterval.isEnabled = true
                            BackgroundSyncScheduler.schedule(this@MainActivity, interval)
                            tvStatus.text = "Samsung Health background auto-sync enabled (${SyncScheduleConfig.labelForMinutes(interval)})."
                        } else {
                            syncPrefs.setBackgroundAutoSyncEnabled(false)
                            setBgSwitchChecked(false)
                            spinnerSyncInterval.isEnabled = false
                            tvStatus.text = "Samsung Health Exercise read permission is required for background auto-sync."
                        }
                    } catch (e: Exception) {
                        syncPrefs.setBackgroundAutoSyncEnabled(false)
                        setBgSwitchChecked(false)
                        spinnerSyncInterval.isEnabled = false
                        tvStatus.text = SamsungHealthRunningReader.formatErrorMessage(e)
                    }
                    return@launch
                }

                if (!healthReader.isBackgroundReadSupported()) {
                    setBgSwitchChecked(false)
                    spinnerSyncInterval.isEnabled = false
                    tvStatus.text = "This device's Health Connect version does not support background read."
                    return@launch
                }

                if (healthReader.hasAllPermissions() && healthReader.hasBackgroundPermission()) {
                    val interval = syncPrefs.load().backgroundIntervalMinutes
                    syncPrefs.setBackgroundAutoSyncEnabled(true)
                    spinnerSyncInterval.isEnabled = true
                    BackgroundSyncScheduler.schedule(this@MainActivity, interval)
                    tvStatus.text = "Background auto-sync enabled (${SyncScheduleConfig.labelForMinutes(interval)})."
                } else {
                    pendingEnableBackgroundSync = true
                    permissionLauncher.launch(healthReader.permissionsToRequest(includeBackground = true))
                }
            }
        }

        if (!initialConfig.isComplete()) {
            tvStatus.text = getString(R.string.status_missing_credentials)
        }

        BackgroundSyncScheduler.applyFromPreferences(this)

        btnPermissions.setOnClickListener {
            val source = syncPrefs.load().dataSource
            if (source == SyncDataSource.SAMSUNG_HEALTH && SamsungHealthRunningReader.isSupported()) {
                requestSamsungHealthPermissions()
            } else {
                pendingEnableBackgroundSync = false
                permissionLauncher.launch(healthReader.permissionsToRequest(includeBackground = true))
            }
        }

        btnOpenHcSettings.setOnClickListener {
            runCatching {
                startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            }.onFailure {
                tvStatus.text = "Unable to open Health Connect Settings on this device."
            }
        }

        btnSync.setOnClickListener {
            syncRecentRuns(triggerLabel = "Manual")
        }

        if (savedInstanceState == null && initialSyncSettings.autoSyncOnOpen && initialConfig.isComplete()) {
            lifecycleScope.launch {
                val ready = if (initialSyncSettings.dataSource == SyncDataSource.SAMSUNG_HEALTH &&
                    SamsungHealthRunningReader.isSupported()
                ) {
                    runCatching { samsungReader.hasRequiredPermissions() }.getOrDefault(false)
                } else {
                    HealthConnectRunningReader.isAvailable(this@MainActivity) &&
                        runCatching { healthReader.hasAllPermissions() }.getOrDefault(false)
                }
                if (ready) {
                    syncRecentRuns(triggerLabel = "App Open")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLastSyncDisplay()
    }

    private fun applyDataSourceUi(source: SyncDataSource) {
        val useSamsung = source == SyncDataSource.SAMSUNG_HEALTH && SamsungHealthRunningReader.isSupported()
        if (useSamsung) {
            val installed = SamsungHealthRunningReader.isSamsungHealthInstalled(this)
            btnPermissions.text = getString(R.string.btn_grant_samsung_permissions)
            btnOpenHcSettings.visibility = View.GONE
            btnPermissions.isEnabled = installed
            btnSync.isEnabled = installed
            switchAutoSyncOnOpen.isEnabled = installed
            switchBgAutoSync.isEnabled = installed
            spinnerSyncInterval.isEnabled = installed && syncPrefs.load().backgroundAutoSyncEnabled
            if (!installed) {
                tvStatus.text = "Samsung Health app is not installed on this device."
            }
        } else {
            val hcAvailable = HealthConnectRunningReader.isAvailable(this)
            btnPermissions.text = getString(R.string.btn_grant_permissions)
            btnOpenHcSettings.visibility = View.VISIBLE
            btnPermissions.isEnabled = hcAvailable
            btnOpenHcSettings.isEnabled = hcAvailable
            btnSync.isEnabled = hcAvailable
            switchAutoSyncOnOpen.isEnabled = hcAvailable
            switchBgAutoSync.isEnabled = hcAvailable
            spinnerSyncInterval.isEnabled = hcAvailable && syncPrefs.load().backgroundAutoSyncEnabled
            if (!hcAvailable) {
                tvStatus.text = "Health Connect is not available on this device."
            }
        }
    }

    private fun requestSamsungHealthPermissions() {
        lifecycleScope.launch {
            btnPermissions.isEnabled = false
            try {
                val grantedTypes = samsungReader.requestPermissions(this@MainActivity)
                val hasExercise = samsungReader.hasRequiredPermissions()
                val hasLocation = samsungReader.hasLocationPermission()
                tvStatus.text = when {
                    hasExercise && hasLocation ->
                        "Samsung Health permissions granted (Exercise + GPS Route + HR). Ready to sync."
                    hasExercise ->
                        "Samsung Health Exercise permission granted (GPS Route permission not granted)."
                    else ->
                        "Samsung Health permissions not granted (${grantedTypes.size} granted)."
                }
            } catch (e: Exception) {
                tvStatus.text = SamsungHealthRunningReader.formatErrorMessage(e)
            } finally {
                btnPermissions.isEnabled = true
            }
        }
    }

    private fun updateSupabaseSectionVisibility(editing: Boolean, config: SupabaseConfig) {
        if (!editing && config.isComplete()) {
            val host = Uri.parse(config.url).host ?: config.url
            tvSupabaseSummary.text = getString(R.string.supabase_connected_summary, host)
            layoutSupabaseSummary.visibility = View.VISIBLE
            layoutSupabaseEditor.visibility = View.GONE
        } else {
            layoutSupabaseSummary.visibility = View.GONE
            layoutSupabaseEditor.visibility = View.VISIBLE
            btnCancelEdit.visibility = if (config.isComplete()) View.VISIBLE else View.GONE
        }
    }

    private fun setBgSwitchChecked(checked: Boolean) {
        isUpdatingUi = true
        switchBgAutoSync.isChecked = checked
        isUpdatingUi = false
    }

    private fun refreshLastSyncDisplay() {
        val summary = syncPrefs.load().lastSyncSummary
        tvLastSync.text = summary ?: getString(R.string.last_sync_none)
    }

    private fun syncRecentRuns(triggerLabel: String = "Manual") {
        if (isSyncing) return

        val storedConfig = configStore.load()
        val isEditorVisible = layoutSupabaseEditor.visibility == View.VISIBLE
        val rawUrl = if (isEditorVisible) {
            etSupabaseUrl.text?.toString().orEmpty().ifBlank { storedConfig.url }
        } else {
            storedConfig.url
        }
        val rawKey = if (isEditorVisible) {
            etSupabaseAnonKey.text?.toString().orEmpty().ifBlank { storedConfig.anonKey }
        } else {
            storedConfig.anonKey
        }

        val validationError = SupabaseConfigValidator.validate(rawUrl, rawKey)
        if (validationError != null) {
            tvStatus.text = validationError
            return
        }

        val minRaw = etZone2MinBpm.text?.toString().orEmpty()
            .ifBlank { storedConfig.zone2MinBpm.toString() }
        val maxRaw = etZone2MaxBpm.text?.toString().orEmpty()
            .ifBlank { storedConfig.zone2MaxBpm.toString() }
        val zone2Error = SupabaseConfigValidator.validateZone2(minRaw, maxRaw)
        if (zone2Error != null) {
            tvStatus.text = zone2Error
            return
        }
        val (zone2Min, zone2Max) = SupabaseConfigValidator.parseZone2(minRaw, maxRaw)
            ?: return

        val savedConfig = configStore.save(
            url = rawUrl,
            anonKey = rawKey,
            zone2MinBpm = zone2Min,
            zone2MaxBpm = zone2Max
        )
        etSupabaseUrl.setText(savedConfig.url)
        etSupabaseAnonKey.setText(savedConfig.anonKey)
        etZone2MinBpm.setText(savedConfig.zone2MinBpm.toString())
        etZone2MaxBpm.setText(savedConfig.zone2MaxBpm.toString())
        updateSupabaseSectionVisibility(editing = false, config = savedConfig)

        val selectedSource = syncPrefs.load().dataSource
        val useSamsung = selectedSource == SyncDataSource.SAMSUNG_HEALTH &&
            SamsungHealthRunningReader.isSupported()

        lifecycleScope.launch {
            isSyncing = true
            btnSync.isEnabled = false
            val supabaseClient = SupabaseRunningRepository.createSupabaseClient(
                supabaseUrl = savedConfig.url,
                supabaseAnonKey = savedConfig.anonKey
            )
            try {
                val reader: RunningTelemetryReader
                val sourceLabel: String
                if (useSamsung) {
                    if (!samsungReader.hasRequiredPermissions()) {
                        tvStatus.text = "Samsung Health permissions required before syncing."
                        samsungReader.requestPermissions(this@MainActivity)
                        if (!samsungReader.hasRequiredPermissions()) {
                            return@launch
                        }
                    }
                    reader = samsungReader
                    sourceLabel = "Samsung Health"
                } else {
                    if (!healthReader.hasAllPermissions()) {
                        tvStatus.text = "Health Connect permissions required before syncing."
                        permissionLauncher.launch(
                            healthReader.permissionsToRequest(includeBackground = switchBgAutoSync.isChecked)
                        )
                        return@launch
                    }
                    reader = healthReader
                    sourceLabel = "Health Connect"
                }

                tvStatus.text = "Reading $sourceLabel runs and uploading to Supabase..."
                val repository = SupabaseRunningRepository(supabaseClient)
                val syncManager = RunningSyncManager(reader, repository)

                val endTime = Instant.now()
                val startTime = endTime.minus(Duration.ofDays(30))
                val zone2 = HeartRateZone2Config(
                    minBpm = savedConfig.zone2MinBpm,
                    maxBpm = savedConfig.zone2MaxBpm
                )

                val result = syncManager.syncRuns(
                    startTime = startTime,
                    endTime = endTime,
                    zone2Config = zone2
                )

                val timeStr = TIME_FORMATTER.format(Instant.now())
                val latest = result.syncedRecords.maxByOrNull { it.runDate }
                if (latest == null) {
                    tvStatus.text = "No running/walking sessions found in $sourceLabel (last 30 days)."
                    syncPrefs.setLastSyncSummary("$timeStr · $sourceLabel ($triggerLabel): no runs in last 30 days")
                } else {
                    tvStatus.text = "Synced ${result.sessionsSynced} run(s) via $sourceLabel ($triggerLabel).\n" +
                        "Latest: ${latest.runDate} (${latest.workoutType}) · ${latest.distanceKm} km · " +
                        "HR ${latest.avgHr ?: "-"} bpm · Z2 ${latest.zone2Pct ?: "-"}% · " +
                        "${latest.avgCadence ?: "-"} spm"
                    syncPrefs.setLastSyncSummary("$timeStr · $sourceLabel ($triggerLabel): synced ${result.sessionsSynced} run(s)")
                }
                refreshLastSyncDisplay()
            } catch (e: Exception) {
                val message = if (useSamsung) {
                    SamsungHealthRunningReader.formatErrorMessage(e)
                } else {
                    e.localizedMessage ?: e::class.java.simpleName
                }
                tvStatus.text = "Sync failed: $message"
                val timeStr = TIME_FORMATTER.format(Instant.now())
                syncPrefs.setLastSyncSummary("$timeStr · $triggerLabel failed: $message")
                refreshLastSyncDisplay()
            } finally {
                runCatching { supabaseClient.close() }
                btnSync.isEnabled = true
                isSyncing = false
            }
        }
    }

    companion object {
        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
    }
}
