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
package com.primaverahq.videocompressor.utils

import android.media.MediaFormat

/**
 * Same as
 * https://developer.android.com/reference/android/media/MediaFormat#getInteger(java.lang.String,%20int)
 * but without API 29+ requirement.
 */
fun MediaFormat.getIntegerCompat(name: String, defaultValue: Int): Int {
    return if (containsKey(name)) getInteger(name) else defaultValue
}