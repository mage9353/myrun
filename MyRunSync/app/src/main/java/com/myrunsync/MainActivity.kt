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
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.myrunsync.config.SupabaseConfig
import com.myrunsync.config.SupabaseConfigStore
import com.myrunsync.config.SupabaseConfigValidator
import com.myrunsync.config.SyncDataSource
import com.myrunsync.config.SyncPreferencesStore
import com.myrunsync.config.SyncScheduleConfig
import com.myrunsync.drive.GoogleDriveWebhookRepository
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

    private lateinit var tvActiveSourcePill: TextView
    private lateinit var tvLastSyncTime: TextView
    private lateinit var tvLastSyncDetail: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvPermissionBadge: TextView
    private lateinit var tvPermissionDetail: TextView
    private lateinit var btnPermissions: Button
    private lateinit var btnOpenHcSettings: Button
    private lateinit var btnSync: Button
    private lateinit var layoutDataSourceRow: LinearLayout
    private lateinit var spinnerDataSource: Spinner
    private lateinit var switchAutoSyncOnOpen: SwitchCompat
    private lateinit var switchBgAutoSync: SwitchCompat
    private lateinit var spinnerSyncInterval: Spinner
    private lateinit var layoutZone2Summary: LinearLayout
    private lateinit var tvZone2Summary: TextView
    private lateinit var btnEditZone2: Button
    private lateinit var layoutZone2Editor: LinearLayout
    private lateinit var etZone2MinBpm: EditText
    private lateinit var etZone2MaxBpm: EditText
    private lateinit var btnSaveZone2: Button
    private lateinit var btnCancelZone2: Button
    private lateinit var layoutSupabaseSummary: LinearLayout
    private lateinit var tvSupabaseSummary: TextView
    private lateinit var btnEditSettings: Button
    private lateinit var layoutSupabaseEditor: LinearLayout
    private lateinit var etSupabaseUrl: EditText
    private lateinit var etSupabaseAnonKey: EditText
    private lateinit var cbShowAnonKey: CheckBox
    private lateinit var btnSaveSettings: Button
    private lateinit var btnCancelEdit: Button
    private lateinit var layoutDriveSummary: LinearLayout
    private lateinit var tvDriveSummary: TextView
    private lateinit var btnEditDrive: Button
    private lateinit var layoutDriveEditor: LinearLayout
    private lateinit var etDriveWebhookUrl: EditText
    private lateinit var etDriveSecret: EditText
    private lateinit var btnSaveDrive: Button
    private lateinit var btnCancelDrive: Button

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
                tvStatus.text = "백그라운드 자동 동기화가 활성화되었습니다 (${SyncScheduleConfig.labelForMinutes(interval)})."
            } else {
                syncPrefs.setBackgroundAutoSyncEnabled(false)
                setBgSwitchChecked(false)
                spinnerSyncInterval.isEnabled = false
                tvStatus.text = if (missingForeground.isNotEmpty()) {
                    "Health Connect 필수 권한 ${missingForeground.size}개가 아직 허용되지 않았습니다."
                } else {
                    "백그라운드 자동 동기화를 위해 백그라운드 읽기 권한이 필요합니다. Health Connect 설정에서도 허용할 수 있습니다."
                }
            }
            refreshPermissionStatus()
            return@registerForActivityResult
        }

        tvStatus.text = when {
            missingForeground.isEmpty() && (!bgSupported || bgGranted) ->
                "Health Connect 권한이 모두 허용되었습니다."
            missingForeground.isEmpty() ->
                "기본 읽기 권한은 허용되었습니다 (백그라운드 권한 미허용)."
            else ->
                "Health Connect 필수 권한 ${missingForeground.size}개가 허용되지 않았습니다."
        }
        refreshPermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvActiveSourcePill = findViewById(R.id.tvActiveSourcePill)
        tvLastSyncTime = findViewById(R.id.tvLastSyncTime)
        tvLastSyncDetail = findViewById(R.id.tvLastSyncDetail)
        tvStatus = findViewById(R.id.tvStatus)
        tvPermissionBadge = findViewById(R.id.tvPermissionBadge)
        tvPermissionDetail = findViewById(R.id.tvPermissionDetail)
        btnPermissions = findViewById(R.id.btnPermissions)
        btnOpenHcSettings = findViewById(R.id.btnOpenHcSettings)
        btnSync = findViewById(R.id.btnSync)
        layoutDataSourceRow = findViewById(R.id.layoutDataSourceRow)
        spinnerDataSource = findViewById(R.id.spinnerDataSource)
        switchAutoSyncOnOpen = findViewById(R.id.switchAutoSyncOnOpen)
        switchBgAutoSync = findViewById(R.id.switchBgAutoSync)
        spinnerSyncInterval = findViewById(R.id.spinnerSyncInterval)
        layoutZone2Summary = findViewById(R.id.layoutZone2Summary)
        tvZone2Summary = findViewById(R.id.tvZone2Summary)
        btnEditZone2 = findViewById(R.id.btnEditZone2)
        layoutZone2Editor = findViewById(R.id.layoutZone2Editor)
        etZone2MinBpm = findViewById(R.id.etZone2MinBpm)
        etZone2MaxBpm = findViewById(R.id.etZone2MaxBpm)
        btnSaveZone2 = findViewById(R.id.btnSaveZone2)
        btnCancelZone2 = findViewById(R.id.btnCancelZone2)
        layoutSupabaseSummary = findViewById(R.id.layoutSupabaseSummary)
        tvSupabaseSummary = findViewById(R.id.tvSupabaseSummary)
        btnEditSettings = findViewById(R.id.btnEditSettings)
        layoutSupabaseEditor = findViewById(R.id.layoutSupabaseEditor)
        etSupabaseUrl = findViewById(R.id.etSupabaseUrl)
        etSupabaseAnonKey = findViewById(R.id.etSupabaseAnonKey)
        cbShowAnonKey = findViewById(R.id.cbShowAnonKey)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnCancelEdit = findViewById(R.id.btnCancelEdit)
        layoutDriveSummary = findViewById(R.id.layoutDriveSummary)
        tvDriveSummary = findViewById(R.id.tvDriveSummary)
        btnEditDrive = findViewById(R.id.btnEditDrive)
        layoutDriveEditor = findViewById(R.id.layoutDriveEditor)
        etDriveWebhookUrl = findViewById(R.id.etDriveWebhookUrl)
        etDriveSecret = findViewById(R.id.etDriveSecret)
        btnSaveDrive = findViewById(R.id.btnSaveDrive)
        btnCancelDrive = findViewById(R.id.btnCancelDrive)

        val initialConfig = configStore.load()
        etSupabaseUrl.setText(initialConfig.url)
        etSupabaseAnonKey.setText(initialConfig.anonKey)
        etZone2MinBpm.setText(initialConfig.zone2MinBpm.toString())
        etZone2MaxBpm.setText(initialConfig.zone2MaxBpm.toString())
        etDriveWebhookUrl.setText(initialConfig.googleDriveWebhookUrl)
        etDriveSecret.setText(initialConfig.googleDriveSecret)
        updateSupabaseSectionVisibility(editing = false, config = initialConfig)
        updateZone2SectionVisibility(editing = false, config = initialConfig)
        updateDriveSectionVisibility(editing = false, config = initialConfig)

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

        btnEditZone2.setOnClickListener {
            val currentConfig = configStore.load()
            etZone2MinBpm.setText(currentConfig.zone2MinBpm.toString())
            etZone2MaxBpm.setText(currentConfig.zone2MaxBpm.toString())
            updateZone2SectionVisibility(editing = true, config = currentConfig)
        }

        btnCancelZone2.setOnClickListener {
            val currentConfig = configStore.load()
            etZone2MinBpm.setText(currentConfig.zone2MinBpm.toString())
            etZone2MaxBpm.setText(currentConfig.zone2MaxBpm.toString())
            updateZone2SectionVisibility(editing = false, config = currentConfig)
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
            updateZone2SectionVisibility(editing = false, config = saved)
            tvStatus.text = getString(R.string.status_zone2_saved, saved.zone2MinBpm, saved.zone2MaxBpm)
        }

        btnEditDrive.setOnClickListener {
            val currentConfig = configStore.load()
            etDriveWebhookUrl.setText(currentConfig.googleDriveWebhookUrl)
            etDriveSecret.setText(currentConfig.googleDriveSecret)
            updateDriveSectionVisibility(editing = true, config = currentConfig)
        }

        btnCancelDrive.setOnClickListener {
            val currentConfig = configStore.load()
            etDriveWebhookUrl.setText(currentConfig.googleDriveWebhookUrl)
            etDriveSecret.setText(currentConfig.googleDriveSecret)
            updateDriveSectionVisibility(editing = false, config = currentConfig)
        }

        btnSaveDrive.setOnClickListener {
            val rawWebhookUrl = etDriveWebhookUrl.text?.toString().orEmpty()
            val rawSecret = etDriveSecret.text?.toString().orEmpty()
            val driveError = SupabaseConfigValidator.validateGoogleDriveWebhook(rawWebhookUrl)
            if (driveError != null) {
                tvStatus.text = driveError
                return@setOnClickListener
            }
            val current = configStore.load()
            val saved = configStore.save(
                url = current.url,
                anonKey = current.anonKey,
                zone2MinBpm = current.zone2MinBpm,
                zone2MaxBpm = current.zone2MaxBpm,
                googleDriveWebhookUrl = rawWebhookUrl,
                googleDriveSecret = if (rawWebhookUrl.isBlank()) "" else rawSecret
            )
            etDriveWebhookUrl.setText(saved.googleDriveWebhookUrl)
            etDriveSecret.setText(saved.googleDriveSecret)
            updateDriveSectionVisibility(editing = false, config = saved)
            tvStatus.text = if (saved.hasGoogleDriveWebhook()) {
                getString(R.string.status_drive_saved)
            } else {
                getString(R.string.status_drive_cleared)
            }
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
                    tvStatus.text = "데이터 소스가 ${selected.label}으로 변경되었습니다."
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        switchAutoSyncOnOpen.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            syncPrefs.setAutoSyncOnOpen(isChecked)
            tvStatus.text = if (isChecked) {
                "앱 실행 시 자동 동기화가 켜졌습니다."
            } else {
                "앱 실행 시 자동 동기화가 꺼졌습니다."
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
                        tvStatus.text = "백그라운드 동기화 주기가 ${selected.label}으로 변경되었습니다."
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
                tvStatus.text = "백그라운드 주기 자동 동기화가 꺼졌습니다."
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
                            tvStatus.text = "Samsung Health 백그라운드 자동 동기화가 활성화되었습니다 (${SyncScheduleConfig.labelForMinutes(interval)})."
                        } else {
                            syncPrefs.setBackgroundAutoSyncEnabled(false)
                            setBgSwitchChecked(false)
                            spinnerSyncInterval.isEnabled = false
                            tvStatus.text = "백그라운드 자동 동기화를 위해 Samsung Health 운동 읽기 권한이 필요합니다."
                        }
                    } catch (e: Exception) {
                        syncPrefs.setBackgroundAutoSyncEnabled(false)
                        setBgSwitchChecked(false)
                        spinnerSyncInterval.isEnabled = false
                        tvStatus.text = SamsungHealthRunningReader.formatErrorMessage(e)
                    } finally {
                        refreshPermissionStatus()
                    }
                    return@launch
                }

                if (!healthReader.isBackgroundReadSupported()) {
                    setBgSwitchChecked(false)
                    spinnerSyncInterval.isEnabled = false
                    tvStatus.text = "이 기기의 Health Connect 버전은 백그라운드 읽기를 지원하지 않습니다."
                    return@launch
                }

                if (healthReader.hasAllPermissions() && healthReader.hasBackgroundPermission()) {
                    val interval = syncPrefs.load().backgroundIntervalMinutes
                    syncPrefs.setBackgroundAutoSyncEnabled(true)
                    spinnerSyncInterval.isEnabled = true
                    BackgroundSyncScheduler.schedule(this@MainActivity, interval)
                    tvStatus.text = "백그라운드 자동 동기화가 활성화되었습니다 (${SyncScheduleConfig.labelForMinutes(interval)})."
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
                tvStatus.text = "Health Connect 설정 화면을 열 수 없습니다."
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
        refreshPermissionStatus()
    }

    private fun applyDataSourceUi(source: SyncDataSource) {
        val useSamsung = source == SyncDataSource.SAMSUNG_HEALTH && SamsungHealthRunningReader.isSupported()
        tvActiveSourcePill.text = if (useSamsung) "Samsung Health" else "Health Connect"

        if (useSamsung) {
            val installed = SamsungHealthRunningReader.isSamsungHealthInstalled(this)
            btnOpenHcSettings.visibility = View.GONE
            btnPermissions.isEnabled = installed
            btnSync.isEnabled = installed
            switchAutoSyncOnOpen.isEnabled = installed
            switchBgAutoSync.isEnabled = installed
            spinnerSyncInterval.isEnabled = installed && syncPrefs.load().backgroundAutoSyncEnabled
            if (!installed) {
                tvStatus.text = "이 기기에 삼성 헬스 앱이 설치되어 있지 않습니다."
            }
        } else {
            val hcAvailable = HealthConnectRunningReader.isAvailable(this)
            btnOpenHcSettings.visibility = View.VISIBLE
            btnPermissions.isEnabled = hcAvailable
            btnOpenHcSettings.isEnabled = hcAvailable
            btnSync.isEnabled = hcAvailable
            switchAutoSyncOnOpen.isEnabled = hcAvailable
            switchBgAutoSync.isEnabled = hcAvailable
            spinnerSyncInterval.isEnabled = hcAvailable && syncPrefs.load().backgroundAutoSyncEnabled
            if (!hcAvailable) {
                tvStatus.text = "이 기기에서 Health Connect를 사용할 수 없습니다."
            }
        }
        refreshPermissionStatus()
    }

    private fun refreshPermissionStatus() {
        val source = syncPrefs.load().dataSource
        val useSamsung = source == SyncDataSource.SAMSUNG_HEALTH && SamsungHealthRunningReader.isSupported()

        lifecycleScope.launch {
            if (useSamsung) {
                if (!SamsungHealthRunningReader.isSamsungHealthInstalled(this@MainActivity)) {
                    setPermissionUiState(
                        grantedAll = false,
                        badgeText = getString(R.string.perm_badge_needed),
                        isOkBadge = false,
                        detail = "삼성 헬스 앱이 설치되어 있지 않습니다.",
                        buttonText = getString(R.string.btn_grant_samsung_permissions)
                    )
                    return@launch
                }
                try {
                    val hasExercise = samsungReader.hasRequiredPermissions()
                    val hasLocation = samsungReader.hasLocationPermission()
                    val hasHr = samsungReader.hasHeartRatePermission()

                    when {
                        hasExercise && hasLocation && hasHr -> setPermissionUiState(
                            grantedAll = true,
                            badgeText = getString(R.string.perm_badge_granted),
                            isOkBadge = true,
                            detail = "운동 · GPS 경로 · 심박수 권한이 모두 허용되었습니다.",
                            buttonText = getString(R.string.btn_permissions_granted)
                        )
                        hasExercise -> {
                            val missing = buildList {
                                if (!hasLocation) add("GPS 경로")
                                if (!hasHr) add("심박수")
                            }.joinToString(", ")
                            setPermissionUiState(
                                grantedAll = false,
                                badgeText = getString(R.string.perm_badge_partial),
                                isOkBadge = false,
                                detail = "운동 읽기 허용됨 (미허용: $missing)",
                                buttonText = getString(R.string.btn_grant_samsung_permissions)
                            )
                        }
                        else -> setPermissionUiState(
                            grantedAll = false,
                            badgeText = getString(R.string.perm_badge_needed),
                            isOkBadge = false,
                            detail = "운동·케이던스·GPS 데이터를 읽으려면 아래 버튼을 눌러 허용해 주세요.",
                            buttonText = getString(R.string.btn_grant_samsung_permissions)
                        )
                    }
                } catch (e: Exception) {
                    setPermissionUiState(
                        grantedAll = false,
                        badgeText = getString(R.string.perm_badge_needed),
                        isOkBadge = false,
                        detail = SamsungHealthRunningReader.formatErrorMessage(e),
                        buttonText = getString(R.string.btn_grant_samsung_permissions)
                    )
                }
            } else {
                if (!HealthConnectRunningReader.isAvailable(this@MainActivity)) {
                    setPermissionUiState(
                        grantedAll = false,
                        badgeText = getString(R.string.perm_badge_needed),
                        isOkBadge = false,
                        detail = "Health Connect를 사용할 수 없습니다.",
                        buttonText = getString(R.string.btn_grant_permissions)
                    )
                    return@launch
                }
                val hasRead = runCatching { healthReader.hasAllPermissions() }.getOrDefault(false)
                val bgSupported = healthReader.isBackgroundReadSupported()
                val hasBg = runCatching { healthReader.hasBackgroundPermission() }.getOrDefault(false)

                when {
                    hasRead && (!bgSupported || hasBg) -> setPermissionUiState(
                        grantedAll = true,
                        badgeText = getString(R.string.perm_badge_granted),
                        isOkBadge = true,
                        detail = if (bgSupported) {
                            "운동·거리·심박수·걸음수 및 백그라운드 읽기 모두 허용됨"
                        } else {
                            "Health Connect 필수 읽기 권한이 모두 허용됨"
                        },
                        buttonText = getString(R.string.btn_permissions_granted)
                    )
                    hasRead -> setPermissionUiState(
                        grantedAll = false,
                        badgeText = getString(R.string.perm_badge_partial),
                        isOkBadge = false,
                        detail = "기본 읽기 허용됨 (백그라운드 읽기 권한 미허용)",
                        buttonText = getString(R.string.btn_grant_permissions)
                    )
                    else -> setPermissionUiState(
                        grantedAll = false,
                        badgeText = getString(R.string.perm_badge_needed),
                        isOkBadge = false,
                        detail = "Health Connect 읽기 권한이 필요합니다.",
                        buttonText = getString(R.string.btn_grant_permissions)
                    )
                }
            }
        }
    }

    private fun setPermissionUiState(
        grantedAll: Boolean,
        badgeText: String,
        isOkBadge: Boolean,
        detail: String,
        buttonText: String
    ) {
        tvPermissionBadge.text = badgeText
        tvPermissionBadge.setBackgroundResource(
            if (isOkBadge) R.drawable.bg_badge_ok else R.drawable.bg_badge_warn
        )
        tvPermissionBadge.setTextColor(
            ContextCompat.getColor(
                this,
                if (isOkBadge) R.color.badge_ok_text else R.color.badge_warn_text
            )
        )
        tvPermissionDetail.text = detail
        btnPermissions.text = buttonText
        btnPermissions.setBackgroundResource(
            if (grantedAll) R.drawable.bg_btn_granted else R.drawable.bg_btn_primary
        )
        btnPermissions.setTextColor(
            if (grantedAll) {
                ContextCompat.getColor(this, R.color.badge_ok_text)
            } else {
                0xFFFFFFFF.toInt()
            }
        )
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
                        "Samsung Health 권한이 허용되었습니다 (운동 · GPS · 심박수)."
                    hasExercise ->
                        "Samsung Health 운동 권한이 허용되었습니다 (GPS 경로 미허용)."
                    else ->
                        "Samsung Health 권한이 허용되지 않았습니다 (${grantedTypes.size}개 허용됨)."
                }
            } catch (e: Exception) {
                tvStatus.text = SamsungHealthRunningReader.formatErrorMessage(e)
            } finally {
                btnPermissions.isEnabled = true
                refreshPermissionStatus()
            }
        }
    }

    private fun updateZone2SectionVisibility(editing: Boolean, config: SupabaseConfig) {
        tvZone2Summary.text = getString(
            R.string.zone2_summary_format,
            config.zone2MinBpm,
            config.zone2MaxBpm
        )
        if (editing) {
            layoutZone2Summary.visibility = View.GONE
            layoutZone2Editor.visibility = View.VISIBLE
        } else {
            layoutZone2Summary.visibility = View.VISIBLE
            layoutZone2Editor.visibility = View.GONE
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

    private fun updateDriveSectionVisibility(editing: Boolean, config: SupabaseConfig) {
        if (editing) {
            layoutDriveSummary.visibility = View.GONE
            layoutDriveEditor.visibility = View.VISIBLE
        } else {
            tvDriveSummary.text = if (config.hasGoogleDriveWebhook()) {
                getString(R.string.drive_connected_summary)
            } else {
                getString(R.string.drive_not_configured_summary)
            }
            layoutDriveSummary.visibility = View.VISIBLE
            layoutDriveEditor.visibility = View.GONE
        }
    }

    private fun setBgSwitchChecked(checked: Boolean) {
        isUpdatingUi = true
        switchBgAutoSync.isChecked = checked
        isUpdatingUi = false
    }

    private fun refreshLastSyncDisplay() {
        val settings = syncPrefs.load()
        val formattedTime = settings.lastSyncAtIso
            ?.let { iso -> runCatching { TIME_FORMATTER.format(Instant.parse(iso)) }.getOrNull() }
        tvLastSyncTime.text = formattedTime ?: getString(R.string.last_sync_time_none)
        tvLastSyncDetail.text = settings.lastSyncSummary ?: getString(R.string.last_sync_detail_none)
    }

    private fun syncRecentRuns(triggerLabel: String = "Manual") {
        if (isSyncing) return

        val storedConfig = configStore.load()
        val isSupabaseEditorVisible = layoutSupabaseEditor.visibility == View.VISIBLE
        val rawUrl = if (isSupabaseEditorVisible) {
            etSupabaseUrl.text?.toString().orEmpty().ifBlank { storedConfig.url }
        } else {
            storedConfig.url
        }
        val rawKey = if (isSupabaseEditorVisible) {
            etSupabaseAnonKey.text?.toString().orEmpty().ifBlank { storedConfig.anonKey }
        } else {
            storedConfig.anonKey
        }

        val validationError = SupabaseConfigValidator.validate(rawUrl, rawKey)
        if (validationError != null) {
            tvStatus.text = validationError
            return
        }

        val isZone2EditorVisible = layoutZone2Editor.visibility == View.VISIBLE
        val minRaw = if (isZone2EditorVisible) {
            etZone2MinBpm.text?.toString().orEmpty().ifBlank { storedConfig.zone2MinBpm.toString() }
        } else {
            storedConfig.zone2MinBpm.toString()
        }
        val maxRaw = if (isZone2EditorVisible) {
            etZone2MaxBpm.text?.toString().orEmpty().ifBlank { storedConfig.zone2MaxBpm.toString() }
        } else {
            storedConfig.zone2MaxBpm.toString()
        }
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
        updateZone2SectionVisibility(editing = false, config = savedConfig)

        val selectedSource = syncPrefs.load().dataSource
        val useSamsung = selectedSource == SyncDataSource.SAMSUNG_HEALTH &&
            SamsungHealthRunningReader.isSupported()

        lifecycleScope.launch {
            isSyncing = true
            btnSync.isEnabled = false
            btnSync.text = getString(R.string.btn_syncing)
            val supabaseClient = SupabaseRunningRepository.createSupabaseClient(
                supabaseUrl = savedConfig.url,
                supabaseAnonKey = savedConfig.anonKey
            )
            try {
                val reader: RunningTelemetryReader
                val sourceLabel: String
                if (useSamsung) {
                    if (!samsungReader.hasRequiredPermissions()) {
                        tvStatus.text = "Samsung Health 권한 허용이 필요합니다."
                        samsungReader.requestPermissions(this@MainActivity)
                        refreshPermissionStatus()
                        if (!samsungReader.hasRequiredPermissions()) {
                            return@launch
                        }
                    }
                    reader = samsungReader
                    sourceLabel = "Samsung Health"
                } else {
                    if (!healthReader.hasAllPermissions()) {
                        tvStatus.text = "Health Connect 권한 허용이 필요합니다."
                        permissionLauncher.launch(
                            healthReader.permissionsToRequest(includeBackground = switchBgAutoSync.isChecked)
                        )
                        return@launch
                    }
                    reader = healthReader
                    sourceLabel = "Health Connect"
                }

                val uploadTargetLabel = if (savedConfig.hasGoogleDriveWebhook()) {
                    "Supabase + Google Drive"
                } else {
                    "Supabase"
                }
                tvStatus.text = "$sourceLabel 기록을 읽어 ${uploadTargetLabel}로 업로드 중…"
                val repository = SupabaseRunningRepository(supabaseClient)
                val driveGateway = if (savedConfig.hasGoogleDriveWebhook()) {
                    GoogleDriveWebhookRepository(
                        webhookUrl = savedConfig.googleDriveWebhookUrl,
                        secret = savedConfig.googleDriveSecret
                    )
                } else {
                    null
                }
                val syncManager = RunningSyncManager(reader, repository, driveGateway)

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

                val completedAt = Instant.now()
                val latest = result.syncedRecords.maxByOrNull { it.runDate }
                if (latest == null) {
                    tvStatus.text = "$sourceLabel 에서 최근 30일 러닝/걷기 기록을 찾지 못했습니다."
                    syncPrefs.recordSyncResult(
                        summary = "$sourceLabel ($triggerLabel) · 최근 30일 기록 없음",
                        syncedAtIso = completedAt.toString()
                    )
                } else {
                    val extras = buildList {
                        latest.avgCadence?.let { add("${it} spm") }
                        latest.calories?.let { add("${it} kcal") }
                        latest.altitudeGain?.let { add("+${it.toInt()}m") }
                        latest.vo2Max?.let { add("VO2 $it") }
                    }.joinToString(" · ")
                    val driveNote = when {
                        result.driveSynced -> "\nGoogle Drive(시트·문서) 동기화 완료"
                        result.driveError != null -> "\n(참고: Google Drive 업로드 실패 - ${result.driveError})"
                        else -> ""
                    }
                    val driveSummarySuffix = when {
                        result.driveSynced -> " + Drive 완료"
                        result.driveError != null -> " (Drive 실패)"
                        else -> ""
                    }
                    tvStatus.text = "$sourceLabel ${result.sessionsSynced}건 동기화 완료!\n" +
                        "최근: ${latest.workoutType} ${latest.distanceKm}km · " +
                        "HR ${latest.avgHr ?: "-"}bpm · Z2 ${latest.zone2Pct ?: "-"}%" +
                        (if (extras.isNotEmpty()) " · $extras" else "") +
                        driveNote
                    syncPrefs.recordSyncResult(
                        summary = "$sourceLabel ($triggerLabel) · ${result.sessionsSynced}건 동기화 완료$driveSummarySuffix",
                        syncedAtIso = completedAt.toString()
                    )
                }
                refreshLastSyncDisplay()
            } catch (e: Exception) {
                val message = if (useSamsung) {
                    SamsungHealthRunningReader.formatErrorMessage(e)
                } else {
                    e.localizedMessage ?: e::class.java.simpleName
                }
                tvStatus.text = "동기화 실패: $message"
                syncPrefs.setLastSyncSummary("$triggerLabel 실패 · $message")
                refreshLastSyncDisplay()
            } finally {
                runCatching { supabaseClient.close() }
                btnSync.text = getString(R.string.btn_sync_runs)
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
