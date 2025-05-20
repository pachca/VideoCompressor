/*
 * This file is part of VideoCompressor library.
 *
 * Originally based on code from the LightCompressor project,
 * licensed under the Apache License, Version 2.0.
 * See: https://github.com/AbedElazizShe/LightCompressor
 *
 * Copyright (C) Abed Elaziz Shehadeh
 * Modifications and additions Copyright (C) 2025 Primavera
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
package com.primaverahq.videocompressor.utils

fun uInt32ToLong(int32: Int): Long {
    return int32.toLong()
}

fun uInt32ToInt(uInt32: Long): Int {
    if (uInt32 > Int.MAX_VALUE || uInt32 < 0) {
        throw Exception("uInt32 value is too large")
    }
    return uInt32.toInt()
}

fun uInt64ToLong(uInt64: Long): Long {
    if (uInt64 < 0) throw Exception("uInt64 value is too large")
    return uInt64
}


fun uInt32ToInt(uInt32: Int): Int {
    if (uInt32 < 0) {
        throw Exception("uInt32 value is too large")
    }
    return uInt32
}
