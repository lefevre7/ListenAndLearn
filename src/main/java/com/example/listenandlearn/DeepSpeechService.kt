package com.example.listenandlearn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import ai.coqui.libstt.STTModel  // Correct import for Coqui STT
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeepSpeechService : Service() {

    private val CHANNEL_ID = "DeepSpeechServiceChannel"
    private var isRecording = false
    private lateinit var audioRecord: AudioRecord
    private var coquiModel: STTModel? = null

    // Recording thread for audio capture.
    private var recordingThread: Thread? = null

    // Executor for transcription tasks.
    private val transcriptionExecutor = Executors.newSingleThreadExecutor()

    // Shared buffer for accumulating audio samples.
    private val clipBuffer = mutableListOf<Short>()

    // Audio configuration: (16 kHz, mono, 16-bit PCM)
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    // Silence detection parameters.
    private val silenceThreshold = 50            // Used for per-buffer loudness.
    private val silenceDurationThreshold = 2000L // milliseconds of silence to mark clip end.
    // Minimum peak amplitude required for the clip to be considered non-silent.
    private val minSpeechAmplitude = 200         // Adjust this threshold based on testing.

    // New constants for pre-processing.
    private val noiseGateThreshold = 30          // Samples below this amplitude will be zeroed.
    private val targetAmplitude = 30000          // Target peak amplitude after gain adjustment.

    private var lastSpeechTime = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        showToast("Service Created: Loading model...")

        // Flag to control use of the external scorer.
        val useExternalScorer = true  // Set to true if you want to enable the scorer.
        // Load Coqui model (and optionally a scorer) on a background thread.
        Thread {
            try {
                // Load the TFLite model from assets.
                val modelFile = copyAssetToFile("deepspeech-0.9.3-models.tflite")
                coquiModel = STTModel(modelFile.absolutePath)
                if (useExternalScorer) {
                    // Load external scorer if enabled.
                    val scorerFile = copyAssetToFile("deepspeech-0.9.3-models.scorer")
                    coquiModel?.enableExternalScorer(scorerFile.absolutePath)
                    coquiModel?.setBeamWidth(500)
                    Log.d("DeepSpeechService", "External scorer enabled with beam width 500.")
                } else {
                    // Disable external scorer and use a lower beam width.
                    coquiModel?.setBeamWidth(200)
                    Log.d("DeepSpeechService", "External scorer disabled. Beam width set to 200.")
                }
                Log.d("DeepSpeechService", "Coqui model loaded.")
                showToast("Model loaded successfully")
            } catch (e: Exception) {
                Log.e("DeepSpeechService", "Error loading Coqui model/scorer: ${e.message}")
                showToast("Error loading model/scorer: ${e.message}")
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(1, getNotification())
            startAudioRecording()
            showToast("Audio recording started")
        } catch (e: Exception) {
            Log.e("DeepSpeechService", "Error starting audio recording: ${e.message}")
            showToast("Error starting recording: ${e.message}")
        }
        return START_STICKY
    }

    private fun startAudioRecording() {
        try {
            isRecording = true
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            audioRecord.startRecording()
            lastSpeechTime = System.currentTimeMillis()

            // Start capturing audio on a background thread.
            recordingThread = Thread {
                try {
                    val audioBuffer = ShortArray(bufferSize)
                    while (isRecording) {
                        val read = audioRecord.read(audioBuffer, 0, audioBuffer.size)
                        if (read > 0) {
                            // Append captured samples to the shared clipBuffer.
                            synchronized(clipBuffer) {
                                for (i in 0 until read) {
                                    clipBuffer.add(audioBuffer[i])
                                }
                            }
                            // Update last speech time if the buffer is loud.
                            if (isBufferLoud(audioBuffer, read)) {
                                lastSpeechTime = System.currentTimeMillis()
                            }
                            // When enough silence is detected, process the clip.
                            if (System.currentTimeMillis() - lastSpeechTime > silenceDurationThreshold && clipBuffer.isNotEmpty()) {
                                val clipData: ShortArray
                                synchronized(clipBuffer) {
                                    clipData = clipBuffer.toShortArray()
                                    clipBuffer.clear()
                                }
                                transcribeAudioClip(clipData)
                                lastSpeechTime = System.currentTimeMillis()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DeepSpeechService", "Error during audio capture: ${e.message}")
                    showToast("Error during audio capture: ${e.message}")
                }
            }
            recordingThread?.start()
        } catch (e: Exception) {
            Log.e("DeepSpeechService", "Error initializing AudioRecord: ${e.message}")
            showToast("Error initializing AudioRecord: ${e.message}")
        }
    }

    // Returns true if the average amplitude of the buffer exceeds the threshold.
    private fun isBufferLoud(buffer: ShortArray, read: Int): Boolean {
        var sum = 0L
        for (i in 0 until read) {
            sum += kotlin.math.abs(buffer[i].toInt())
        }
        val avg = sum / read
        return avg > silenceThreshold
    }

    // Logs statistics (max and average amplitude) for the clip.
    private fun logAudioStats(clip: ShortArray) {
        val max = clip.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        val sum = clip.fold(0L) { acc, sample -> acc + kotlin.math.abs(sample.toInt()) }
        val avg = if (clip.isNotEmpty()) sum / clip.size else 0L
        Log.d("DeepSpeechService", "Audio stats - max: $max, avg: $avg")
    }

    // Pre-processes the audio clip by applying noise reduction and gain normalization.
    private fun preProcessAudio(clip: ShortArray): ShortArray {
        // Apply a simple noise gate: zero out samples below the noiseGateThreshold.
        val noiseReduced = clip.map { sample ->
            if (kotlin.math.abs(sample.toInt()) < noiseGateThreshold) 0 else sample
        }.toShortArray()

        // Compute the maximum absolute amplitude of the noise-reduced clip.
        val maxAmplitude = noiseReduced.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0

        if (maxAmplitude == 0) {
            Log.d("DeepSpeechService", "PreProcess: clip is silent after noise reduction.")
            return noiseReduced
        }

        // Calculate gain factor to normalize the clip to targetAmplitude.
        val gain = targetAmplitude.toFloat() / maxAmplitude

        // Apply gain normalization.
        val normalized = noiseReduced.map { sample ->
            val adjusted = (sample * gain).toInt()
            when {
                adjusted > Short.MAX_VALUE -> Short.MAX_VALUE
                adjusted < Short.MIN_VALUE -> Short.MIN_VALUE
                else -> adjusted.toShort()
            }
        }.toShortArray()

        Log.d("DeepSpeechService", "PreProcess: Applied gain of $gain. Max before: $maxAmplitude, target: $targetAmplitude")
        return normalized
    }

    // Transcribes the audio clip, saves it, and broadcasts the result.
    private fun transcribeAudioClip(clip: ShortArray) {
        // Check if the clip has any sample with amplitude above our minimum threshold.
        val maxAmplitude = clip.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0
        if (maxAmplitude < minSpeechAmplitude) {
            Log.d("DeepSpeechService", "Skipping transcription due to low max amplitude: $maxAmplitude")
            return
        }
        // Log raw audio stats.
        logAudioStats(clip)
        // Pre-process the audio (noise reduction and normalization).
        val processedClip = preProcessAudio(clip)
        // Log processed audio stats.
        logAudioStats(processedClip)
        // Execute transcription on the transcriptionExecutor.
        transcriptionExecutor.execute {
            try {
                showToast("Transcribing audio clip...")
                // Transcribe the pre-processed audio clip.
                val transcript = coquiModel?.stt(processedClip, sampleRate) ?: ""
                Log.d("DeepSpeechService", "Transcript: $transcript")
                showToast("Transcription completed")
                // Save the processed audio clip to a file.
                val clipFile = saveClipToFile(processedClip)
                showToast("Audio file saved: ${clipFile.absolutePath}")
                // Broadcast the file path and transcript.
                val broadcastIntent = Intent("com.example.listenandlearn.AUDIO_CLIP_PROCESSED").apply {
                    putExtra("filePath", clipFile.absolutePath)
                    putExtra("transcript", transcript)
                    setPackage(applicationContext.packageName)
                }
                sendBroadcast(broadcastIntent)
                Log.d("DeepSpeechService", "Broadcast sent: filePath=${clipFile.absolutePath}, transcript=$transcript")
            } catch (e: Exception) {
                Log.e("DeepSpeechService", "Error during transcription: ${e.message}")
                showToast("Error during transcription: ${e.message}")
            }
        }
    }

    // Saves the PCM clip to a file and returns the file.
    private fun saveClipToFile(clip: ShortArray): File {
        val fileName = "recording_${System.currentTimeMillis()}.pcm"
        val file = File(filesDir, fileName)
        try {
            // Convert the ShortArray to a ByteArray in little-endian order.
            val byteBuffer = ByteBuffer.allocate(clip.size * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (s in clip) {
                byteBuffer.putShort(s)
            }
            val bytes = byteBuffer.array()
            FileOutputStream(file).use { it.write(bytes) }
        } catch (e: Exception) {
            Log.e("DeepSpeechService", "Error saving clip to file: ${e.message}")
            showToast("Error saving file: ${e.message}")
        }
        return file
    }

    private fun getNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DeepSpeech Service")
            .setContentText("Recording and transcribing audio...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DeepSpeech Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // Copies an asset file to internal storage so it can be loaded by Coqui STT.
    private fun copyAssetToFile(assetName: String): File {
        val file = File(filesDir, assetName)
        try {
            if (!file.exists()) {
                assets.open(assetName).use { inputStream ->
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DeepSpeechService", "Error copying asset to file: ${e.message}")
            showToast("Error copying asset: ${e.message}")
        }
        return file
    }

    override fun onDestroy() {
        try {
            // Signal the recording thread to stop and wait for it.
            isRecording = false
            recordingThread?.join()

            // Flush any remaining audio in clipBuffer.
            if (clipBuffer.isNotEmpty()) {
                val remainingClip: ShortArray
                synchronized(clipBuffer) {
                    remainingClip = clipBuffer.toShortArray()
                    clipBuffer.clear()
                }
                transcribeAudioClip(remainingClip)
            }

            // Shut down the transcription executor and wait for pending tasks.
            transcriptionExecutor.shutdown()
            if (!transcriptionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                transcriptionExecutor.shutdownNow()
            }

            audioRecord.stop()
            audioRecord.release()
            coquiModel?.freeModel()
            showToast("Service destroyed: Audio recording stopped")
        } catch (e: Exception) {
            Log.e("DeepSpeechService", "Error during service destruction: ${e.message}")
            showToast("Error during service destruction: ${e.message}")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Helper to show Toast messages from any thread.
    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
