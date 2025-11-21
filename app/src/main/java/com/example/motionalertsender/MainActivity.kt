package com.example.motionalertsender

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.example.motionalertsender.ui.theme.MotionAlertSenderTheme
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource

private val Context.dataStore by preferencesDataStore(name = "settings")
private val EMERGENCY_NUMBER_KEY = stringPreferencesKey("emergency_number")
private val SENSITIVITY_KEY = floatPreferencesKey("sensitivity")
private val ROLE_IS_AMBULANCE_KEY = booleanPreferencesKey("is_ambulance_override")

class MainActivity : ComponentActivity(), SensorEventListener, LocationListener {
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var isMonitoring by mutableStateOf(false)
    private var lastAlert by mutableStateOf("No alerts yet")
    private var currentLocation by mutableStateOf("Not available")
    private var sensitivity by mutableStateOf(22f)
    private var emergencyNumber by mutableStateOf("")
    private var showNumberDialog by mutableStateOf(false)
    private var tempNumber by mutableStateOf("")
    private var isAmbulance by mutableStateOf(false)
    private val alerts = mutableStateListOf<AlertItem>()
    private var internalAlertReceiver: android.content.BroadcastReceiver? = null
    // SMS coalescing state (send only once per 5s, using the last alert)
    private var pendingSmsMessage: String? = null
    private var pendingSmsJob: Job? = null
    // Motion/location debounce
    private var awaitingLocation by mutableStateOf(false)
    private var lastMotionAt: Long = 0L
    private val motionCooldownMs: Long = 5000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // Role from flavor resources
        isAmbulance = resources.getBoolean(R.bool.is_ambulance)

        // Load persisted settings
        lifecycleScope.launch {
            val prefs = applicationContext.dataStore.data.first()
            emergencyNumber = prefs[EMERGENCY_NUMBER_KEY] ?: ""
            sensitivity = prefs[SENSITIVITY_KEY] ?: 22f
            isAmbulance = prefs[ROLE_IS_AMBULANCE_KEY] ?: isAmbulance
        }

        setContent {
            MotionAlertSenderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current
                    val role = if (isAmbulance) "Ambulance" else "Car"
                    if (showNumberDialog) {
                        AlertDialog(
                            onDismissRequest = { showNumberDialog = false },
                            title = { Text("Emergency Contact") },
                            text = {
                                OutlinedTextField(
                                    value = tempNumber,
                                    onValueChange = { tempNumber = it },
                                    label = { Text("Phone Number") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        emergencyNumber = tempNumber
                                        showNumberDialog = false
                                        scope.launch {
                                            context.dataStore.edit { it[EMERGENCY_NUMBER_KEY] = emergencyNumber }
                                        }
                                    }
                                ) { Text("Save") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNumberDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    MainScreen(
                        isMonitoring = isMonitoring,
                        onMonitoringChange = { enabled ->
                            if (isAmbulance) {
                                lastAlert = "Ambulance mode: monitoring disabled"
                                return@MainScreen
                            }
                            isMonitoring = enabled
                            if (enabled) {
                                startMonitoring()
                            } else {
                                stopMonitoring()
                            }
                        },
                        lastAlert = lastAlert,
                        currentLocation = currentLocation,
                        sensitivity = sensitivity,
                        onSensitivityChange = { value ->
                            sensitivity = value
                            scope.launch {
                                context.dataStore.edit { it[SENSITIVITY_KEY] = value }
                            }
                        },
                        emergencyNumber = emergencyNumber,
                        onEmergencyNumberClick = {
                            tempNumber = emergencyNumber
                            showNumberDialog = true
                        },
                        role = role,
                        isAmbulance = isAmbulance,
                        onRoleChange = { checked ->
                            isAmbulance = checked
                            if (checked && isMonitoring) {
                                stopMonitoring()
                                lastAlert = "Ambulance mode: monitoring disabled"
                            }
                            // Persist
                            lifecycleScope.launch {
                                applicationContext.dataStore.edit { it[ROLE_IS_AMBULANCE_KEY] = checked }
                            }
                        },
                        alerts = alerts,
                        onGoToLocation = { lat, lon ->
                            val gmmIntentUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
                            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                            mapIntent.setPackage("com.google.android.apps.maps")
                            try {
                                startActivity(mapIntent)
                            } catch (_: Exception) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps?q=$lat,$lon")))
                            }
                        }
                    )
                }
            }
        }

        requestPermissions()
    }

