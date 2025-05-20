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
package com.primaverahq.videocompressor.video

import android.media.MediaCodec
import android.media.MediaFormat
import com.googlecode.mp4parser.util.Matrix
import java.io.File
import java.util.*

class Mp4Movie {

    private var matrix = Matrix.ROTATE_0
    private val tracks = ArrayList<Track>()
    private var cacheFile: File? = null

    fun getMatrix(): Matrix? = matrix

    fun setCacheFile(file: File) {
        cacheFile = file
    }

    fun setRotation(angle: Int) {
        when (angle) {
            0 -> {
                matrix = Matrix.ROTATE_0
            }
            90 -> {
                matrix = Matrix.ROTATE_90
            }
            180 -> {
                matrix = Matrix.ROTATE_180
            }
            270 -> {
                matrix = Matrix.ROTATE_270
            }
        }
    }

    fun getTracks(): ArrayList<Track> = tracks

    fun getCacheFile(): File? = cacheFile

    fun addSample(trackIndex: Int, offset: Long, bufferInfo: MediaCodec.BufferInfo) {
        if (trackIndex < 0 || trackIndex >= tracks.size) {
            return
        }
        val track = tracks[trackIndex]
        track.addSample(offset, bufferInfo)
    }

    fun addTrack(mediaFormat: MediaFormat, isAudio: Boolean): Int {
        tracks.add(Track(tracks.size, mediaFormat, isAudio))
        return tracks.size - 1
    }
}
