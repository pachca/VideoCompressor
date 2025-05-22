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
package com.primaverahq.videocompressor.data

import android.util.Size

class Metadata(
    internal val width: Int,
    internal val height: Int,
    internal val rotation: Int
) {
    val actualWidth: Int get() =
        if (rotation == 90 || rotation == 270) height else width

    val actualHeight: Int get() =
        if (rotation == 90 || rotation == 270) width else height

    val size: Size get() =
        Size(actualWidth, actualHeight)
}