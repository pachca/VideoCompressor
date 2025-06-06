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
package com.primaverahq.videocompressor

import kotlinx.coroutines.CancellationException

sealed interface CompressionResult {

    object Success: CompressionResult

    object Cancelled: CompressionResult

    class Error(val error: CompressionException): CompressionResult
}

internal suspend fun runAsResult(block: suspend () -> Unit): CompressionResult {
    return runCatching {
        block.invoke()
        CompressionResult.Success
    }.getOrElse { error ->
        return when (error) {
            is CancellationException ->
                CompressionResult.Cancelled

            is CompressionException ->
                CompressionResult.Error(error)

            else ->
                CompressionResult.Error(CompressionException("Unknown error", error))
        }
    }
}
