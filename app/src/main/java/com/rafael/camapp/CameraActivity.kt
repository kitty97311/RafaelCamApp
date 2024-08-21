package com.rafael.camapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var audioRecord: AudioRecord
    private lateinit var visualizerView: VisualizerView
    private var isRecording: Boolean = false
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordButton: Button
    private lateinit var monitorButton: ImageButton
    private lateinit var deviceButton: ImageButton
    private lateinit var timeText: TextView
    private lateinit var handler: Handler
    private var elapsedTime: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        visualizerView = findViewById(R.id.soundVisualizer)

        // Request the necessary permissions
        requestPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
            )
        )

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize the Handler
        handler = Handler(Looper.getMainLooper())
        // Start the camera
        startCamera()

        timeText = findViewById(R.id.timeText)
        recordButton = findViewById<Button>(R.id.recordButton)
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        monitorButton = findViewById<ImageButton>(R.id.monitorButton)
        monitorButton.isActivated = true
        monitorButton.setOnClickListener {
            monitorButton.isActivated = !monitorButton.isActivated
        }
        deviceButton = findViewById<ImageButton>(R.id.deviceButton)
        setupDeviceButton();
    }

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                elapsedTime ++
                val seconds = elapsedTime % 60
                val minutes = elapsedTime / 60
                timeText.text = String.format("%02d:%02d", minutes, seconds)
                handler.postDelayed(this, 1000)
            }
        }
    }

    private fun setupDeviceButton() {
        var curDeviceType = getHeadsetType(this)
        when (curDeviceType) {
            "Speaker" -> {
                deviceButton.isActivated = false
                deviceButton.isSelected = false
            }
            "Wired" -> {
                deviceButton.isActivated = true
                deviceButton.isSelected = false
                deviceButton.setOnClickListener {
                    deviceButton.isActivated = !deviceButton.isActivated
                    switchAudioOutput(this, if (deviceButton.isSelected) "Wired" else "Speaker")
                }
            }
            "Bluetooth" -> {
                deviceButton.isActivated = false
                deviceButton.isSelected = true
                deviceButton.setOnClickListener {
                    deviceButton.isSelected = !deviceButton.isSelected
                    switchAudioOutput(this, if (deviceButton.isSelected) "Bluetooth" else "Speaker")
                }
            }
        }
    }

    private fun switchAudioOutput(context: Context, deviceType: String) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (deviceType) {
            "Speaker" -> {
                audioManager.isSpeakerphoneOn = true
                audioManager.isBluetoothScoOn = false
                audioManager.isWiredHeadsetOn = false
            }
            "Wired" -> {
                // No need to force audio routing; this usually happens automatically.
                audioManager.isSpeakerphoneOn = false
                audioManager.isBluetoothScoOn = false
            }
            "Bluetooth" -> {
                if (audioManager.isBluetoothA2dpOn) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } else {
                    // Handle case where Bluetooth is not available or not connected
                    audioManager.isSpeakerphoneOn = true
                }
            }
            else -> {
                // Default to speaker
                audioManager.isSpeakerphoneOn = true
                audioManager.isBluetoothScoOn = false
            }
        }
    }

    private fun isHeadsetConnected(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return true
                }
            }
        } else {
            // For older Android versions
            return audioManager.isWiredHeadsetOn || audioManager.isBluetoothA2dpOn
        }
        return false
    }

    fun getHeadsetType(context: Context): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> return "Wired"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return "Bluetooth"
                }
            }
        } else {
            // For older Android versions
            if (audioManager.isWiredHeadsetOn) {
                return "Wired"
            } else if (audioManager.isBluetoothA2dpOn) {
                return "Bluetooth"
            }
        }
        return "Speaker"
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.RECORD_AUDIO] == true &&
                permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true &&
                permissions[Manifest.permission.MANAGE_EXTERNAL_STORAGE] == true &&
                permissions[Manifest.permission.MODIFY_AUDIO_SETTINGS] == true
            ) {
                // Permissions granted, start the camera
                startCamera()
            } else {
                // Handle the case where permissions are denied
                // Inform the user that the permissions are needed
            }
        }

    private fun startCamera() {
        val viewFinder = findViewById<PreviewView>(R.id.viewFinder)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Set up the preview use case to display camera preview
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            // Set up the video capture use case
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Select the back camera as the default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind the camera to the lifecycle
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                )

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "RafaelCamApp").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else filesDir
    }

    private fun startRecording() {
        isRecording = true
        recordButton.isActivated = isRecording
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        // Create a file to save the video
        val videoFile = File(getOutputDirectory(), "${System.currentTimeMillis()}.mp4")

        // Set up the FileOutputOptions
        val outputOptions = FileOutputOptions.Builder(videoFile).build()

        // Initialize AudioRecord
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Start recording the video
        currentRecording = videoCapture.output
            .prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        // Recording has started
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            // Video was saved successfully
                            Toast.makeText(this, "Recorded video file saved successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            // Handle error
                            Log.e("File saving error", "${recordEvent.error}")
                            Toast.makeText(this, "Recorded video file save failed!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // Start recording audio
        audioRecord.startRecording()

        // Start a thread to continuously read audio data and update the visualizer
        Thread {
            val audioBuffer = ShortArray(bufferSize)
            while (isRecording && monitorButton.isActivated) {
                val readSize = audioRecord.read(audioBuffer, 0, audioBuffer.size)

                // Convert the audio buffer to a list of amplitude values
                val amplitudes = audioBuffer.take(readSize).map { it.toFloat() }

                // Update the visualizer view with the amplitudes
                runOnUiThread {
                    visualizerView.updateAmplitudes(amplitudes)
                }
            }
        }.start()

        // Start updating the timeText
        handler.post(updateTimeRunnable)
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.isActivated = isRecording

        // Stop updating the timeText
        elapsedTime = 0
        handler.removeCallbacks(updateTimeRunnable)
        // Stop audio recording
        audioRecord.stop()
        audioRecord.release()

        // Stop video recording
        currentRecording?.stop()
        currentRecording = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
