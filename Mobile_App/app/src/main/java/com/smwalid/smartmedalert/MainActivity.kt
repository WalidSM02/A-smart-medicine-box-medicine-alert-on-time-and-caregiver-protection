package com.smwalid.smartmedalert

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.smwalid.smartmedalert.ui.theme.SmartMedAlertTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ==========================================
// 1. DATA MODELS & STATE
// ==========================================

enum class AppState {
    CONNECT,
    AUTH_CHOICE,
    REGISTER,
    LOGIN,
    DASHBOARD,
    SCAN_RESULT,
    RECORDS,
    LOAD_RELOAD,
    HISTORY,
    PATIENT_RECORD,
    HEALTH_MONITOR
}

data class ParsedMedicine(
    val name: String = "",
    val dose: String = "-",
    val mor: Boolean = false,
    val lun: Boolean = false,
    val nig: Boolean = false,
    val morTime: String = "08:00 AM",
    val lunTime: String = "01:00 PM",
    val nigTime: String = "08:00 PM",
    val beforeMeal: Boolean = false,
    val afterMeal: Boolean = false,
    val duration: String = "-",
    val tabletsLoaded: Int = 0,
    val remainingTablets: Int = 0,
    val imageUriStr: String = "" // Stores the specific medicine image
)

data class PatientDetails(
    val name: String = "",
    val age: String = "",
    val dob: String = "",
    val weight: String = "",
    val height: String = "",
    val hasDiabetes: Boolean = false,
    val hasHypertension: Boolean = false,
    val physicianName: String = "",
    val homeAssistanceName: String = "",
    val imageUriStr: String = ""
)

// ==========================================
// 2. NOTIFICATION & ALARM HELPERS
// ==========================================

object MedNotificationHelper {
    const val CHANNEL_ID = "med_alarm_channel_v1"
    private const val CHANNEL_NAME = "Medicine Reminders"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = "Notifications to remind you to take your medicine on time and reload stock."

                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .build()

                setSound(soundUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 500L, 200L, 500L)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // Accepts an Image URI to display the medicine picture in the notification
    fun sendSystemNotification(context: Context, title: String, message: String, imageUriStr: String? = null) {
        createNotificationChannel(context)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationId = System.currentTimeMillis().toInt()

        val dismissIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = "DISMISS_ACTION"
            putExtra("NOTIF_ID", notificationId)
        }

        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var largeBitmap: Bitmap? = null
        if (!imageUriStr.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(imageUriStr)
                largeBitmap = if (Build.VERSION.SDK_INT < 28) {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                } else {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.smart_med_logo)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0L, 500L, 200L, 500L))
            .setAutoCancel(true)
            .addAction(0, "Dismiss", dismissPendingIntent)

        if (largeBitmap != null) {
            builder.setLargeIcon(largeBitmap)
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(largeBitmap).bigLargeIcon(null as Bitmap?))
        }

        notificationManager.notify(notificationId, builder.build())
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "DISMISS_ACTION") {
            val notifId = intent.getIntExtra("NOTIF_ID", -1)
            if (notifId != -1) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notifId)
            }
        }
    }
}

class MedicineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()

        val medName = intent.getStringExtra("MED_NAME") ?: "Medicine"
        val dose = intent.getStringExtra("MED_DOSE") ?: ""
        val slot = intent.getStringExtra("ALARM_SLOT") ?: run {
            pendingResult.finish()
            return
        }
        val imageUri = intent.getStringExtra("IMAGE_URI")

        MedNotificationHelper.sendSystemNotification(
            context,
            "⏰ Time for Medicine!",
            "Please take your $slot medicines now. ($medName)",
            imageUri
        )

        pendingResult.finish()
    }
}

fun scheduleMedicineAlarm(
    context: Context,
    timeString: String,
    medName: String,
    dose: String,
    slot: String,
    uid: String,
    requestCode: Int,
    imageUriStr: String = ""
) {
    val format = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
    val date: Date = try {
        format.parse(timeString) ?: return
    } catch (e: Exception) {
        return
    }

    val calendar = Calendar.getInstance()
    val timeCalendar = Calendar.getInstance().apply {
        time = date
    }

    calendar.set(Calendar.HOUR_OF_DAY, timeCalendar.get(Calendar.HOUR_OF_DAY))
    calendar.set(Calendar.MINUTE, timeCalendar.get(Calendar.MINUTE))
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)

    if (calendar.before(Calendar.getInstance())) {
        calendar.add(Calendar.DATE, 1)
    }

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, MedicineAlarmReceiver::class.java).apply {
        putExtra("MED_NAME", medName)
        putExtra("MED_DOSE", dose)
        putExtra("ALARM_SLOT", slot)
        putExtra("USER_UID", uid)
        putExtra("IMAGE_URI", imageUriStr)
    }

    val pendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }
    } else {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }
}

fun saveBitmapToLocal(context: Context, bitmap: Bitmap): String {
    val file = File(context.filesDir, "med_img_${System.currentTimeMillis()}.jpg")
    file.outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
    }
    return Uri.fromFile(file).toString()
}

