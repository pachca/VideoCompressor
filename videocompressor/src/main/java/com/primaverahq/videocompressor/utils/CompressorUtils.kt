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

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.primaverahq.videocompressor.video.Mp4Movie
import java.io.File

object CompressorUtils {

    // 1 second between I-frames
    private const val I_FRAME_INTERVAL = 1

    /**
     * Setup movie with the height, width, and rotation values
     * @param rotation video rotation
     *
     * @return set movie with new values
     */
    fun setUpMP4Movie(rotation: Int, cacheFile: File): Mp4Movie {
        val movie = Mp4Movie()
        movie.setCacheFile(cacheFile)
        movie.setRotation(rotation)
        return movie
    }

    /**
     * Set output parameters like bitrate and frame rate
     */
    fun setOutputFileParameters(
        inputFormat: MediaFormat,
        outputFormat: MediaFormat,
        newBitrate: Int
    ) {
        val newFrameRate = getFrameRate(inputFormat)
        val iFrameInterval = getIFrameIntervalRate(inputFormat)
        outputFormat.apply {

            // according to https://developer.android.com/media/optimize/sharing#b-frames_and_encoding_profiles
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val type = outputFormat.getString(MediaFormat.KEY_MIME)
                val higherLevel = getHighestCodecProfileLevel(type)
                Log.i("Output file parameters", "Selected CodecProfileLevel: $higherLevel")
                setInteger(MediaFormat.KEY_PROFILE, higherLevel)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }

            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )

            setInteger(MediaFormat.KEY_FRAME_RATE, newFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            // expected bps
            setInteger(MediaFormat.KEY_BIT_RATE, newBitrate)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getColorStandard(inputFormat)?.let {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, it)
                }

                getColorTransfer(inputFormat)?.let {
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, it)
                }

                getColorRange(inputFormat)?.let {
                    setInteger(MediaFormat.KEY_COLOR_RANGE, it)
                }
            }


            Log.i(
                "Output file parameters",
                "videoFormat: $this"
            )
        }
    }

    private fun getFrameRate(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) format.getInteger(MediaFormat.KEY_FRAME_RATE)
        else 30
    }

    private fun getIFrameIntervalRate(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) format.getInteger(
            MediaFormat.KEY_I_FRAME_INTERVAL
        )
        else I_FRAME_INTERVAL
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getColorStandard(format: MediaFormat): Int? {
        return if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
            format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
        else null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getColorTransfer(format: MediaFormat): Int? {
        return if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        else null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getColorRange(format: MediaFormat): Int? {
        return if (format.containsKey(MediaFormat.KEY_COLOR_RANGE))
            format.getInteger(MediaFormat.KEY_COLOR_RANGE)
        else null
    }

    /**
     * Counts the number of tracks (video, audio) found in the file source provided
     * @param extractor what is used to extract the encoded data
     * @param isVideo to determine whether we are processing video or audio at time of call
     * @return index of the requested track
     */
    fun findTrack(extractor: MediaExtractor, isVideo: Boolean): Int {
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (isVideo) {
                if (mime?.startsWith("video/")!!) return i
            } else {
                if (mime?.startsWith("audio/")!!) return i
            }
        }
        return -5
    }

    fun hasQTI(): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (codec in list) {
            Log.i("CODECS: ", codec.name)
            if (codec.name.contains("qti.avc")) {
                return true
            }
        }
        return false
    }

    /**
     * Get the highest profile level supported by the AVC encoder: High > Main > Baseline
     */
    private fun getHighestCodecProfileLevel(type: String?): Int {
        if (type == null) {
            return MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        }
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val capabilities = list
            .filter { codec -> type in codec.supportedTypes && codec.name.contains("encoder") }
            .mapNotNull { codec -> codec.getCapabilitiesForType(type) }

        capabilities.forEach { capabilitiesForType ->
            val levels =  capabilitiesForType.profileLevels.map { it.profile }
            return when {
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh in levels -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                MediaCodecInfo.CodecProfileLevel.AVCProfileMain in levels -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
            }
        }

        return MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
    }
}