    private fun startMonitoring() {
        if (isAmbulance) {
            lastAlert = "Ambulance mode: monitoring disabled"
            return
        }
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_NORMAL
        )
        lastAlert = "Monitoring started at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
    }

    private fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        lastAlert = "Monitoring stopped at ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECEIVE_SMS,
            ),
            1
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isMonitoring || isAmbulance) return
        
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        val accel = sqrt(ax * ax + ay * ay + az * az)

        if (accel > sensitivity) {
            val now = System.currentTimeMillis()
            if (now - lastMotionAt < motionCooldownMs || awaitingLocation) {
                return
            }
            lastMotionAt = now
            awaitingLocation = true
            lastAlert = "Motion detected, acquiring location..."
            requestLocation()
        }
    }

    private fun requestLocation() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, this)
    }

    override fun onLocationChanged(location: Location) {
        if (isAmbulance) return
        if (!awaitingLocation) return
        val lat = location.latitude
        val lon = location.longitude
        currentLocation = "Lat: ${String.format("%.6f", lat)}, Lon: ${String.format("%.6f", lon)}"
        awaitingLocation = false
        // Stop continuous updates until next motion trigger
        locationManager.removeUpdates(this)

        if (emergencyNumber.isNotBlank()) {
            val message = """
                MOTION ALERT!
                Location: https://www.google.com/maps?q=$lat,$lon
                Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}
                """.trimIndent()

            // Queue SMS to send once within 5 seconds (coalescing)
            val startedNewWindow = scheduleSms(message)
            if (startedNewWindow) {
                lastAlert = "Alert queued for sending"
            } else {
                // Do not spam the status, quietly updated the pending message
            }
        }
    }

    private fun scheduleSms(message: String): Boolean {
        pendingSmsMessage = message
        if (pendingSmsJob == null) {
            pendingSmsJob = lifecycleScope.launch {
                delay(5000L)
                val toSend = pendingSmsMessage
                pendingSmsMessage = null
                pendingSmsJob = null
                if (!toSend.isNullOrBlank()) {
                    sendSms(toSend)
                }
            }
            return true // started a new 5s window
        }
        return false // only updated the pending message
    }

    private fun sendSms(message: String) {
        try {
            val smsManager = SmsManager.getDefault()
            smsManager.sendTextMessage(emergencyNumber, null, message, null, null)
            lastAlert = "Alert sent to $emergencyNumber"
        } catch (e: Exception) {
            lastAlert = "Failed to send alert: ${e.message}"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStart() {
        super.onStart()
        // Listen for internal alerts broadcasted by AlertSmsReceiver
        if (internalAlertReceiver == null) {
            internalAlertReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "com.example.motionalertsender.ALERT_RECEIVED") {
                        val lat = intent.getDoubleExtra("lat", Double.NaN)
                        val lon = intent.getDoubleExtra("lon", Double.NaN)
                        val time = intent.getStringExtra("time") ?: ""
                        val from = intent.getStringExtra("from") ?: ""
                        if (!lat.isNaN() && !lon.isNaN()) {
                            alerts.add(0, AlertItem(lat, lon, time, from))
                            lastAlert = "New alert received at $time"
                        }
                    }
                }
            }
        }
        val filter = IntentFilter("com.example.motionalertsender.ALERT_RECEIVED")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(internalAlertReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(internalAlertReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        internalAlertReceiver?.let { unregisterReceiver(it) }
    }

    data class AlertItem(val lat: Double, val lon: Double, val time: String, val from: String)

    @Composable
    private fun MainScreen(
        isMonitoring: Boolean,
        onMonitoringChange: (Boolean) -> Unit,
        lastAlert: String,
        currentLocation: String,
        sensitivity: Float,
        onSensitivityChange: (Float) -> Unit,
        emergencyNumber: String,
        onEmergencyNumberClick: () -> Unit,
        role: String,
        isAmbulance: Boolean,
        onRoleChange: (Boolean) -> Unit,
        alerts: List<AlertItem>,
        onGoToLocation: (Double, Double) -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Text(
                text = "Motion Alert Sender",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Text(
                text = "Role: $role",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )

            // Runtime Role Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ambulance mode",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = isAmbulance,
                    onCheckedChange = onRoleChange
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isAmbulance) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Ambulance mode: incoming alerts will appear below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Alerts list
                if (alerts.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("No alerts yet", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    alerts.forEach { alert ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Alert: ${"%.6f".format(alert.lat)}, ${"%.6f".format(alert.lon)}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text("Time: ${alert.time}", style = MaterialTheme.typography.bodyMedium)
                                if (alert.from.isNotBlank())
                                    Text("From: ${alert.from}", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { onGoToLocation(alert.lat, alert.lon) }) {
                                    Text("GO TO LOCATION")
                                }
                            }
                        }
                    }
                }
            } else {
                // Car-only UI
                // Status Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isMonitoring) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = if (isMonitoring) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isMonitoring) "ACTIVE" else "INACTIVE",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Emergency Contact
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onEmergencyNumberClick
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContactEmergency,
                            contentDescription = "Emergency Contact",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Emergency Contact", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = emergencyNumber.ifEmpty { "Tap to set emergency number" },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Sensitivity
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Sensitivity",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Sensitivity", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                "${sensitivity.roundToInt()}",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Slider(
                            value = sensitivity,
                            onValueChange = onSensitivityChange,
                            valueRange = 10f..40f,
                            steps = 30,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Low", style = MaterialTheme.typography.bodySmall)
                            Text("High", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Location
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Location",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Current Location", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = currentLocation,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Last Alert
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Last Alert",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text("Last Alert", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = lastAlert,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Toggle Button
                Button(
                    onClick = { onMonitoringChange(!isMonitoring) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = !isAmbulance,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMonitoring) MaterialTheme.colorScheme.errorContainer 
                                       else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isMonitoring) MaterialTheme.colorScheme.error 
                                     else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Text(
                        text = if (isAmbulance) "AMBULANCE MODE" else if (isMonitoring) "STOP MONITORING" else "START MONITORING",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
