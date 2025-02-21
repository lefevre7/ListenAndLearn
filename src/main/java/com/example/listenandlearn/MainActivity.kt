package com.example.listenandlearn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.listenandlearn.ui.theme.DeepSpeechAppTheme
import java.io.File

// Simple data class to hold the recording file path and transcript.
data class Recording(val filePath: String, val transcript: String)

class MainActivity : ComponentActivity() {

    // A state list that will hold all recordings.
    private val recordingsList = mutableStateListOf<Recording>()

    // Constant for the audio sample rate (same as used for recording).
    private val SAMPLE_RATE = 16000

    // BroadcastReceiver to listen for audio clip broadcasts.
    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val filePath = it.getStringExtra("filePath") ?: ""
                val transcript = it.getStringExtra("transcript") ?: ""
                Log.d("MainActivity", "Broadcast received: filePath=$filePath, transcript=$transcript")
                runOnUiThread {
                    recordingsList.add(Recording(filePath, transcript))
                    Toast.makeText(this@MainActivity, "Received new recording", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()

        setContent {
            DeepSpeechAppTheme {
                MainScreen(
                    onStartRecording = {
                        try {
                            val intent = Intent(this, DeepSpeechService::class.java)
                            startForegroundService(intent)
                            Toast.makeText(this, "Started recording", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error starting recording: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onStopRecording = {
                        try {
                            val intent = Intent(this, DeepSpeechService::class.java)
                            stopService(intent)
                            Toast.makeText(this, "Stopped recording", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error stopping recording: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    recordings = recordingsList,
                    onPlayRecording = { filePath ->
                        playRecording(filePath)
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.example.listenandlearn.AUDIO_CLIP_PROCESSED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(recordingReceiver)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
        )

        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 0)
        }
    }

    /**
     * Plays the PCM recording from the given file path.
     */
    private fun playRecording(filePath: String) {
        Thread {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    runOnUiThread {
                        Toast.makeText(this, "Recording file not found.", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // Read the PCM data from file.
                val audioData = file.readBytes()

                // Determine the minimum buffer size for playback.
                val minBufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                // Configure the AudioTrack for streaming playback.
                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                runOnUiThread {
                    Toast.makeText(this, "Playing recording...", Toast.LENGTH_SHORT).show()
                }
                audioTrack.play()
                audioTrack.write(audioData, 0, audioData.size)
                audioTrack.stop()
                audioTrack.release()
                runOnUiThread {
                    Toast.makeText(this, "Playback finished", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Playback error: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}

@Composable
fun MainScreen(
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    recordings: List<Recording>,
    onPlayRecording: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row for the start and stop buttons.
        Row {
            Button(onClick = onStartRecording) {
                Text("Start Recording")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onStopRecording) {
                Text("Stop Recording")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Recordings", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        // Display the recordings in a scrollable list.
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(recordings) { recording ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(text = "File: ${recording.filePath}", style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = "Transcript: ${recording.transcript}", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onPlayRecording(recording.filePath) }) {
                            Text("Play")
                        }
                    }
                }
            }
        }
    }
}
