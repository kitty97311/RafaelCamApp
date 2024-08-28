package com.rafael.camapp

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
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
import androidx.camera.core.Camera
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
import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.ExecuteCallback
import com.arthenica.mobileffmpeg.FFmpeg
import com.rafael.camapp.ui.AmplifierView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

class CameraActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var audioRecord: AudioRecord
    private lateinit var audioTrack: AudioTrack
    private lateinit var recordedFile: String
//    private lateinit var visualizerView: VisualizerView
    private lateinit var amplifierView: AmplifierView
    private var isRecording: Boolean = false
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var currentRecording: Recording? = null
    private lateinit var recordButton: Button
    private lateinit var monitorButton: ImageButton
    private lateinit var deviceButton: ImageButton
    private lateinit var timeText: TextView
    private lateinit var flashButton: ImageButton
    private lateinit var handler: Handler
    private var elapsedTime: Int = 0
    private lateinit var camera: Camera

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

//        visualizerView = findViewById(R.id.soundVisualizer)
        amplifierView = findViewById(R.id.soundAmplifier)

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
            if (isRecording) {
                Toast.makeText(this, "You can't change monitoring while recording", Toast.LENGTH_SHORT).show()
            } else {
                monitorButton.isActivated = !monitorButton.isActivated
            }
        }
        flashButton = findViewById(R.id.flashButton)
        flashButton.setOnClickListener {
            flashButton.isActivated = !flashButton.isActivated
            toggleTorch() // Add this to toggle the torch
        }
        deviceButton = findViewById<ImageButton>(R.id.deviceButton)
        setupDeviceButton();
    }

    private fun toggleTorch() {
        if (camera.cameraInfo.hasFlashUnit()) {
            val isTorchOn = camera.cameraInfo.torchState.value == androidx.camera.core.TorchState.ON
            camera.cameraControl.enableTorch(!isTorchOn)
            flashButton.isActivated = !isTorchOn // Update the button UI based on torch state
        } else {
            Toast.makeText(this, "Flash is not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                elapsedTime++
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
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                ) // Save the Camera instance

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
        recordedFile = "${getOutputDirectory()}/${System.currentTimeMillis()}"

        // Set up the FileOutputOptions
        val videoOutputOption = FileOutputOptions.Builder(File("${recordedFile}.mp4")).build()

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
            .prepareRecording(this, videoOutputOption)
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        // Recording has started
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            // Video was saved successfully
                            Log.e("Video save", "success");
                        } else {
                            // Handle error
                            Log.e("Video save", "${recordEvent.error}")
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

        // Initialize AudioTrack
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
            AudioTrack.MODE_STREAM
        )

        // Start recording audio
        audioRecord.startRecording()
        audioTrack.play()

        // Start a thread to continuously read audio data and update the visualizer
        Thread {
            val audioBuffer = ByteArray(bufferSize)
            FileOutputStream(File("${recordedFile}.pcm")).use { fos ->
                while (isRecording && monitorButton.isActivated) {
                    val readSize = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                    if (readSize > 0) {
                        fos.write(audioBuffer, 0, readSize)
                    }

                    audioTrack.write(audioBuffer, 0, bufferSize)
                    // Convert the audio buffer to a list of amplitude values
//                    val amplitudes = audioBuffer.take(readSize).map { it.toFloat() }
                    val amplitude = calculateAmplitude(audioBuffer, readSize)
                    // Update the visualizer view with the amplitudes
                    runOnUiThread {
//                        visualizerView.updateAmplitudes(amplitudes)
                        amplifierView.updateStrength(amplitude)
                    }
                }
            }
        }.start()

        // Start updating the timeText
        handler.post(updateTimeRunnable)
    }

    private fun calculateAmplitude(buffer: ByteArray, readSize: Int): Float {
        var sum = 0f
        for (i in 0 until readSize step 2) {
            val value = (buffer[i].toInt() or (buffer[i + 1].toInt() shl 8)).toShort()
            sum += abs(value.toFloat())
        }
        val amplitude = sum / (readSize / 8)
        // Normalize the amplitude to be between 0.0 and 1.0
        return min(amplitude / Short.MAX_VALUE, 1f)
    }

    private fun mergeVideoAndAudio(mp4FilePath: String, pcmFilePath: String, outputFilePath: String) {
        val sampleRate = 44100  // Make sure this matches your PCM file
        val channels = 1  // 1 for mono, 2 for stereo

        // FFmpeg command to combine PCM and MP4
        val ffmpegCommand = arrayOf(
            "-y",  // Overwrite output file if it exists
            "-f", "s16le",  // PCM format: 16-bit signed little-endian
            "-ar", sampleRate.toString(),  // Audio sample rate
            "-ac", channels.toString(),  // Number of audio channels
            "-i", pcmFilePath,  // Input PCM file
            "-i", mp4FilePath,  // Input MP4 file
            "-c:v", "copy",  // Copy video codec (no re-encoding)
            "-c:a", "aac",  // Audio codec (AAC)
            "-strict", "experimental",  // Allow experimental codecs
            outputFilePath  // Output file path
        )

        // Execute FFmpeg command
        FFmpeg.executeAsync(ffmpegCommand, ExecuteCallback { executionId, returnCode ->
            if (returnCode == 0) {
                Log.d("Combine", "Successfully combined PCM and MP4 into $outputFilePath")
                Toast.makeText(this, "Recorded video file saved successfully!", Toast.LENGTH_SHORT).show()
            } else {
                // Failure
                Log.e("Combine", "Failed to combine PCM and MP4. Return code: $returnCode")
                Toast.makeText(this, "Recorded video file save failed!", Toast.LENGTH_SHORT).show()
            }
            File("${recordedFile}.mp4").delete()
            File("${recordedFile}.pcm").delete()
        })
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
        audioTrack.stop()
        audioTrack.release()
        // Stop video recording
        currentRecording?.stop()
        currentRecording = null

        showSaveDialog(this)
    }

    private fun showSaveDialog(context: Context) {
        // Create the AlertDialog.Builder
        val builder = AlertDialog.Builder(context)

        // Set the title and message
        builder.setTitle("Save Changes")
        builder.setMessage("Do you want to save the changes?")

        // Set up the "Save" button
        builder.setPositiveButton("Save") { dialog, which ->
            // Handle the save action
            mergeVideoAndAudio("${recordedFile}.mp4", "${recordedFile}.pcm", "${recordedFile}_saved.mp4")
            dialog.dismiss()
        }

        // Set up the "Cancel" button
        builder.setNegativeButton("Cancel") { dialog, which ->
            // Handle the cancel action
            File("${recordedFile}.mp4").delete()
            File("${recordedFile}.pcm").delete()
            dialog.dismiss()
        }

        // Create and show the dialog
        val dialog = builder.create()
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
