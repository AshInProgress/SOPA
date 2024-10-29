package com.example.sopa

import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.icu.util.Calendar
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.text.InputType
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Toast
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.RecognitionListener
import android.widget.Button
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import android.util.Log
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.json.JSONException
import org.json.JSONObject
import java.io.FileWriter
import java.io.IOException
import android.graphics.Color


class MainActivity : AppCompatActivity(), RecognitionListener {
    companion object {
        const val REQUEST_RECORD_AUDIO_PERMISSION = 200
        const val REQUEST_STORAGE_PERMISSION = 201
        private val REQUEST_CODE_PERMISSIONS = 101

        // List of permissions you might need
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        init {
            // Set the property early before JNA tries to load
            System.setProperty("jna.nosys", "true")
        }
    }

    private lateinit var speechService: SpeechService
    private lateinit var recognizer: Recognizer
    private lateinit var model: Model
    private lateinit var resultTextView: TextView
    private lateinit var dictationTextView: TextView
    private var isCommandMode = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request microphone permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
        }
        // Request permissions at runtime
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        fun requestWriteSettingsPermission() {
            if (!Settings.System.canWrite(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }


        // Other initializations...
    }

    // Check if all required permissions are granted
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // Handle the permission request result

    @SuppressLint("MissingSuperCall")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (!allPermissionsGranted()) {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
                finish() // Close the app if permissions are not granted
            }
        }

        // Check if manage external storage permission is needed for Android 11+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri: Uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivityForResult(intent, REQUEST_STORAGE_PERMISSION)
            }
        } else {
            // For older versions, request regular storage permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_STORAGE_PERMISSION)
            }
        }

        // Initialize UI components
        resultTextView = findViewById(R.id.recognizedTextView)
        dictationTextView = findViewById(R.id.dictationTextView)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val saveButton: Button = findViewById(R.id.saveButton)
        val commandToggleButton: Button = findViewById(R.id.commandToggleButton)


        startButton.setOnClickListener {
            Log.d("SOPA", "Start button clicked")
            startRecognition()
        }

        stopButton.setOnClickListener {
            Log.d("SOPA", "Stop button clicked")
            stopRecognition()
        }

        saveButton.setOnClickListener {
            Log.d("SOPA", "Save button clicked")
            saveTextToFile()
        }
        commandToggleButton.setOnClickListener {
            isCommandMode = !isCommandMode
            if (isCommandMode) {
                commandToggleButton.text = "Command Mode ON"
                commandToggleButton.setBackgroundColor(Color.GREEN)
            } else {
                commandToggleButton.text = "Command Mode OFF"
                commandToggleButton.setBackgroundColor(Color.RED)
            }
        }

        // Ensure the model is loaded in the background
        Thread {
            try {
                val modelDir = cacheModelFromAssets("vosk-model-small-en-us-0.15")
                model = Model(modelDir)
                runOnUiThread {
                    Log.d("SOPA", "Model loaded successfully.")
                    Toast.makeText(this, "Model loaded successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Log.e("SOPA", "Failed to load model: ${e.message}")
                    Toast.makeText(this, "Failed to load model", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    // Start recognition
    private fun startRecognition() {
        try {
            if (!this::model.isInitialized) {
                throw Exception("Model not initialized")
            }
            // Stop any existing recognizer before starting a new one
            if (this::speechService.isInitialized) {
                speechService.stop()
            }

            recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService.startListening(this)
            resultTextView.text = "" // Clear the placeholder text when recognition starts
            dictationTextView.text = "" // Clear the placeholder text when recognition starts
            Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
            Log.d("SOPA", "Recognizer started.")
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error starting recognizer", Toast.LENGTH_SHORT).show()
        }
    }

    // Stop recognition
    private fun stopRecognition() {
        try {
            speechService.stop()
            Log.d("SOPA", "Recognizer stopped.")
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error stopping recognizer", Toast.LENGTH_SHORT).show()
        }
    }

    // Store the last recognized partial result
    var lastPartialText: String = ""

    // Partial result: only update if the new result is different from the last one
    override fun onPartialResult(hypothesis: String?) {
        hypothesis?.let {
            try {
                val jsonObject = JSONObject(it)
                if (jsonObject.has("partial")) {
                    val partialText = jsonObject.getString("partial")

                    // Only update if the new partial text is different
                    if (partialText != lastPartialText) {
                        lastPartialText = partialText
                        runOnUiThread {
                            // Display cleaner partial text without the JSON formatting
                            resultTextView.text = partialText
                        }
                    }
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        // hypothesis?.let {
        // runOnUiThread {
        //       resultTextView.append("Mid-result: $it\n")
        //    }
        // }
    }

    // Final result: overwrite partial with final and append to dictation
    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            try {
                val jsonObject = JSONObject(it)
                if (jsonObject.has("text")) {
                    val finalText = jsonObject.getString("text")

                    runOnUiThread {
                        // Overwrite partial result with final result and append to dictation
                        resultTextView.text = finalText
                        dictationTextView.append(finalText + " ")
                        if (isCommandMode) {
                            parseCommands(resultTextView.text.toString()) // Parse commands if in command mode
                        }
                    }
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
    }





    override fun onError(e: Exception?) {
        runOnUiThread { resultTextView.text = "Error: ${e?.message}" }
    }

    override fun onTimeout() {
        runOnUiThread { resultTextView.text = "Timeout: No input detected." }
    }

    private fun saveTextToFile() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Save Dictation")

        // Set up the input
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton("Save") { dialog, _ ->
            val fileName = input.text.toString()
            if (fileName.isNotEmpty()) {
                try {
                    val documentDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val file = File(documentDir, "$fileName.txt")

                    val writer = FileWriter(file)
                    writer.write(dictationTextView.text.toString())
                    writer.flush()
                    writer.close()

                    Toast.makeText(this, "File saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                } catch (e: IOException) {
                    e.printStackTrace()
                    Toast.makeText(this, "Failed to save file", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "File name cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }

        builder.show()
    }


    private fun cacheModelFromAssets(assetDir: String): String {
        val assetManager = assets
        val modelDir = File(cacheDir, assetDir)

        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        val files = assetManager.list(assetDir) ?: return modelDir.absolutePath

        for (filename in files) {
            val assetPath = "$assetDir/$filename"
            val outFile = File(modelDir, filename)

            try {
                if (assetManager.list(assetPath)?.isNotEmpty() == true) {
                    cacheModelFromAssets(assetPath) // Recursive copying for directories
                } else {
                    val inputStream = assetManager.open(assetPath)
                    val outputStream: OutputStream = FileOutputStream(outFile)
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    inputStream.close()
                    outputStream.flush()
                    outputStream.close()
                }
            } catch (e: Exception) {
                Log.e("SOPA", "Error copying asset file: $filename", e)
            }
        }
        return modelDir.absolutePath
    }
    fun parseCommands(resultTextView: String) {
        when {
            resultTextView.contains("call", ignoreCase = true) -> {
                val contactName = extractContactName(resultTextView)
                makeCall(contactName)
            }
            resultTextView.contains("send message", ignoreCase = true) -> {
                val contactName = extractContactName(resultTextView)
                val message = extractMessageContent(resultTextView)
                sendMessage(contactName, message)
            }
            resultTextView.contains("set alarm", ignoreCase = true) -> {
                val time = extractTime(resultTextView)
                setAlarm(time)
            }
            resultTextView.contains("turn on wifi", ignoreCase = true) -> {
                toggleWiFi(true)
            }
            resultTextView.contains("turn off wifi", ignoreCase = true) -> {
                toggleWiFi(false)
            }
            // Add more commands as needed
            else -> {
                Log.d("SOPA", "Command not recognized")
            }
        }
    }
    fun makeCall(contactName: String) {
        val intent = Intent(Intent.ACTION_CALL)
        val contactNumber = getPhoneNumberFromContact(contactName)
        intent.data = Uri.parse("tel:$contactNumber")
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startActivity(intent)
        }
    }
    fun sendMessage(contactName: String, message: String) {
        val smsManager = SmsManager.getDefault()
        val contactNumber = getPhoneNumberFromContact(contactName)
        smsManager.sendTextMessage(contactNumber, null, message, null, null)
    }
    fun toggleWiFi(enable: Boolean) {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.isWifiEnabled = enable
    }
    fun extractContactName(resultTextView: String): String {
        // This is a simple placeholder - you can use more advanced text processing later
        return resultTextView.substringAfter("to ").trim()
    }
    fun extractMessageContent(resultTextView: String): String {
        return resultTextView.substringAfter("message to ").substringAfter("say ").trim()
    }
    fun extractTime(resultTextView: String): String {
        // Placeholder: In the future, you can parse actual time formats
        return resultTextView.substringAfter("set alarm for ").trim()
    }
    fun setAlarm(time: String) {
        val hour = time.substringBefore(":").toIntOrNull()
        val minute = time.substringAfter(":").toIntOrNull()

        if (hour != null && minute != null) {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
            }

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, AlarmReceiver::class.java) // Updated to reference your new AlarmReceiver class
            val pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)

            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )

            Toast.makeText(this, "Alarm set for $time", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Invalid time format", Toast.LENGTH_SHORT).show()
        }
    }

    fun getPhoneNumberFromContact(contactName: String): String {
        var contactNumber = ""

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(contactName)

        val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
        if (cursor != null && cursor.moveToFirst()) {
            contactNumber = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            cursor.close()
        }

        return contactNumber
    }



}