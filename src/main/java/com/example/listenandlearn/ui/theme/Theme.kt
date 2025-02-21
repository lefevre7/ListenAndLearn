package com.example.listenandlearn.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun DeepSpeechAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        typography = Typography,
        content = content
    )
}

@Preview(showBackground = true)
@Composable
fun ThemePreview() {
    DeepSpeechAppTheme {
        Text("Hello, DeepSpeech!")
    }
}
