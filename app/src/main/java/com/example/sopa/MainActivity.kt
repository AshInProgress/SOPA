package com.example.sopa

import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.json.JSONException
import org.json.JSONObject
import java.io.FileWriter
import java.io.IOException

class MainActivity : AppCompatActivity(), RecognitionListener {
    companion object {
        const val REQUEST_RECORD_AUDIO_PERMISSION = 200
        const val REQUEST_STORAGE_PERMISSION = 201

        init {
            // Set the property early before JNA tries to load
            System.setProperty("jna.nosys", "true")
        }
    }

    private lateinit var speechService: SpeechService
    private lateinit var recognizer: Recognizer
    private lateinit var model: Model
    private lateinit var resultTextView: TextView
    private var fullText: String = "" // To store the complete dictation
    private lateinit var recognizedText: TextView  // Declare recognizedText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Request microphone permission if not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
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
        recognizedText = findViewById(R.id.recognizedTextView)
        val startButton: Button = findViewById(R.id.startButton)
        val stopButton: Button = findViewById(R.id.stopButton)
        val saveButton: Button = findViewById(R.id.saveButton)

        startButton.setOnClickListener {
            startRecognition()
        }

        stopButton.setOnClickListener {
            stopRecognition()
        }

        saveButton.setOnClickListener {
            saveTextToFile()
        }

        // Ensure the model is loaded in the background
        Thread {
            try {
                val modelDir = cacheModelFromAssets("vosk-model-small-en-us-0.15")
                model = Model(modelDir)
                Log.d("SOPA", "Model loaded successfully.")
            } catch (e: Exception) {
                Log.e("SOPA", "Failed to load model: ${e.message}")
                runOnUiThread {
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
            recognizedText.text = "" // Clear the placeholder text when recognition starts
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

    // Only show the final result to avoid multiple entries
    override fun onResult(hypothesis: String?) {
        if (hypothesis != null) {
            try {
                val jsonObject = JSONObject(hypothesis)
                if (jsonObject.has("text")) {
                    val finalText = jsonObject.getString("text")
                    runOnUiThread {
                        recognizedText.append(finalText + " ") // Append final result without resetting
                    }
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }
    }

    // No need for the other result methods if we only care about final results
    override fun onFinalResult(hypothesis: String?) {
        // Leave empty or remove this method entirely
    }

    override fun onPartialResult(hypothesis: String?) {
        // Leave empty or remove this method entirely
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
                    writer.write(recognizedText.text.toString())
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
}
