package com.primaverahq.videocompressor.sample

import com.primaverahq.videocompressor.CompressionException
import java.io.File

sealed interface AppState {

    object Default: AppState

    class InProgress(val input: File): AppState

    class Completed(val input: File, val output: File, val duration: Long): AppState

    class Error(val error: CompressionException): AppState

}