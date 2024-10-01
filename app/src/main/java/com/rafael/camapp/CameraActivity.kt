package com.rafael.camapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
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
    private lateinit var monitorButton: Button
    private lateinit var inputButton: Button
    private lateinit var outputButton: Button
    private lateinit var timeText: TextView
    private lateinit var flashButton: ImageButton
    private lateinit var switchButton: Button
    private lateinit var handler: Handler
    private lateinit var enlargeButton: Button
    private lateinit var shrinkButton: Button
    private var elapsedTime: Int = 0
    private lateinit var camera: Camera
    private lateinit var audioManager: AudioManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var inputDevices: Array<AudioDeviceInfo>? = null
    private var outputDevices: Array<AudioDeviceInfo>? = null
    private var curInputDeviceNum = 0
    private var curOutputDeviceNum = 0
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var curZoomRatio = 1f
    private var minZoomRatio = 1f
    private var maxZoomRatio = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

//        visualizerView = findViewById(R.id.soundVisualizer)
        amplifierView = findViewById(R.id.soundAmplifier)
        switchButton = findViewById(R.id.switchButton)
        enlargeButton = findViewById(R.id.enlargeButton)
        shrinkButton = findViewById(R.id.shrinkButton)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        bluetoothAdapter = getBluetoothAdapter(this)
        inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

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

        switchButton.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera() // Restart the camera with the new lens facing
        }
        timeText = findViewById(R.id.timeText)
        recordButton = findViewById<Button>(R.id.recordButton)
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        monitorButton = findViewById(R.id.monitorButton)
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
        curInputDeviceNum = getAudioDevice(this, true)
        curOutputDeviceNum = getAudioDevice(this, false)
        inputButton = findViewById(R.id.inputButton)
        outputButton = findViewById(R.id.outputButton)
        when (curInputDeviceNum) {
            1 -> inputButton.isActivated = true
            2 -> inputButton.isSelected = true
        }
        when (curOutputDeviceNum) {
            1 -> outputButton.isActivated = true
            2 -> outputButton.isSelected = true
        }
        inputButton.setOnClickListener {
            setAudioDevices(this, true, ++ curInputDeviceNum % 2)
        }
        outputButton.setOnClickListener {
            setAudioDevices(this, false, ++ curOutputDeviceNum % 3)
        }
        enlargeButton.setOnClickListener {
            zoom(true)
        }
        shrinkButton.setOnClickListener {
            zoom(false)
        }
    }

    private fun zoom(isEnlarge: Boolean) {
        if (isEnlarge) {
            camera?.let {
                if (curZoomRatio < maxZoomRatio) {
                    curZoomRatio += 0.5f
                    if (curZoomRatio > maxZoomRatio) {
                        curZoomRatio = maxZoomRatio
                    }
                    it.cameraControl.setZoomRatio(curZoomRatio)
                }
            }
        } else {
            camera?.let {
                if (curZoomRatio > minZoomRatio) {
                    curZoomRatio -= 0.5f
                    if (curZoomRatio < minZoomRatio) {
                        curZoomRatio = minZoomRatio
                    }
                    it.cameraControl.setZoomRatio(curZoomRatio)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.e("Orientation", if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) "Portrait" else "Landscape")
    }

    private fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return bluetoothManager.adapter
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

    fun setAudioDevices(context: Context, isInput: Boolean, device: Int) {
        if (inputDevices == null && isInput) return
        else if (outputDevices == null && !isInput) return
        when (device) {
            2 -> {
                if (isInput) {
                    inputButton.isActivated = false
                    inputButton.isSelected = true
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                } else {
                    outputButton.isActivated = false
                    outputButton.isSelected = true
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//                    bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
//                        @SuppressLint("MissingPermission")
//                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
//                            if (profile == BluetoothProfile.HEADSET) {
//                                val bluetoothHeadset = proxy as BluetoothHeadset
//                                val connectedDevices = bluetoothHeadset.connectedDevices
//
//                                if (connectedDevices.isNotEmpty()) {
//                                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
//                                    audioManager.isBluetoothScoOn = true
//                                    audioManager.startBluetoothSco()
//                                }
//                            }
//                        }
//                        override fun onServiceDisconnected(profile: Int) {
//                            // Handle disconnection
//                        }
//                    }, BluetoothProfile.HEADSET)
                }
            }
            1 -> {
                if (isInput) {
                    inputButton.isActivated = true
                    inputButton.isSelected = false
                } else {
                    outputButton.isActivated = true
                    outputButton.isSelected = false
                }
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            0 -> {
                if (isInput) {
                    inputButton.isActivated = false
                    inputButton.isSelected = false
                } else {
                    outputButton.isActivated = false
                    outputButton.isSelected = false
                }
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    fun getAudioDevice(context: Context, isInput: Boolean): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (isInput) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                        return 2
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                        return 1
                    }
                    AudioDeviceInfo.TYPE_BUILTIN_MIC -> {
                        return 0
                    }
                    else -> {
                        return 3
                    }
                }
            }
        } else {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (device in devices) {
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                        return 2
                    }
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                        return 1
                    }
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                        return 0
                    }
                    else -> {
                        // Other types of output devices can be handled here
                        return 3
                    }
                }
            }
        }
        return -1
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions[Manifest.permission.CAMERA] == true &&
                permissions[Manifest.permission.RECORD_AUDIO] == true &&
                permissions[Manifest.permission.MODIFY_AUDIO_SETTINGS] == true &&
                permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true &&
                permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true &&
                permissions[Manifest.permission.MANAGE_EXTERNAL_STORAGE] == true &&
                permissions[Manifest.permission.BLUETOOTH] == true &&
                permissions[Manifest.permission.BLUETOOTH_CONNECT] == true &&
                permissions[Manifest.permission.BLUETOOTH_ADMIN] == true
            ) {
                // Permissions granted, start the camera
                startCamera()
            } else {
                // Handle the case where permissions are denied
                // Inform the user that the permissions are needed
            }
        }

    private fun startCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
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

            try {
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()
                // Bind the camera to the lifecycle
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, videoCapture
                ) // Save the Camera instance

                // Set up the SeekBar with the zoom limits
                camera?.cameraInfo?.zoomState?.observe(this) { zoomState ->
                    minZoomRatio = zoomState.minZoomRatio
                    maxZoomRatio = zoomState.maxZoomRatio
                    Log.e("min&max zoom:", "$minZoomRatio:$maxZoomRatio")
                    curZoomRatio = zoomState.zoomRatio // Get current zoom ratio
                }
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
