/*
 * This file is part of VideoCompressor library.
 *
 * Copyright (C) 2025 Primavera
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.primaverahq.videocompressor.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.primaverahq.videocompressor.CompressionResult
import com.primaverahq.videocompressor.VideoCompressor
import com.primaverahq.videocompressor.sample.ui.theme.VideoCompressorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var state by remember { mutableStateOf<AppState>(AppState.Default) }

            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent(),
                onResult = { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult

                    scope.launch {
                        val input = copyFileToLocal(uri) ?: return@launch
                        val output = File(filesDir, "${input.nameWithoutExtension}_compressed.mp4")
                        val start = System.currentTimeMillis()

                        state = AppState.InProgress(input)

                        val result = VideoCompressor.compress(
                            context = context,
                            input = input,
                            output = output,
                            onMetadataDecoded = { compressor, metadata ->
                                val maxDimension = max(metadata.width, metadata.height)
                                val ratio = 1280f / maxDimension

                                compressor.height = (metadata.actualHeight * ratio).toInt()
                                compressor.width = (metadata.actualWidth * ratio).toInt()

                                compressor.bitrate = 2_000_000
                                compressor.streamable = true

                                true
                            }
                        )

                        state = when (result) {
                            CompressionResult.Cancelled ->
                                AppState.Default

                            is CompressionResult.Error ->
                                AppState.Error(result.error)

                            CompressionResult.Success -> {
                                val end = System.currentTimeMillis()
                                AppState.Completed(input, output, end - start)
                            }
                        }
                    }
                }
            )



            VideoCompressorTheme {
                AnimatedContent(
                    targetState = state,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    modifier = Modifier.fillMaxSize()
                ) { target ->
                    when (target) {
                        AppState.Default -> {
                            Box(contentAlignment = Alignment.Center) {
                                Button(
                                    onClick = { launcher.launch("video/*") },
                                    content = {
                                        Text(text = "Select a video")
                                    }
                                )
                            }
                        }

                        is AppState.InProgress -> {
                            val input by remember { derivedStateOf { target.input } }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    space = 12.dp,
                                    alignment = Alignment.CenterVertically
                                )
                            ) {
                                LinearProgressIndicator()
                                Text(
                                    text = "Compression in progress",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Original:\n${input.name}\n${input.formatLength()}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        is AppState.Completed -> {
                            val duration by remember { derivedStateOf { target.duration } }
                            val input by remember { derivedStateOf { target.input } }
                            val output by remember { derivedStateOf { target.output } }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    space = 12.dp,
                                    alignment = Alignment.CenterVertically
                                )
                            ) {
                                Text(
                                    text = "Compression completed!",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Duration:\n${formatDuration(duration)}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Original:\n${input.name}\n${input.formatLength()}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Compressed:\n${output.name}\n${output.formatLength()}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )

                                Button(
                                    onClick = { viewFile(output) },
                                    content = {
                                        Text(text = "View compressed video")
                                    }
                                )

                                Button(
                                    onClick = { state = AppState.Default },
                                    content = {
                                        Text(text = "Restart")
                                    }
                                )
                            }
                        }

                        is AppState.Error -> {
                            val error by remember { derivedStateOf { target.error } }
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(
                                    space = 12.dp,
                                    alignment = Alignment.CenterVertically
                                )
                            ) {
                                Text(
                                    text = "Compression failed!",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = "Error:\n${error.localizedMessage}",
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { state = AppState.Default },
                                    content = {
                                        Text(text = "Restart")
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun copyFileToLocal(uri: Uri): File? {
        val fileName = generateFileName(uri) ?: return null
        val file = File(filesDir, fileName)
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val outputStream = file.outputStream()

        withContext(Dispatchers.IO) {
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
        }

        return file
    }

    private fun generateFileName(uri: Uri): String? {
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                val name = cursor.getString(nameIndex)
                val parts = name.split(".")

                return buildString {
                    append(parts.dropLast(1).joinToString("."))
                    append("_")
                    append(System.currentTimeMillis() / 1000)
                    append(".")
                    append(parts.last())
                }
            }
        }
        return null
    }

    private fun viewFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setData(uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        runCatching { startActivity(intent) }
    }

    fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0s"

        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return buildString {
            if (hours > 0) append("${hours}h ")
            if (minutes > 0 || hours > 0) append("${minutes}m ")
            append("${seconds}s")
        }.trim()
    }

    private fun File.formatLength(): String {
        val bytes = this.length()
        if (bytes <= 0) return "0 B"

        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt()
        val size = bytes / 1024.0.pow(digitGroups.toDouble())
        val formatter = DecimalFormat("#,##0.#")

        return "${formatter.format(size)} ${units[digitGroups]}"
    }
}