@Composable
fun AsyncUriImage(context: Context, uriStr: String, modifier: Modifier) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uriStr) {
        if (uriStr.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val uri = Uri.parse(uriStr)
                    bitmap = if (Build.VERSION.SDK_INT < 28) {
                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                    } else {
                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                        ImageDecoder.decodeBitmap(source)
                    }
                } catch (e: Exception) { }
            }
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier = modifier.background(Color.LightGray), contentAlignment = Alignment.Center) {
            Text("No\nImg", fontSize = 9.sp, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

// ==========================================
// 3. MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        MedNotificationHelper.createNotificationChannel(this)

        enableEdgeToEdge()
        setContent {
            SmartMedAlertTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainNavigationScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

// ==========================================
// 4. MAIN NAVIGATION ROUTER
// ==========================================

@Composable
fun MainNavigationScreen(modifier: Modifier = Modifier) {
    var currentState by remember { mutableStateOf(AppState.CONNECT) }
    var scannedData by remember { mutableStateOf<List<ParsedMedicine>>(emptyList()) }
    val savedRecords = remember { mutableStateListOf<ParsedMedicine>() }

    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("med_prefs", Context.MODE_PRIVATE)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            }
        }
    }

    fun processHardwareTrigger(slot: String, currentCount: Long, exactTime: String) {
        val uid = auth.currentUser?.uid ?: return
        val threshold = sharedPrefs.getFloat("globalThreshold", 25f)

        db.collection("users").document(uid).collection("prescriptions").get().addOnSuccessListener { presSnap ->
            val batch = db.batch()
            var updatedSomething = false

            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val historyRef = db.collection("users").document(uid).collection("history").document(todayStr)
            val historyUpdates = hashMapOf<String, Any>()

            for (doc in presSnap.documents) {
                val med = doc.toObject(ParsedMedicine::class.java) ?: continue
                var deduct = false
                if (slot == "MOR" && med.mor) deduct = true
                if (slot == "LUN" && med.lun) deduct = true
                if (slot == "NIG" && med.nig) deduct = true

                if (deduct && med.remainingTablets > 0) {
                    val newRemaining = med.remainingTablets - 1
                    val limit = med.tabletsLoaded * (threshold / 100f)

                    if (newRemaining <= limit && med.remainingTablets > limit) {
                        MedNotificationHelper.sendSystemNotification(
                            context,
                            "⚠️ Low Stock Alert",
                            "${med.name} is running low ($newRemaining left). Please reload.",
                            med.imageUriStr
                        )
                    }

                    val incrementData = hashMapOf<String, Any>(
                        "remainingTablets" to newRemaining
                    )
                    batch.set(doc.reference, incrementData, SetOptions.merge())
                    updatedSomething = true
                }

                if (deduct) {
                    historyUpdates["${med.name}_${slot}"] = exactTime
                    updatedSomething = true
                }
            }

            if (historyUpdates.isNotEmpty()) {
                batch.set(historyRef, historyUpdates, SetOptions.merge())
            }

            val userRef = db.collection("users").document(uid)
            val field = when (slot) {
                "MOR" -> "processedMorCount"
                "LUN" -> "processedLunCount"
                else -> "processedNigCount"
            }

            val countUpdate = hashMapOf<String, Any>(field to currentCount)
            batch.set(userRef, countUpdate, SetOptions.merge())

            batch.commit().addOnSuccessListener {
                if (updatedSomething) {
                    Toast.makeText(
                        context,
                        "$slot Box Used! Inventory & History updated.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun listenToHardwareSync() {
        val uid = auth.currentUser?.uid ?: return

        db.collection("users")
            .document(uid)
            .collection("hardware")
            .document("sync")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val morCount = snapshot.getLong("morTakenCount") ?: 0L
                    val lunCount = snapshot.getLong("lunTakenCount") ?: 0L
                    val nigCount = snapshot.getLong("nigTakenCount") ?: 0L

                    val defaultTime = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
                    val morTime = snapshot.getString("morLastTakenTime") ?: defaultTime
                    val lunTime = snapshot.getString("lunLastTakenTime") ?: defaultTime
                    val nigTime = snapshot.getString("nigLastTakenTime") ?: defaultTime

                    db.collection("users").document(uid).get().addOnSuccessListener { userSnap ->
                        val pMor = userSnap.getLong("processedMorCount") ?: 0L
                        val pLun = userSnap.getLong("processedLunCount") ?: 0L
                        val pNig = userSnap.getLong("processedNigCount") ?: 0L

                        if (morCount > pMor) {
                            processHardwareTrigger("MOR", morCount, morTime)
                        }
                        if (lunCount > pLun) {
                            processHardwareTrigger("LUN", lunCount, lunTime)
                        }
                        if (nigCount > pNig) {
                            processHardwareTrigger("NIG", nigCount, nigTime)
                        }
                    }
                }
            }

        db.collection("users")
            .document(uid)
            .collection("prescriptions")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null) {
                    val newRecords = snapshot.documents.mapNotNull { it.toObject(ParsedMedicine::class.java) }
                    savedRecords.clear()
                    savedRecords.addAll(newRecords)
                }
            }
    }

    fun updateSingleCloudRecord(updatedMed: ParsedMedicine, oldMedName: String? = null) {
        val uid = auth.currentUser?.uid ?: return
        val userRef = db.collection("users").document(uid).collection("prescriptions")

        if (oldMedName != null && oldMedName != updatedMed.name) {
            userRef.document(oldMedName.replace("/", "_")).delete()
        }

        userRef.document(updatedMed.name.replace("/", "_")).set(updatedMed).addOnSuccessListener {
            Toast.makeText(context, "Changes Saved to Cloud!", Toast.LENGTH_SHORT).show()

            val syncUpdates = hashMapOf<String, Any>()
            if (updatedMed.mor) syncUpdates["morTargetTime"] = updatedMed.morTime
            if (updatedMed.lun) syncUpdates["lunTargetTime"] = updatedMed.lunTime
            if (updatedMed.nig) syncUpdates["nigTargetTime"] = updatedMed.nigTime
            if (syncUpdates.isNotEmpty()) {
                db.collection("users").document(uid).collection("hardware").document("sync").set(syncUpdates, SetOptions.merge())
            }

            val bc = updatedMed.name.hashCode()
            if (updatedMed.mor) {
                scheduleMedicineAlarm(
                    context,
                    updatedMed.morTime,
                    updatedMed.name,
                    updatedMed.dose,
                    "MOR",
                    uid,
                    bc + 1,
                    updatedMed.imageUriStr
                )
            }
            if (updatedMed.lun) {
                scheduleMedicineAlarm(
                    context,
                    updatedMed.lunTime,
                    updatedMed.name,
                    updatedMed.dose,
                    "LUN",
                    uid,
                    bc + 2,
                    updatedMed.imageUriStr
                )
            }
            if (updatedMed.nig) {
                scheduleMedicineAlarm(
                    context,
                    updatedMed.nigTime,
                    updatedMed.name,
                    updatedMed.dose,
                    "NIG",
                    uid,
                    bc + 3,
                    updatedMed.imageUriStr
                )
            }
        }
    }

    fun adjustInventory(medName: String, setLoadedTo: Int?, remainingChange: Int) {
        val uid = auth.currentUser?.uid ?: return
        val docRef = db.collection("users").document(uid).collection("prescriptions").document(medName.replace("/", "_"))

        val updates = hashMapOf<String, Any>(
            "remainingTablets" to FieldValue.increment(remainingChange.toLong())
        )
        if (setLoadedTo != null) {
            updates["tabletsLoaded"] = setLoadedTo
        }

        docRef.set(updates, SetOptions.merge()).addOnSuccessListener {
            Toast.makeText(context, "Inventory Updated Successfully!", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            Toast.makeText(context, "Failed to update inventory", Toast.LENGTH_SHORT).show()
        }
    }

    fun resetInventory(medName: String) {
        val uid = auth.currentUser?.uid ?: return
        val docRef = db.collection("users").document(uid).collection("prescriptions").document(medName.replace("/", "_"))

        val updates = hashMapOf<String, Any>(
            "tabletsLoaded" to 0
        )

        docRef.set(updates, SetOptions.merge()).addOnSuccessListener {
            Toast.makeText(context, "Loaded Value Reset to 0!", Toast.LENGTH_SHORT).show()
        }.addOnFailureListener {
            Toast.makeText(context, "Failed to reset loaded value", Toast.LENGTH_SHORT).show()
        }
    }

    fun logManualDose(medName: String, slot: String) {
        val uid = auth.currentUser?.uid ?: return
        val batch = db.batch()

        val presRef = db.collection("users").document(uid).collection("prescriptions").document(medName.replace("/", "_"))
        val incrementData = hashMapOf<String, Any>("remainingTablets" to FieldValue.increment(-1))
        batch.set(presRef, incrementData, SetOptions.merge())

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val currentTime = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(Date())
        val histRef = db.collection("users").document(uid).collection("history").document(todayStr)

        val historyUpdate = hashMapOf<String, Any>("${medName}_${slot}" to currentTime)
        batch.set(histRef, historyUpdate, SetOptions.merge())

        batch.commit().addOnSuccessListener {
            Toast.makeText(context, "$slot Dose Logged Successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    fun initHardwareDocs(uid: String) {
        val syncMap = hashMapOf(
            "morAlarmActive" to false,
            "lunAlarmActive" to false,
            "nigAlarmActive" to false,
            "morTakenCount" to 0,
            "lunTakenCount" to 0,
            "nigTakenCount" to 0,
            "morTargetTime" to "08:00 AM",
            "lunTargetTime" to "01:00 PM",
            "nigTargetTime" to "08:00 PM"
        )
        db.collection("users")
            .document(uid)
            .collection("hardware")
            .document("sync")
            .set(syncMap, SetOptions.merge())

        val userMap = hashMapOf(
            "processedMorCount" to 0,
            "processedLunCount" to 0,
            "processedNigCount" to 0
        )
        db.collection("users").document(uid).set(userMap, SetOptions.merge())
    }

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.medical_bg),
            contentDescription = "Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        AnimatedContent(
            targetState = currentState,
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
            },
            label = "screen_transition"
        ) { state ->
            when (state) {
                AppState.CONNECT -> {
                    IntroScreen {
                        if (auth.currentUser != null) {
                            listenToHardwareSync()
                            requestPermissions()
                            currentState = AppState.DASHBOARD
                        } else {
                            currentState = AppState.AUTH_CHOICE
                        }
                    }
                }
                AppState.AUTH_CHOICE -> {
                    AuthChoiceScreen(
                        onCreateClick = {
                            currentState = AppState.REGISTER
                        },
                        onLoginClick = {
                            currentState = AppState.LOGIN
                        }
                    )
                }
                AppState.REGISTER -> {
                    RegisterScreen(
                        onBack = {
                            currentState = AppState.AUTH_CHOICE
                        }
                    ) { uid ->
                        initHardwareDocs(uid)
                        currentState = AppState.LOGIN
                    }
                }
                AppState.LOGIN -> {
                    LoginScreen(
                        onBack = {
                            currentState = AppState.AUTH_CHOICE
                        },
                        onNavigateToRegister = {
                            currentState = AppState.REGISTER
                        }
                    ) {
                        listenToHardwareSync()
                        requestPermissions()
                        currentState = AppState.DASHBOARD
                    }
                }
                AppState.DASHBOARD -> {
                    DashboardScreen(
                        onScanInitiated = {
                            scannedData = emptyList()
                        },
                        onScanComplete = {
                            scannedData = it
                            currentState = AppState.SCAN_RESULT
                        },
                        onViewRecords = {
                            currentState = AppState.RECORDS
                        },
                        onNavigateToLoadReload = {
                            currentState = AppState.LOAD_RELOAD
                        },
                        onNavigateToHistory = {
                            currentState = AppState.HISTORY
                        },
                        onNavigateToPatientRecord = {
                            currentState = AppState.PATIENT_RECORD
                        },
                        onNavigateToHealthMonitor = {
                            currentState = AppState.HEALTH_MONITOR
                        },
                        onLogout = {
                            auth.signOut()
                            savedRecords.clear()
                            currentState = AppState.AUTH_CHOICE
                        }
                    )
                }
                AppState.SCAN_RESULT -> {
                    PrescriptionResultScreen(
                        medicines = scannedData,
                        existingRecords = savedRecords,
                        onBack = {
                            currentState = AppState.DASHBOARD
                        },
                        onUpdateMedicine = { index, updatedMed ->
                            val newList = scannedData.toMutableList()
                            newList[index] = updatedMed
                            scannedData = newList
                        },
                        onDeleteMedicine = { index ->
                            val newList = scannedData.toMutableList()
                            newList.removeAt(index)
                            scannedData = newList
                        },
                        onSaveToCloud = {
                            val uid = auth.currentUser?.uid
                            if (uid != null) {
                                val batch = db.batch()
                                val userRef = db.collection("users").document(uid).collection("prescriptions")
                                val syncUpdates = hashMapOf<String, Any>()

                                scannedData.forEach { med ->
                                    val safeDocName = med.name.replace("/", "_")
                                    val docRef = userRef.document(safeDocName)
                                    batch.set(docRef, med)

                                    if (med.mor) syncUpdates["morTargetTime"] = med.morTime
                                    if (med.lun) syncUpdates["lunTargetTime"] = med.lunTime
                                    if (med.nig) syncUpdates["nigTargetTime"] = med.nigTime

                                    val bc = med.name.hashCode()
                                    if (med.mor) {
                                        scheduleMedicineAlarm(context, med.morTime, med.name, med.dose, "MOR", uid, bc + 1, med.imageUriStr)
                                    }
                                    if (med.lun) {
                                        scheduleMedicineAlarm(context, med.lunTime, med.name, med.dose, "LUN", uid, bc + 2, med.imageUriStr)
                                    }
                                    if (med.nig) {
                                        scheduleMedicineAlarm(context, med.nigTime, med.name, med.dose, "NIG", uid, bc + 3, med.imageUriStr)
                                    }
                                }

                                if (syncUpdates.isNotEmpty()) {
                                    val syncRef = db.collection("users").document(uid).collection("hardware").document("sync")
                                    batch.set(syncRef, syncUpdates, SetOptions.merge())
                                }

                                batch.commit().addOnSuccessListener {
                                    Toast.makeText(
                                        context,
                                        "Saved securely!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    currentState = AppState.DASHBOARD
                                }
                            }
                        }
                    )
                }
                AppState.RECORDS -> {
                    RecordsScreen(
                        records = savedRecords,
                        onBack = {
                            currentState = AppState.DASHBOARD
                        },
                        onUpdateRecord = { _, old, up ->
                            updateSingleCloudRecord(up, old.name)
                        },
                        onDeleteRecord = { _, del ->
                            val uid = auth.currentUser?.uid
                            if (uid != null) {
                                val safeDocName = del.name.replace("/", "_")
                                db.collection("users")
                                    .document(uid)
                                    .collection("prescriptions")
                                    .document(safeDocName)
                                    .delete()
                            }
                        }
                    )
                }
                AppState.LOAD_RELOAD -> {
                    LoadReloadScreen(
                        records = savedRecords,
                        onBack = {
                            currentState = AppState.DASHBOARD
                        },
                        onAdjustInventory = { medName, setLoadedTo, remChange ->
                            adjustInventory(medName, setLoadedTo, remChange)
                        },
                        onResetInventory = { medName ->
                            resetInventory(medName)
                        },
                        onManualLogDose = { medName, slot ->
                            logManualDose(medName, slot)
                        },
                        context = context,
                        sharedPrefs = sharedPrefs
                    )
                }
                AppState.HISTORY -> {
                    HistoryScreen(
                        records = savedRecords,
                        onBack = {
                            currentState = AppState.DASHBOARD
                        }
                    )
                }
                AppState.PATIENT_RECORD -> {
                    PatientRecordScreen(
                        onBack = {
                            currentState = AppState.DASHBOARD
                        }
                    )
                }
                AppState.HEALTH_MONITOR -> {
                    HealthMonitorScreen(
                        onBack = {
                            currentState = AppState.DASHBOARD
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 5. CORE APP SCREENS
// ==========================================

data class DashboardItem(
    val title: String,
    val iconText: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onScanInitiated: () -> Unit,
    onScanComplete: (List<ParsedMedicine>) -> Unit,
    onViewRecords: () -> Unit,
    onNavigateToLoadReload: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToPatientRecord: () -> Unit,
    onNavigateToHealthMonitor: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var showCameraDialog by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }

    fun runScanner(image: InputImage) {
        isProcessing = true
        processPrescriptionImage(context, image) {
            isProcessing = false
            onScanComplete(it)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val image = InputImage.fromFilePath(context, uri)
                runScanner(image)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val image = InputImage.fromBitmap(bitmap, 0)
            runScanner(image)
        }
    }

    if (showCameraDialog) {
        AlertDialog(
            onDismissRequest = {
                showCameraDialog = false
            },
            title = {
                Text(
                    text = "Scan Prescription",
                    color = Color(0xFF0055A4),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Choose a method to scan the prescription.",
                    color = Color.Black
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCameraDialog = false
                        onScanInitiated()
                        cameraLauncher.launch(null)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A4))
                ) {
                    Text(
                        text = "Take Photo 📷",
                        color = Color.White
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showCameraDialog = false
                        onScanInitiated()
                        galleryLauncher.launch("image/*")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBDD4E7))
                ) {
                    Text(
                        text = "Gallery 🖼️",
                        color = Color.Black
                    )
                }
            },
            containerColor = Color.White
        )
    }

    if (isProcessing) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    text = "Analyzing...",
                    color = Color.Black
                )
            },
            confirmButton = { },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        color = Color(0xFF0055A4)
                    )
                    Spacer(
                        modifier = Modifier.height(16.dp)
                    )
                    Text(
                        text = "Extracting medical data via ML Kit",
                        color = Color.Gray
                    )
                }
            },
            containerColor = Color.White
        )
    }

    val gridItems = listOf(
        DashboardItem("Prescription scan", "🔍") {
            showCameraDialog = true
        },
        DashboardItem("Prescription Record", "📁") {
            onViewRecords()
        },
        DashboardItem("Load/Reload Medicine", "➕") {
            onNavigateToLoadReload()
        },
        DashboardItem("Record", "📋") {
            onNavigateToHistory()
        },
        DashboardItem("Patient Record", "👤") {
            onNavigateToPatientRecord()
        },
        DashboardItem("Health Monitoring", "❤️") {
            onNavigateToHealthMonitor()
        },
        DashboardItem("Setting", "⚙️") {
            Toast.makeText(context, "Opening Settings...", Toast.LENGTH_SHORT).show()
        },
        DashboardItem("Log Out", "🚪") {
            onLogout()
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Smart Med Alert",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0055A4)
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFFF0F4F8)
            ) {
                NavigationBarItem(
                    icon = {
                        Text(
                            text = "🏠",
                            fontSize = 24.sp
                        )
                    },
                    label = {
                        Text(
                            text = "Home",
                            color = Color.Black
                        )
                    },
                    selected = true,
                    onClick = { }
                )
                NavigationBarItem(
                    icon = {
                        Text(
                            text = "📋",
                            fontSize = 24.sp
                        )
                    },
                    label = {
                        Text(
                            text = "Directory",
                            color = Color.Black
                        )
                    },
                    selected = false,
                    onClick = {
                        Toast.makeText(context, "Directory clicked", Toast.LENGTH_SHORT).show()
                    }
                )
                NavigationBarItem(
                    icon = {
                        Text(
                            text = "👤",
                            fontSize = 24.sp
                        )
                    },
                    label = {
                        Text(
                            text = "Symptom",
                            color = Color.Black
                        )
                    },
                    selected = false,
                    onClick = {
                        Toast.makeText(context, "Symptom checker clicked", Toast.LENGTH_SHORT).show()
                    }
                )
                NavigationBarItem(
                    icon = {
                        Text(
                            text = "✉️",
                            fontSize = 24.sp
                        )
                    },
                    label = {
                        Text(
                            text = "Inbox",
                            color = Color.Black
                        )
                    },
                    selected = false,
                    onClick = {
                        Toast.makeText(context, "Inbox clicked", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        },
        containerColor = Color.Transparent
    ) { pd ->
        Column(
            modifier = Modifier
                .padding(pd)
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFFE3F2FD))
                        .fillMaxSize()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "SMART MED ALERT",
                            color = Color(0xFF0055A4),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Precision Monitoring.",
                            color = Color.Black,
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp
                        )
                        Text(
                            text = "Your health, tracked in real-time.",
                            color = Color.DarkGray,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier.height(24.dp)
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(gridItems) { item ->
                    Card(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                item.onClick()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 4.dp
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = item.iconText,
                                fontSize = 32.sp
                            )
                            Spacer(
                                modifier = Modifier.height(8.dp)
                            )
                            Text(
                                text = item.title,
                                fontSize = 12.sp,
                                color = Color.Black,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HealthMonitorScreen(onBack: () -> Unit) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current

    // ESP32 Hardware Values
    var heartRate by remember { mutableStateOf("--") }
    var spo2 by remember { mutableStateOf("--") }
    var lastUpdated by remember { mutableStateOf("Waiting for sensor data...") }

    // Patient Detail Values
    var patientWeight by remember { mutableStateOf("--") }
    var patientHeight by remember { mutableStateOf("--") }

    // Manual Form Values
    var bloodSugar by remember { mutableStateOf("") }
    var systolic by remember { mutableStateOf("") }
    var diastolic by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveStatusText by remember { mutableStateOf("Save Manual Records") }

    // Heartbeat Animation
    val infiniteTransition = rememberInfiniteTransition(label = "heartbeat")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    LaunchedEffect(Unit) {
        val uid = auth.currentUser?.uid ?: return@LaunchedEffect

        // 1. Listen for Live ESP32 SpO2 & Heart Rate
        db.collection("users").document(uid).collection("hardware").document("health")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    // Safe parsing supporting both REST API Strings and Android Ints
                    val hrVal = snapshot.get("heartRate")
                    val o2Val = snapshot.get("spo2")

                    val hr = if (hrVal is Number) hrVal.toLong() else hrVal?.toString()?.toLongOrNull()
                    val o2 = if (o2Val is Number) o2Val.toLong() else o2Val?.toString()?.toLongOrNull()

                    if (hr != null && hr > 0) heartRate = hr.toString()
                    if (o2 != null && o2 > 0) spo2 = o2.toString()

                    val format = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
                    lastUpdated = format.format(Date())
                }
            }

        // 2. Fetch Height & Weight from Patient Record
        db.collection("users").document(uid).collection("patient_details").document("profile")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val pWeight = snapshot.getString("weight")
                    val pHeight = snapshot.getString("height")
                    patientWeight = if (!pWeight.isNullOrEmpty()) pWeight else "--"
                    patientHeight = if (!pHeight.isNullOrEmpty()) pHeight else "--"
                }
            }

        // 3. Fetch previous manual health logs (Sugar, BP)
        db.collection("users").document(uid).collection("health_details").document("manual")
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    bloodSugar = doc.getString("bloodSugar") ?: ""
                    systolic = doc.getString("systolicBP") ?: ""
                    diastolic = doc.getString("diastolicBP") ?: ""
                }
            }
    }

    fun saveManualLogs() {
        val uid = auth.currentUser?.uid ?: return
        isSaving = true
        saveStatusText = "Saving..."

        val data = hashMapOf(
            "bloodSugar" to bloodSugar,
            "systolicBP" to systolic,
            "diastolicBP" to diastolic,
            "lastUpdated" to SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(Date())
        )

        db.collection("users").document(uid).collection("health_details").document("manual")
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                isSaving = false
                saveStatusText = "Save Manual Records"
                Toast.makeText(context, "Health records saved successfully!", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                isSaving = false
                saveStatusText = "Save Manual Records"
                Toast.makeText(context, "Failed to save records.", Toast.LENGTH_SHORT).show()
            }
    }

    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedLabelColor = Color(0xFF0055A4),
        unfocusedLabelColor = Color.DarkGray,
        focusedBorderColor = Color(0xFF0055A4),
        unfocusedBorderColor = Color.Gray,
        cursorColor = Color.Black
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(title = "Health Monitor", onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // CARD 1: LIVE VITALS FROM ESP32
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Live Vitals (MAX30102)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0055A4)
                    )
                    Text(
                        text = "Last Sync: $lastUpdated",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "❤️",
                                fontSize = 48.sp,
                                modifier = Modifier.scale(if (heartRate != "--") scale else 1f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$heartRate BPM",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.Black
                            )
                            Text(
                                text = "Heart Rate",
                                fontSize = 14.sp,
                                color = Color.DarkGray
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "🩸", fontSize = 48.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "$spo2 %",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.Black
                            )
                            Text(
                                text = "Blood Oxygen",
                                fontSize = 14.sp,
                                color = Color.DarkGray
                            )
                        }
                    }
                }
            }

            // CARD 2: PATIENT BIOMETRICS (Read from Patient Record)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text("Patient Biometrics", fontWeight = FontWeight.Bold, color = Color(0xFF0055A4))
                    Text("Automatically synced from Patient Record.", fontSize = 12.sp, color = Color.Gray)

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Weight", color = Color.DarkGray, fontSize = 14.sp)
                            Text(patientWeight, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Height", color = Color.DarkGray, fontSize = 14.sp)
                            Text(patientHeight, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.Black)
                        }
                    }
                }
            }

            // CARD 3: MANUAL HEALTH ENTRY
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text("Manual Daily Logs", fontWeight = FontWeight.Bold, color = Color(0xFF0055A4))
                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = bloodSugar,
                        onValueChange = { bloodSugar = it },
                        label = { Text("Blood Sugar / Diabetes Level (mmol/L)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        colors = customTextFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Blood Pressure (mmHg)", fontSize = 14.sp, color = Color.DarkGray)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = systolic,
                            onValueChange = { systolic = it },
                            label = { Text("Systolic (High)") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = customTextFieldColors,
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = diastolic,
                            onValueChange = { diastolic = it },
                            label = { Text("Diastolic (Low)") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = customTextFieldColors,
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { saveManualLogs() },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A4)),
                        enabled = !isSaving
                    ) {
                        Text(saveStatusText, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun LoadReloadScreen(
    records: List<ParsedMedicine>,
    onBack: () -> Unit,
    onAdjustInventory: (String, Int?, Int) -> Unit,
    onResetInventory: (String) -> Unit,
    onManualLogDose: (String, String) -> Unit,
    context: Context,
    sharedPrefs: android.content.SharedPreferences
) {
    var globalThreshold by remember { mutableFloatStateOf(sharedPrefs.getFloat("globalThreshold", 25f)) }
    var medicineToLoad by remember { mutableStateOf<ParsedMedicine?>(null) }
    var medicineToReset by remember { mutableStateOf<ParsedMedicine?>(null) }
    var medicineToTakeDose by remember { mutableStateOf<ParsedMedicine?>(null) }

    // Reset Confirmation Dialog
    medicineToReset?.let { med ->
        AlertDialog(
            onDismissRequest = { medicineToReset = null },
            title = {
                Text("Reset Loaded Value", color = Color.Black, fontWeight = FontWeight.Bold)
            },
            text = {
                Text("Are you sure you want to reset the Loaded value for ${med.name} to 0? (Your remaining tablets will NOT be changed)", color = Color.DarkGray)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onResetInventory(med.name)
                        medicineToReset = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Reset", color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { medicineToReset = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBDD4E7))
                ) {
                    Text("Cancel", color = Color.Black)
                }
            },
            containerColor = Color.White
        )
    }

    // Specific Dose Taking Dialog (For proper history logging)
    medicineToTakeDose?.let { med ->
        AlertDialog(
            onDismissRequest = { medicineToTakeDose = null },
            title = { Text("Log Dose Manually", fontWeight = FontWeight.Bold, color = Color.Black) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Which dose of ${med.name} are you taking?", textAlign = TextAlign.Center, color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(16.dp))

                    if (med.mor) {
                        Button(
                            onClick = {
                                onManualLogDose(med.name, "MOR")
                                medicineToTakeDose = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) { Text("Morning Dose") }
                    }
                    if (med.lun) {
                        Button(
                            onClick = {
                                onManualLogDose(med.name, "LUN")
                                medicineToTakeDose = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) { Text("Lunch Dose") }
                    }
                    if (med.nig) {
                        Button(
                            onClick = {
                                onManualLogDose(med.name, "NIG")
                                medicineToTakeDose = null
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                        ) { Text("Night Dose") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Button(
                    onClick = { medicineToTakeDose = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBDD4E7))
                ) { Text("Cancel", color = Color.Black) }
            },
            containerColor = Color.White
        )
    }

    // Advanced Load Medicine Dialog (With Smart Calculator)
    medicineToLoad?.let { med ->
        var inputTablets by remember { mutableStateOf("") }
        var targetDays by remember { mutableStateOf("") }
        val dailyNeed = (if (med.mor) 1 else 0) + (if (med.lun) 1 else 0) + (if (med.nig) 1 else 0)

        AlertDialog(
            onDismissRequest = {
                medicineToLoad = null
            },
            title = {
                Text(
                    text = "Load & Calculate Medicine",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    // Calculator Section
                    Text("Smart Calculator", fontWeight = FontWeight.Bold, color = Color(0xFF0055A4))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = targetDays,
                        onValueChange = { targetDays = it },
                        label = { Text("Target Days (e.g., 30 for monthly)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        )
                    )

                    if (targetDays.isNotEmpty()) {
                        val days = targetDays.toIntOrNull() ?: 0
                        val reqTotal = days * dailyNeed
                        val toAdd = maxOf(0, reqTotal - med.remainingTablets)

                        Spacer(modifier = Modifier.height(8.dp))
                        Text("For $days days, you need $reqTotal tablets total.", fontSize = 13.sp, color = Color.DarkGray)
                        Text("Currently remaining: ${med.remainingTablets}. You should load: $toAdd.", fontSize = 13.sp, color = Color(0xFFE53935))

                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = { inputTablets = toAdd.toString() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        ) {
                            Text("Use $toAdd", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = Color.LightGray)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "How many ${med.name} tablets are you manually adding?",
                        color = Color.DarkGray,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )
                    OutlinedTextField(
                        value = inputTablets,
                        onValueChange = {
                            inputTablets = it
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val added = inputTablets.toIntOrNull() ?: 0
                        if (added > 0) {
                            val newLoadedTotal = med.remainingTablets + added
                            onAdjustInventory(med.name, newLoadedTotal, added)
                        }
                        medicineToLoad = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0055A4)
                    )
                ) {
                    Text(
                        text = "Load",
                        color = Color.White
                    )
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        medicineToLoad = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFBDD4E7)
                    )
                ) {
                    Text(
                        text = "Cancel",
                        color = Color.Black
                    )
                }
            },
            containerColor = Color.White
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(
            title = "Inventory & Load",
            onBack = onBack
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color.White
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 4.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Low Stock Alert Threshold: ${globalThreshold.roundToInt()}%",
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Slider(
                    value = globalThreshold,
                    onValueChange = {
                        globalThreshold = it
                        sharedPrefs.edit().putFloat("globalThreshold", it).apply()
                    },
                    valueRange = 5f..50f,
                    steps = 9,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF0055A4),
                        activeTrackColor = Color(0xFF0055A4)
                    )
                )
                Text(
                    text = "App will notify you when remaining tablets drop below this percentage.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(16.dp))

                // NEW BUTTON: Triggers the Manual Loop
                Button(
                    onClick = {
                        var alerted = false
                        records.forEach { med ->
                            if (med.tabletsLoaded > 0) {
                                val limit = med.tabletsLoaded * (globalThreshold / 100f)
                                if (med.remainingTablets <= limit) {
                                    MedNotificationHelper.sendSystemNotification(
                                        context,
                                        "⚠️ Low Stock Alert",
                                        "${med.name} is running low (${med.remainingTablets} left). Please reload.",
                                        med.imageUriStr
                                    )
                                    alerted = true
                                }
                            }
                        }
                        if (!alerted) {
                            Toast.makeText(context, "Stock levels are good!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Low stock alerts generated!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("Test Low Stock Alert Notification", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize()
            ) {
                Text(
                    text = "Current Inventory",
                    color = Color(0xFF0055A4),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(8.dp)
                )

                if (records.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No records in the cloud.",
                            color = Color.Gray
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Row(
                            modifier = Modifier
                                .background(
                                    Color(0xFFBDD4E7),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(8.dp)
                        ) {
                            TableCell(text = "Pic", width = 50.dp, isHeader = true)
                            TableCell(text = "Medicine", width = 120.dp, isHeader = true)
                            TableCell(text = "Daily Dose", width = 80.dp, isHeader = true)
                            TableCell(text = "Loaded", width = 70.dp, isHeader = true)
                            TableCell(text = "Remaining", width = 80.dp, isHeader = true)
                            TableCell(text = "Action", width = 120.dp, isHeader = true)
                        }

                        HorizontalDivider(
                            color = Color.Gray,
                            thickness = 1.dp
                        )

                        LazyColumn {
                            itemsIndexed(records) { index, med ->
                                val dailyNeed = (if (med.mor) 1 else 0) +
                                        (if (med.lun) 1 else 0) +
                                        (if (med.nig) 1 else 0)
                                val limit = med.tabletsLoaded * (globalThreshold / 100f)
                                val isLow = med.tabletsLoaded > 0 && med.remainingTablets <= limit

                                val rowColor = if(isLow) Color(0xFFFFCDD2) else Color.Transparent

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(rowColor)
                                        .border(
                                            width = 1.dp,
                                            color = Color(0xFFE0E0E0)
                                        )
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // NEW: Image inside the table
                                    AsyncUriImage(context = context, uriStr = med.imageUriStr, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)))

                                    TableCell(
                                        text = med.name,
                                        width = 120.dp,
                                        isHeader = false
                                    )
                                    TableCell(
                                        text = "$dailyNeed/day",
                                        width = 80.dp,
                                        isHeader = false
                                    )
                                    TableCell(
                                        text = med.tabletsLoaded.toString(),
                                        width = 70.dp,
                                        isHeader = false
                                    )
                                    TableCell(
                                        text = med.remainingTablets.toString() + if(isLow) " ⚠️" else "",
                                        width = 80.dp,
                                        isHeader = false
                                    )

                                    Column(
                                        modifier = Modifier.width(120.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Button(
                                            onClick = {
                                                medicineToLoad = med
                                            },
                                            modifier = Modifier.height(34.dp).fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF0055A4)
                                            ),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(
                                                text = "Load",
                                                fontSize = 11.sp,
                                                color = Color.White
                                            )
                                        }

                                        Spacer(
                                            modifier = Modifier.height(4.dp)
                                        )

                                        if (med.remainingTablets > 0 && dailyNeed > 0) {
                                            Button(
                                                onClick = {
                                                    // Triggers the specific Dose Selection Modal
                                                    medicineToTakeDose = med
                                                },
                                                modifier = Modifier.height(34.dp).fillMaxWidth(),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF4CAF50)
                                                ),
                                                contentPadding = PaddingValues(0.dp)
                                            ) {
                                                Text(
                                                    text = "Take Dose",
                                                    fontSize = 11.sp,
                                                    color = Color.White
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }

                                        Button(
                                            onClick = {
                                                medicineToReset = med
                                            },
                                            modifier = Modifier.height(34.dp).fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFE53935)
                                            ),
                                            contentPadding = PaddingValues(0.dp)
                                        ) {
                                            Text(
                                                text = "Reset",
                                                fontSize = 11.sp,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(
    records: List<ParsedMedicine>,
    onBack: () -> Unit
) {
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()
    val context = LocalContext.current

    var selectedDate by remember { mutableStateOf(Calendar.getInstance()) }
    var historyLogs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var showResetDialog by remember { mutableStateOf(false) }

    val displayFormat = SimpleDateFormat("EEEE, MMM dd, yyyy", Locale.getDefault())
    val docFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun fetchHistory(date: Calendar) {
        val uid = auth.currentUser?.uid ?: return
        isLoading = true
        val dateStr = docFormat.format(date.time)
        db.collection("users").document(uid).collection("history").document(dateStr)
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    val data = doc.data?.mapValues { it.value.toString() } ?: emptyMap()
                    historyLogs = data
                } else {
                    historyLogs = emptyMap()
                }
                isLoading = false
            }
            .addOnFailureListener {
                historyLogs = emptyMap()
                isLoading = false
            }
    }

    LaunchedEffect(selectedDate) {
        fetchHistory(selectedDate)
    }

    fun getStatus(slot: String, med: ParsedMedicine, logTime: String?): Pair<String, Color> {
        if (logTime != null) {
            return Pair("Taken at $logTime ✅", Color(0xFF4CAF50))
        }

        val timeStr = when (slot) {
            "MOR" -> med.morTime
            "LUN" -> med.lunTime
            else -> med.nigTime
        }

        val timeFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
        val slotDate = try {
            timeFormat.parse(timeStr)
        } catch(e: Exception) {
            null
        } ?: return Pair("Pending ⏳", Color.Gray)

        val slotCalendar = Calendar.getInstance().apply {
            time = selectedDate.time
            val tempCal = Calendar.getInstance().apply { time = slotDate }
            set(Calendar.HOUR_OF_DAY, tempCal.get(Calendar.HOUR_OF_DAY))
            set(Calendar.MINUTE, tempCal.get(Calendar.MINUTE))
            set(Calendar.SECOND, 0)
        }

        val now = Calendar.getInstance()
        return if (slotCalendar.before(now)) {
            Pair("Missed ❌", Color(0xFFE53935))
        } else {
            Pair("Pending ⏳", Color.Gray)
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Daily History", fontWeight = FontWeight.Bold, color = Color.Black) },
            text = { Text("Are you sure you want to completely clear the history record for ${displayFormat.format(selectedDate.time)}? This will revert all taken medicines back to Pending/Missed.", color = Color.DarkGray) },
            confirmButton = {
                Button(
                    onClick = {
                        val uid = auth.currentUser?.uid
                        if (uid != null) {
                            val dateStr = docFormat.format(selectedDate.time)
                            db.collection("users").document(uid).collection("history").document(dateStr)
                                .delete()
                                .addOnSuccessListener {
                                    historyLogs = emptyMap()
                                    Toast.makeText(context, "History Cleared!", Toast.LENGTH_SHORT).show()
                                }
                        }
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Clear History", color = Color.White) }
            },
            dismissButton = {
                Button(
                    onClick = { showResetDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBDD4E7))
                ) { Text("Cancel", color = Color.Black) }
            },
            containerColor = Color.White
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(
            title = "Daily History",
            onBack = onBack
        )

        Spacer(modifier = Modifier.height(16.dp))

        // FIX: Replaced isolated card with LazyColumn item { } wrapper so headers scroll cleanly without overlapping
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val newDate = Calendar.getInstance().apply { time = selectedDate.time }
                                newDate.add(Calendar.DATE, -1)
                                selectedDate = newDate
                            }
                        ) {
                            Text(
                                text = "◀",
                                fontSize = 24.sp,
                                color = Color(0xFF0055A4)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = displayFormat.format(selectedDate.time),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.Black
                            )
                            Text(
                                text = "Reset History",
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                color = Color.Red,
                                modifier = Modifier
                                    .clickable { showResetDialog = true }
                                    .padding(4.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val newDate = Calendar.getInstance().apply { time = selectedDate.time }
                                newDate.add(Calendar.DATE, 1)
                                selectedDate = newDate
                            }
                        ) {
                            Text(
                                text = "▶",
                                fontSize = 24.sp,
                                color = Color(0xFF0055A4)
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF0055A4))
                    }
                }
            } else if (records.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No prescriptions found.", color = Color.Gray)
                    }
                }
            } else {
                itemsIndexed(records) { _, med ->
                    if (med.mor || med.lun || med.nig) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = med.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = Color(0xFF0055A4)
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                if (med.mor) {
                                    val log = historyLogs["${med.name}_MOR"]
                                    val (status, color) = getStatus("MOR", med, log)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            // FIX: Displays exact scheduled time even if edited recently
                                            text = "Morning (${med.morTime})",
                                            color = Color.DarkGray
                                        )
                                        Text(
                                            text = status,
                                            color = color,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }

                                if (med.lun) {
                                    val log = historyLogs["${med.name}_LUN"]
                                    val (status, color) = getStatus("LUN", med, log)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            // FIX: Displays exact scheduled time even if edited recently
                                            text = "Lunch (${med.lunTime})",
                                            color = Color.DarkGray
                                        )
                                        Text(
                                            text = status,
                                            color = color,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                }

                                if (med.nig) {
                                    val log = historyLogs["${med.name}_NIG"]
                                    val (status, color) = getStatus("NIG", med, log)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            // FIX: Displays exact scheduled time even if edited recently
                                            text = "Night (${med.nigTime})",
                                            color = Color.DarkGray
                                        )
                                        Text(
                                            text = status,
                                            color = color,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatientRecordScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()

    var patient by remember { mutableStateOf(PatientDetails()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var csvContentToSave by remember { mutableStateOf("") }

    var profileBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Export UI State
    var showExportDialog by remember { mutableStateOf(false) }
    var exportStartDate by remember { mutableStateOf(Calendar.getInstance().apply { add(Calendar.DATE, -7) }) }
    var exportEndDate by remember { mutableStateOf(Calendar.getInstance()) }

    val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(csvContentToSave.toByteArray())
                }
                Toast.makeText(context, "Exported Successfully!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        isExporting = false
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            patient = patient.copy(imageUriStr = uri.toString())
            try {
                profileBitmap = if (Build.VERSION.SDK_INT < 28) {
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                } else {
                    val source = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    LaunchedEffect(Unit) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            db.collection("users").document(uid).collection("patient_details").document("profile")
                .get()
                .addOnSuccessListener { doc ->
                    if (doc != null && doc.exists()) {
                        val p = doc.toObject(PatientDetails::class.java)
                        if (p != null) {
                            patient = p
                            if (p.imageUriStr.isNotEmpty()) {
                                try {
                                    val uri = Uri.parse(p.imageUriStr)
                                    profileBitmap = if (Build.VERSION.SDK_INT < 28) {
                                        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                                    } else {
                                        val source = ImageDecoder.createSource(context.contentResolver, uri)
                                        ImageDecoder.decodeBitmap(source)
                                    }
                                } catch (e: Exception) {
                                    // Image might have been deleted locally
                                }
                            }
                        }
                    }
                    isLoading = false
                }
                .addOnFailureListener {
                    isLoading = false
                }
        } else {
            isLoading = false
        }
    }

    fun savePatientDetails() {
        val uid = auth.currentUser?.uid ?: return
        isSaving = true
        db.collection("users").document(uid).collection("patient_details").document("profile")
            .set(patient)
            .addOnSuccessListener {
                isSaving = false
                Toast.makeText(context, "Patient Record Saved", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                isSaving = false
                Toast.makeText(context, "Failed to save", Toast.LENGTH_SHORT).show()
            }
    }

    fun generateCsvAndExport(startDateStr: String, endDateStr: String) {
        val uid = auth.currentUser?.uid ?: return
        isExporting = true

        db.collection("users").document(uid).collection("prescriptions")
            .get()
            .addOnSuccessListener { presDocs ->
                val prescriptions = presDocs.mapNotNull { it.toObject(ParsedMedicine::class.java) }

                db.collection("users").document(uid).collection("history")
                    .whereGreaterThanOrEqualTo(FieldPath.documentId(), startDateStr)
                    .whereLessThanOrEqualTo(FieldPath.documentId(), endDateStr)
                    .get()
                    .addOnSuccessListener { histDocs ->
                        val sb = StringBuilder()

                        sb.append("--- PATIENT RECORD ---\n")
                        sb.append("Name:,${patient.name}\n")
                        sb.append("Age:,${patient.age}\n")
                        sb.append("DOB:,${patient.dob}\n")
                        sb.append("Weight:,${patient.weight}\n")
                        sb.append("Height:,${patient.height}\n")
                        sb.append("Diabetes:,${if (patient.hasDiabetes) "Yes" else "No"}\n")
                        sb.append("Hypertension:,${if (patient.hasHypertension) "Yes" else "No"}\n")
                        sb.append("Physician:,${patient.physicianName}\n")
                        sb.append("Home Assistance:,${patient.homeAssistanceName}\n\n")

                        sb.append("--- COMBINED PRESCRIPTION RECORD ---\n")
                        sb.append("Medicine,Dose,Duration,Morning,Lunch,Night,Before Meal,After Meal\n")
                        prescriptions.forEach { med ->
                            sb.append("${med.name},${med.dose},${med.duration},")
                            sb.append("${if(med.mor) "Yes" else "No"},${if(med.lun) "Yes" else "No"},${if(med.nig) "Yes" else "No"},")
                            sb.append("${if(med.beforeMeal) "Yes" else "No"},${if(med.afterMeal) "Yes" else "No"}\n")
                        }
                        sb.append("\n")

                        sb.append("--- SPECIFIC DATE RECORD ($startDateStr to $endDateStr) ---\n")
                        sb.append("Date,Medicine Slot,Time Taken\n")

                        if (histDocs.isEmpty) {
                            sb.append("No records found in this date range.\n")
                        } else {
                            histDocs.forEach { doc ->
                                val date = doc.id
                                val dataMap = doc.data
                                dataMap.forEach { (slotKey, timeTaken) ->
                                    sb.append("$date,$slotKey,$timeTaken\n")
                                }
                            }
                        }

                        csvContentToSave = sb.toString()
                        createDocumentLauncher.launch("Patient_Report_${startDateStr}.csv")
                    }
                    .addOnFailureListener {
                        isExporting = false
                        Toast.makeText(context, "Error fetching history.", Toast.LENGTH_SHORT).show()
                    }
            }
            .addOnFailureListener {
                isExporting = false
                Toast.makeText(context, "Error fetching prescriptions.", Toast.LENGTH_SHORT).show()
            }
    }

    if (showExportDialog) {
        var quickRange by remember { mutableStateOf("Custom") }

        fun showDatePicker(initialCalendar: Calendar, onDateSelected: (Calendar) -> Unit) {
            val picker = android.app.DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    val newCal = Calendar.getInstance().apply {
                        set(year, month, dayOfMonth)
                    }
                    onDateSelected(newCal)
                    quickRange = "Custom"
                },
                initialCalendar.get(Calendar.YEAR),
                initialCalendar.get(Calendar.MONTH),
                initialCalendar.get(Calendar.DAY_OF_MONTH)
            )
            picker.show()
        }

        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export to Excel (CSV)", color = Color.Black, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Select a date range to generate a combined patient and prescription report.", color = Color.DarkGray, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Quick Range:", fontWeight = FontWeight.Bold, color = Color.Black)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(
                            onClick = {
                                quickRange = "7 Days"
                                exportEndDate = Calendar.getInstance()
                                exportStartDate = Calendar.getInstance().apply { add(Calendar.DATE, -7) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (quickRange == "7 Days") Color(0xFF0055A4) else Color.LightGray)
                        ) { Text("7 Days") }

                        Button(
                            onClick = {
                                quickRange = "30 Days"
                                exportEndDate = Calendar.getInstance()
                                exportStartDate = Calendar.getInstance().apply { add(Calendar.DATE, -30) }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (quickRange == "30 Days") Color(0xFF0055A4) else Color.LightGray)
                        ) { Text("30 Days") }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Start Date:", fontWeight = FontWeight.Bold, color = Color.Black)
                    Text(
                        text = dateFormatter.format(exportStartDate.time),
                        color = Color(0xFF0055A4),
                        modifier = Modifier.clickable { showDatePicker(exportStartDate) { exportStartDate = it } }.padding(vertical = 8.dp)
                    )

                    Text("End Date:", fontWeight = FontWeight.Bold, color = Color.Black)
                    Text(
                        text = dateFormatter.format(exportEndDate.time),
                        color = Color(0xFF0055A4),
                        modifier = Modifier.clickable { showDatePicker(exportEndDate) { exportEndDate = it } }.padding(vertical = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sDateStr = dateFormatter.format(exportStartDate.time)
                        val eDateStr = dateFormatter.format(exportEndDate.time)
                        if (exportStartDate.after(exportEndDate)) {
                            Toast.makeText(context, "Start date must be before End date", Toast.LENGTH_SHORT).show()
                        } else {
                            showExportDialog = false
                            generateCsvAndExport(sDateStr, eDateStr)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Generate CSV", color = Color.White) }
            },
            dismissButton = {
                Button(
                    onClick = { showExportDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBDD4E7))
                ) { Text("Cancel", color = Color.Black) }
            },
            containerColor = Color.White
        )
    }

    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedLabelColor = Color(0xFF0055A4),
        unfocusedLabelColor = Color.DarkGray,
        focusedBorderColor = Color(0xFF0055A4),
        unfocusedBorderColor = Color.Gray,
        cursorColor = Color.Black
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        TopBar(title = "Patient Record", onBack = onBack)

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF0055A4))
            }
        } else if (isExporting) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF0055A4))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Compiling Report Data...", color = Color.Black)
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Profile Image Area
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE0E0E0))
                            .clickable { imagePickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (profileBitmap != null) {
                            Image(
                                bitmap = profileBitmap!!.asImageBitmap(),
                                contentDescription = "Profile Picture",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("Tap to add\nPhoto", textAlign = TextAlign.Center, color = Color.Gray, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = patient.name,
                        onValueChange = { patient = patient.copy(name = it) },
                        label = { Text("Full Name") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = customTextFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = patient.age,
                            onValueChange = { patient = patient.copy(age = it) },
                            label = { Text("Age") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = customTextFieldColors,
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = patient.dob,
                            onValueChange = { patient = patient.copy(dob = it) },
                            label = { Text("DOB (DD/MM/YYYY)") },
                            modifier = Modifier.weight(1f),
                            colors = customTextFieldColors,
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = patient.weight,
                            onValueChange = { patient = patient.copy(weight = it) },
                            label = { Text("Weight (kg/lbs)") },
                            modifier = Modifier.weight(1f),
                            colors = customTextFieldColors,
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = patient.height,
                            onValueChange = { patient = patient.copy(height = it) },
                            label = { Text("Height (cm/ft)") },
                            modifier = Modifier.weight(1f),
                            colors = customTextFieldColors,
                            singleLine = true
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text("Medical Conditions", fontWeight = FontWeight.Bold, color = Color.Black, modifier = Modifier.align(Alignment.Start))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = patient.hasDiabetes, onCheckedChange = { patient = patient.copy(hasDiabetes = it) })
                        Text("Diabetes", color = Color.Black)
                        Spacer(modifier = Modifier.width(16.dp))
                        Checkbox(checked = patient.hasHypertension, onCheckedChange = { patient = patient.copy(hasHypertension = it) })
                        Text("Hypertension", color = Color.Black)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = patient.physicianName,
                        onValueChange = { patient = patient.copy(physicianName = it) },
                        label = { Text("Assigned Physician") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = customTextFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = patient.homeAssistanceName,
                        onValueChange = { patient = patient.copy(homeAssistanceName = it) },
                        label = { Text("Medical Home Assistance") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = customTextFieldColors,
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    Button(
                        onClick = { savePatientDetails() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A4)),
                        enabled = !isSaving
                    ) {
                        Text(if (isSaving) "Saving..." else "Save Patient Details", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showExportDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Download Excel / CSV Report", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
fun PrescriptionResultScreen(
    medicines: List<ParsedMedicine>,
    existingRecords: MutableList<ParsedMedicine>,
    onBack: () -> Unit,
    onUpdateMedicine: (Int, ParsedMedicine) -> Unit,
    onDeleteMedicine: (Int) -> Unit,
    onSaveToCloud: () -> Unit
) {
    var showDuplicateAlert by remember { mutableStateOf<List<String>>(emptyList()) }
    var medicineToEdit by remember { mutableStateOf<Pair<Int, ParsedMedicine>?>(null) }

    if (showDuplicateAlert.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    text = "Already Saved",
                    color = Color(0xFF0055A4),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "These medicines are already in your cloud records:\n\n" +
                            showDuplicateAlert.joinToString("\n") { "• $it" },
                    color = Color.Black
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDuplicateAlert = emptyList()
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFBDD4E7)
                    )
                ) {
                    Text(
                        text = "OK",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            containerColor = Color.White
        )
    }

    medicineToEdit?.let { (index, med) ->
        EditMedicineDialog(
            medicine = med,
            onDismiss = {
                medicineToEdit = null
            },
            onSave = { updatedMed ->
                onUpdateMedicine(index, updatedMed)
                medicineToEdit = null
            },
            onDelete = {
                onDeleteMedicine(index)
                medicineToEdit = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(
            title = "New Scan Result",
            onBack = onBack
        )

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.95f)
            )
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Extracted Information",
                        color = Color(0xFF0055A4),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Text(
                    text = "💡 Tap any row to edit or delete",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(
                        start = 8.dp,
                        bottom = 8.dp
                    )
                )

                PrescriptionTable(
                    medicines = medicines,
                    onRowClick = { index, med ->
                        medicineToEdit = Pair(index, med)
                    }
                )
            }
        }

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        StyledButton(
            text = "Save to Cloud"
        ) {
            val duplicates = mutableListOf<String>()
            var madeChanges = false

            medicines.forEach { scannedMed ->
                val existingIndex = existingRecords.indexOfFirst {
                    it.name.equals(scannedMed.name, ignoreCase = true)
                }

                if (existingIndex != -1) {
                    val existingMed = existingRecords[existingIndex]
                    if (existingMed == scannedMed) {
                        duplicates.add(scannedMed.name)
                    } else {
                        existingRecords[existingIndex] = scannedMed
                        madeChanges = true
                    }
                } else {
                    existingRecords.add(scannedMed)
                    madeChanges = true
                }
            }

            if (madeChanges) {
                onSaveToCloud()
            }

            if (duplicates.isNotEmpty()) {
                showDuplicateAlert = duplicates
            } else if (!madeChanges) {
                onBack()
            }
        }
    }
}

@Composable
fun RecordsScreen(
    records: List<ParsedMedicine>,
    onBack: () -> Unit,
    onUpdateRecord: (Int, ParsedMedicine, ParsedMedicine) -> Unit,
    onDeleteRecord: (Int, ParsedMedicine) -> Unit
) {
    var medicineToEdit by remember { mutableStateOf<Pair<Int, ParsedMedicine>?>(null) }

    medicineToEdit?.let { (index, oldMed) ->
        EditMedicineDialog(
            medicine = oldMed,
            onDismiss = {
                medicineToEdit = null
            },
            onSave = { updatedMed ->
                onUpdateRecord(index, oldMed, updatedMed)
                medicineToEdit = null
            },
            onDelete = {
                onDeleteRecord(index, oldMed)
                medicineToEdit = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(
            title = "Cloud Records",
            onBack = onBack
        )

        Spacer(
            modifier = Modifier.height(24.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            ),
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.95f)
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxSize()
            ) {
                Text(
                    text = "Saved History",
                    color = Color(0xFF0055A4),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(8.dp)
                )

                Text(
                    text = "💡 Tap any row to edit times or delete",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(
                        start = 8.dp,
                        bottom = 8.dp
                    )
                )

                if (records.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No records in the cloud.\nScan a prescription to sync!",
                            color = Color.Gray,
                            textAlign = TextAlign.Center,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    PrescriptionTable(
                        medicines = records,
                        onRowClick = { index, med ->
                            medicineToEdit = Pair(index, med)
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 6. AUTH SCREENS
// ==========================================

@Composable
fun IntroScreen(onConnectClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LogoWidget()
        Spacer(
            modifier = Modifier.height(48.dp)
        )
        StyledButton(
            text = "CONNECT",
            onClick = onConnectClick
        )
    }
}

@Composable
fun AuthChoiceScreen(
    onCreateClick: () -> Unit,
    onLoginClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LogoWidget()
        Spacer(
            modifier = Modifier.height(48.dp)
        )
        StyledButton(
            text = "Create Account",
            onClick = onCreateClick
        )
        Spacer(
            modifier = Modifier.height(24.dp)
        )
        StyledButton(
            text = "LOG IN",
            onClick = onLoginClick
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onRegisterSuccess: (String) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var mobile by remember { mutableStateOf("") }
    var profession by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    var expanded by remember { mutableStateOf(false) }
    val genderOptions = listOf("Male", "Female", "Other")
    var selectedGender by remember { mutableStateOf(genderOptions[0]) }

    var isLoading by remember { mutableStateOf(false) }

    val auth = FirebaseAuth.getInstance()
    val db = FirebaseFirestore.getInstance()

    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedLabelColor = Color(0xFF0055A4),
        unfocusedLabelColor = Color.DarkGray,
        focusedBorderColor = Color(0xFF0055A4),
        unfocusedBorderColor = Color.Gray,
        cursorColor = Color.Black
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(
            title = "Create Account",
            onBack = onBack
        )
        Spacer(
            modifier = Modifier.height(24.dp)
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                    },
                    label = {
                        Text("Name")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors,
                    singleLine = true
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                OutlinedTextField(
                    value = mobile,
                    onValueChange = {
                        mobile = it
                    },
                    label = {
                        Text("Mobile Number")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors,
                    singleLine = true
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = {
                        expanded = it
                    }
                ) {
                    OutlinedTextField(
                        value = selectedGender,
                        onValueChange = {},
                        readOnly = true,
                        label = {
                            Text("Gender")
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = expanded
                            )
                        },
                        colors = customTextFieldColors,
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = {
                            expanded = false
                        },
                        modifier = Modifier.background(Color.White)
                    ) {
                        genderOptions.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = selectionOption,
                                        color = Color.Black
                                    )
                                },
                                onClick = {
                                    selectedGender = selectionOption
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                OutlinedTextField(
                    value = profession,
                    onValueChange = {
                        profession = it
                    },
                    label = {
                        Text("Profession")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors,
                    singleLine = true
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                    },
                    label = {
                        Text("New Password (Min 6 chars)")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors,
                    singleLine = true
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                    },
                    label = {
                        Text("Re-type Password")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors,
                    singleLine = true
                )
            }
        }

        Spacer(
            modifier = Modifier.height(32.dp)
        )

        if (isLoading) {
            CircularProgressIndicator(
                color = Color(0xFF0055A4)
            )
        } else {
            StyledButton(
                text = "REGISTER"
            ) {
                if (password.length >= 6 && password == confirmPassword && name.isNotEmpty() && mobile.isNotEmpty()) {
                    isLoading = true
                    val fakeEmail = "$mobile@smartmedalert.com"
                    auth.createUserWithEmailAndPassword(fakeEmail, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val userId = auth.currentUser?.uid
                                if (userId != null) {
                                    val userMap = hashMapOf(
                                        "name" to name,
                                        "mobile" to mobile,
                                        "profession" to profession,
                                        "gender" to selectedGender
                                    )
                                    db.collection("users")
                                        .document(userId)
                                        .set(userMap)
                                }
                                onRegisterSuccess(userId ?: "")
                            } else {
                                Toast.makeText(
                                    context,
                                    "Error: ${task.exception?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            isLoading = false
                        }
                } else {
                    Toast.makeText(
                        context,
                        "Please check all fields and ensure password is 6+ chars.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    var mobile by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val auth = FirebaseAuth.getInstance()
    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedLabelColor = Color(0xFF0055A4),
        unfocusedLabelColor = Color.DarkGray,
        focusedBorderColor = Color(0xFF0055A4),
        unfocusedBorderColor = Color.Gray,
        cursorColor = Color.Black
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TopBar(
            title = "Log In",
            onBack = onBack
        )
        Spacer(
            modifier = Modifier.height(48.dp)
        )

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color.White.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 8.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                OutlinedTextField(
                    value = mobile,
                    onValueChange = {
                        mobile = it
                    },
                    label = {
                        Text("Mobile Number")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors,
                    singleLine = true
                )

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                    },
                    label = {
                        Text("Password")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors,
                    singleLine = true
                )
            }
        }

        Spacer(
            modifier = Modifier.height(32.dp)
        )

        if (isLoading) {
            CircularProgressIndicator(
                color = Color(0xFF0055A4)
            )
        } else {
            StyledButton(
                text = "LOG IN"
            ) {
                if (mobile.isNotEmpty() && password.isNotEmpty()) {
                    isLoading = true
                    val fakeEmail = "$mobile@smartmedalert.com"
                    auth.signInWithEmailAndPassword(fakeEmail, password)
                        .addOnCompleteListener { task ->
                            isLoading = false
                            if (task.isSuccessful) {
                                onLoginSuccess()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Login Failed: ${task.exception?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                }
            }
        }

        Spacer(
            modifier = Modifier.height(32.dp)
        )

        Text(
            text = "Not registered yet?\nTap here to Create an Account",
            color = Color(0xFF003366),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clickable {
                    onNavigateToRegister()
                }
                .padding(8.dp)
        )
    }
}

// ==========================================
// 7. DIALOGS & REUSABLE UI
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditMedicineDialog(
    medicine: ParsedMedicine,
    onDismiss: () -> Unit,
    onSave: (ParsedMedicine) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(medicine.name) }
    var dose by remember { mutableStateOf(medicine.dose) }
    var duration by remember { mutableStateOf(medicine.duration) }
    var mor by remember { mutableStateOf(medicine.mor) }
    var lun by remember { mutableStateOf(medicine.lun) }
    var nig by remember { mutableStateOf(medicine.nig) }
    var morTime by remember { mutableStateOf(medicine.morTime) }
    var lunTime by remember { mutableStateOf(medicine.lunTime) }
    var nigTime by remember { mutableStateOf(medicine.nigTime) }
    var beforeMeal by remember { mutableStateOf(medicine.beforeMeal) }
    var afterMeal by remember { mutableStateOf(medicine.afterMeal) }

    // Image State for Medicine Photo
    var imageUriStr by remember { mutableStateOf(medicine.imageUriStr) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            val uri = saveBitmapToLocal(context, bitmap)
            imageUriStr = uri
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            imageUriStr = uri.toString()
        }
    }

    fun showTimePicker(initialTime: String, onTimeSelected: (String) -> Unit) {
        val format = SimpleDateFormat("hh:mm a", Locale.ENGLISH)
        val calendar = Calendar.getInstance()
        try {
            calendar.time = format.parse(initialTime) ?: Date()
        } catch (e: Exception) {
            // Ignore parse errors, default to current time
        }

        val timePickerDialog = TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                val newTime = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hourOfDay)
                    set(Calendar.MINUTE, minute)
                }
                onTimeSelected(format.format(newTime.time))
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            false
        )
        timePickerDialog.show()
    }

    val customTextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedLabelColor = Color(0xFF0055A4),
        unfocusedLabelColor = Color.DarkGray,
        focusedBorderColor = Color(0xFF0055A4),
        unfocusedBorderColor = Color.Gray,
        cursorColor = Color.Black
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Medicine & Image",
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp)
            ) {
                // Image Picker UI inside the Editor
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    AsyncUriImage(
                        context = context,
                        uriStr = imageUriStr,
                        modifier = Modifier.size(100.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(onClick = { cameraLauncher.launch(null) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A4))) { Text("📷 Camera", color = Color.White) }
                    Button(onClick = { galleryLauncher.launch(arrayOf("image/*")) }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055A4))) { Text("🖼 Gallery", color = Color.White) }
                }
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                    },
                    label = {
                        Text("Medicine Name")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                OutlinedTextField(
                    value = dose,
                    onValueChange = {
                        dose = it
                    },
                    label = {
                        Text("Dose (e.g., 500mg)")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors
                )

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                OutlinedTextField(
                    value = duration,
                    onValueChange = {
                        duration = it
                    },
                    label = {
                        Text("Duration (e.g., 5 days)")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = customTextFieldColors
                )

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                Text(
                    text = "Timings & Alarm Setup",
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = mor,
                            onCheckedChange = {
                                mor = it
                            }
                        )
                        Text(
                            text = "Mor",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                        if (mor) {
                            Spacer(
                                modifier = Modifier.width(8.dp)
                            )
                            Text(
                                text = morTime,
                                color = Color(0xFF0055A4),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        showTimePicker(morTime) {
                                            morTime = it
                                        }
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = lun,
                            onCheckedChange = {
                                lun = it
                            }
                        )
                        Text(
                            text = "Lun",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                        if (lun) {
                            Spacer(
                                modifier = Modifier.width(8.dp)
                            )
                            Text(
                                text = lunTime,
                                color = Color(0xFF0055A4),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        showTimePicker(lunTime) {
                                            lunTime = it
                                        }
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = nig,
                            onCheckedChange = {
                                nig = it
                            }
                        )
                        Text(
                            text = "Nig",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                        if (nig) {
                            Spacer(
                                modifier = Modifier.width(8.dp)
                            )
                            Text(
                                text = nigTime,
                                color = Color(0xFF0055A4),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        showTimePicker(nigTime) {
                                            nigTime = it
                                        }
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                }

                Spacer(
                    modifier = Modifier.height(8.dp)
                )

                Text(
                    text = "Meal Instructions",
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = beforeMeal,
                            onCheckedChange = {
                                beforeMeal = it
                            }
                        )
                        Text(
                            text = "Before",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                    }
                    Spacer(
                        modifier = Modifier.width(16.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = afterMeal,
                            onCheckedChange = {
                                afterMeal = it
                            }
                        )
                        Text(
                            text = "After",
                            fontSize = 14.sp,
                            color = Color.Black
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        medicine.copy(
                            name = name,
                            dose = dose,
                            mor = mor,
                            lun = lun,
                            nig = nig,
                            morTime = morTime,
                            lunTime = lunTime,
                            nigTime = nigTime,
                            beforeMeal = beforeMeal,
                            afterMeal = afterMeal,
                            duration = duration,
                            imageUriStr = imageUriStr // Save image URI
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0055A4)
                )
            ) {
                Text(
                    text = "Save Changes",
                    color = Color.White
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red
                )
            ) {
                Text(
                    text = "Delete",
                    color = Color.White
                )
            }
        },
        containerColor = Color.White
    )
}

@Composable
fun PrescriptionTable(
    medicines: List<ParsedMedicine>,
    onRowClick: ((Int, ParsedMedicine) -> Unit)?
) {
    Column(
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .background(
                    Color(0xFFBDD4E7),
                    RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            TableCell(text = "Pic", width = 50.dp, isHeader = true)
            TableCell(text = "Medicine Name", width = 140.dp, isHeader = true)
            TableCell(text = "Dose", width = 80.dp, isHeader = true)
            TableCell(text = "Mor Time", width = 85.dp, isHeader = true)
            TableCell(text = "Lun Time", width = 85.dp, isHeader = true)
            TableCell(text = "Nig Time", width = 85.dp, isHeader = true)
        }

        HorizontalDivider(
            color = Color.Gray,
            thickness = 1.dp
        )

        LazyColumn {
            itemsIndexed(medicines) { index, medicine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = Color(0xFFE0E0E0)
                        )
                        .clickable(enabled = onRowClick != null) {
                            if (onRowClick != null) {
                                onRowClick(index, medicine)
                            }
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Display Image in Table
                    AsyncUriImage(
                        context = LocalContext.current,
                        uriStr = medicine.imageUriStr,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                    )

                    TableCell(
                        text = medicine.name,
                        width = 140.dp,
                        isHeader = false
                    )
                    TableCell(
                        text = medicine.dose,
                        width = 80.dp,
                        isHeader = false
                    )
                    TableCell(
                        text = if (medicine.mor) medicine.morTime else "-",
                        width = 85.dp,
                        isHeader = false
                    )
                    TableCell(
                        text = if (medicine.lun) medicine.lunTime else "-",
                        width = 85.dp,
                        isHeader = false
                    )
                    TableCell(
                        text = if (medicine.nig) medicine.nigTime else "-",
                        width = 85.dp,
                        isHeader = false
                    )
                }
            }
        }
    }
}

@Composable
fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    isHeader: Boolean
) {
    Text(
        text = text,
        color = Color.Black,
        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
        fontSize = if (isHeader) 14.sp else 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .width(width)
            .padding(4.dp)
    )
}

@Composable
fun LogoWidget() {
    Surface(
        shape = CircleShape,
        color = Color.White,
        modifier = Modifier
            .width(240.dp)
            .height(240.dp),
        shadowElevation = 12.dp
    ) {
        Image(
            painter = painterResource(id = R.drawable.smart_med_logo),
            contentDescription = "Smart Med Alert Logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        )
    }
}

@Composable
fun StyledButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .width(250.dp)
            .height(56.dp),
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFBDD4E7),
            contentColor = Color.Black
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 6.dp,
            pressedElevation = 2.dp
        )
    ) {
        Text(
            text = text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif
        )
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.background(
                Color.White.copy(alpha = 0.9f),
                CircleShape
            )
        ) {
            Text(
                text = "←",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
        Spacer(
            modifier = Modifier.width(16.dp)
        )
        Text(
            text = title,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
    }
}

// ==========================================
// 8. ML PARSING LOGIC (SCANNER)
// ==========================================

fun processPrescriptionImage(
    context: Context,
    image: InputImage,
    onResult: (List<ParsedMedicine>) -> Unit
) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    val medicineDictionary = try {
        val jsonString = context.assets.open("medicines.json").bufferedReader().use { reader ->
            reader.readText()
        }
        val jsonArray = org.json.JSONArray(jsonString)
        val list = mutableListOf<String>()

        for (i in 0 until jsonArray.length()) {
            val medObject = jsonArray.getJSONObject(i)
            val medName = medObject.getString("name")
            list.add(medName)
        }
        list
    } catch (e: Exception) {
        emptyList<String>()
    }

    fun computeLevenshteinDistance(str1: String, str2: String): Int {
        val matrix = Array(str1.length + 1) {
            IntArray(str2.length + 1)
        }

        for (i in 0..str1.length) {
            matrix[i][0] = i
        }

        for (j in 0..str2.length) {
            matrix[0][j] = j
        }

        for (i in 1..str1.length) {
            for (j in 1..str2.length) {
                val cost = if (str1[i - 1] == str2[j - 1]) {
                    0
                } else {
                    1
                }

                val deletion = matrix[i - 1][j] + 1
                val insertion = matrix[i][j - 1] + 1
                val substitution = matrix[i - 1][j - 1] + cost

                matrix[i][j] = minOf(deletion, insertion, substitution)
            }
        }
        return matrix[str1.length][str2.length]
    }

    recognizer.process(image)
        .addOnSuccessListener { visionText ->
            val allLines = visionText.textBlocks.flatMap { block ->
                block.lines
            }

            val sortedByY = allLines.sortedBy { line ->
                line.boundingBox?.exactCenterY() ?: 0f
            }

            val horizontalRows = mutableListOf<List<com.google.mlkit.vision.text.Text.Line>>()
            var currentRow = mutableListOf<com.google.mlkit.vision.text.Text.Line>()
            var currentY = -1000f

            for (line in sortedByY) {
                val y = line.boundingBox?.exactCenterY() ?: 0f
                if (Math.abs(y - currentY) > 35f) {
                    if (currentRow.isNotEmpty()) {
                        horizontalRows.add(currentRow)
                    }
                    currentRow = mutableListOf(line)
                    currentY = y
                } else {
                    currentRow.add(line)
                }
            }

            if (currentRow.isNotEmpty()) {
                horizontalRows.add(currentRow)
            }

            val extractedMedicines = mutableListOf<ParsedMedicine>()
            val noiseWords = listOf(
                "name of",
                "medication",
                "investigal",
                "applicable",
                "then,",
                "dose",
                "mor",
                "lun",
                "nig"
            )

            for (row in horizontalRows) {
                val sortedLeftToRight = row.sortedBy { lineItem ->
                    lineItem.boundingBox?.left ?: 0
                }

                val fullRowText = sortedLeftToRight.joinToString(" ") { lineItem ->
                    lineItem.text
                }.trim()

                val lowerLine = fullRowText.lowercase()

                var isNoise = false
                for (noiseWord in noiseWords) {
                    if (lowerLine.contains(noiseWord)) {
                        isNoise = true
                        break
                    }
                }

                if (fullRowText.length < 3 || isNoise) {
                    continue
                }

                val hasNumber = lowerLine.contains(Regex("\\d"))
                val hasTab = lowerLine.contains("tab")
                val hasCap = lowerLine.contains("cap")

                val isFragment = !hasNumber && !hasTab && !hasCap

                if (isFragment && extractedMedicines.isNotEmpty() && lowerLine.length < 15) {
                    val lastMedIndex = extractedMedicines.size - 1
                    val lastMed = extractedMedicines.removeAt(lastMedIndex)

                    val updatedName = (lastMed.name + " " + fullRowText).take(25)
                    val newMed = lastMed.copy(name = updatedName)

                    extractedMedicines.add(newMed)
                    continue
                }

                var medName = sortedLeftToRight.firstOrNull()?.text ?: ""

                if (medName.lowercase().startsWith("tab") || medName.lowercase().startsWith("cap")) {
                    medName = sortedLeftToRight.take(3).joinToString(" ") { lineItem ->
                        lineItem.text
                    }
                }
                medName = medName.take(25).trim()

                if (medicineDictionary.isNotEmpty()) {
                    val cleanName = medName.lowercase()
                        .replace("tab.", "")
                        .replace("cap.", "")
                        .trim()

                    var bestMatch = medName
                    var lowestDistance = Int.MAX_VALUE

                    for (dbName in medicineDictionary) {
                        val dist = computeLevenshteinDistance(cleanName, dbName.lowercase())
                        if (dist < lowestDistance) {
                            lowestDistance = dist
                            bestMatch = dbName
                        }
                    }

                    if (lowestDistance <= 2) {
                        if (medName.lowercase().startsWith("tab")) {
                            medName = "Tab. $bestMatch"
                        } else {
                            medName = bestMatch
                        }
                    }
                }

                val hasMorWord = lowerLine.contains("mor")
                val hasMorNumber = lowerLine.contains("1+")
                val hasMorStart = Regex("^1\\s").containsMatchIn(lowerLine)
                val isMorning = hasMorWord || hasMorNumber || hasMorStart

                val hasLunWord = lowerLine.contains("lun")
                val hasLunNumber = lowerLine.contains("+1+")
                val isLunch = hasLunWord || hasLunNumber

                val hasNigWord = lowerLine.contains("nig")
                val hasNigNumber = lowerLine.contains("+1")
                val hasNigEnd = Regex("\\+\\s*1$").containsMatchIn(lowerLine)
                val isNight = hasNigWord || hasNigNumber || hasNigEnd

                var finalMor = isMorning
                var finalLun = isLunch
                var finalNig = isNight

                if (lowerLine.contains("0+")) {
                    finalMor = false
                }
                if (lowerLine.contains("+0+")) {
                    finalLun = false
                }
                if (lowerLine.endsWith("+0")) {
                    finalNig = false
                }

                val hasBeforeWord = lowerLine.contains("before")
                val hasEmptyWord = lowerLine.contains("empty")
                val isBefore = hasBeforeWord || hasEmptyWord

                val hasAfterWord = lowerLine.contains("after")
                val hasMealWord = lowerLine.contains("meal")
                val isAfter = hasAfterWord || hasMealWord

                var duration = "-"
                val durationRegex = Regex("(\\d+)\\s*(day|month|week)").find(lowerLine)

                if (durationRegex != null) {
                    duration = durationRegex.value
                } else {
                    if (lowerLine.contains("continue")) {
                        duration = "Continue"
                    }
                }

                var dose = "-"
                val doseRegex = Regex("(\\d+\\s*(mg|ml|gm|iu))").find(lowerLine)
                if (doseRegex != null) {
                    dose = doseRegex.value
                }

                val isShortName = medName.length <= 4
                val isSameAsDose = medName.lowercase() == dose.lowercase()

                if (isShortName || isSameAsDose) {
                    val hasTimeSet = isMorning || isLunch || isNight
                    val hasDoseSet = dose != "-"

                    if (extractedMedicines.isNotEmpty() && (hasDoseSet || hasTimeSet)) {
                        val lastMedIndex = extractedMedicines.size - 1
                        val lastMed = extractedMedicines.removeAt(lastMedIndex)

                        val newDose = if (dose != "-") {
                            dose
                        } else {
                            lastMed.dose
                        }

                        val updatedMed = lastMed.copy(
                            dose = newDose,
                            mor = lastMed.mor || finalMor,
                            lun = lastMed.lun || finalLun,
                            nig = lastMed.nig || finalNig
                        )
                        extractedMedicines.add(updatedMed)
                    }
                } else {
                    val newMedicine = ParsedMedicine(
                        name = medName,
                        dose = dose,
                        mor = finalMor,
                        lun = finalLun,
                        nig = finalNig,
                        beforeMeal = isBefore,
                        afterMeal = isAfter,
                        duration = duration
                    )
                    extractedMedicines.add(newMedicine)
                }
            }

            if (extractedMedicines.isEmpty()) {
                val placeholderMed = ParsedMedicine(
                    name = "No format detected",
                    dose = "-",
                    mor = false,
                    lun = false,
                    nig = false,
                    morTime = "08:00 AM",
                    lunTime = "01:00 PM",
                    nigTime = "08:00 PM",
                    beforeMeal = false,
                    afterMeal = false,
                    duration = "-"
                )
                extractedMedicines.add(placeholderMed)
            }

            onResult(extractedMedicines)
        }
        .addOnFailureListener { e ->
            e.printStackTrace()
            onResult(emptyList())
        }
}