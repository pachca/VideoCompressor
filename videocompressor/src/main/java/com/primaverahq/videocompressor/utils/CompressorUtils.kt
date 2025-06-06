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

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.primaverahq.videocompressor.settings.CompressionSettings
import com.primaverahq.videocompressor.video.Mp4Movie
import java.io.File
import kotlin.math.min

internal object CompressorUtils {

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
     * Creates and configures a MediaFormat for video compression based on input parameters.
     *
     * This handles:
     * - Dimension adjustment (if [CompressionSettings.allowSizeAdjustments] is true)
     * - Bitrate configuration
     * - Frame rate and keyframe interval preservation
     * - Color format and standards setup
     * - Codec profile selection
     *
     * NOTE: This implementation intentionally avoids constrained profiles
     * AVCProfileConstrainedHigh and AVCProfileConstrainedBaseline
     * even when supported by the encoder.
     *
     * The output format is specifically configured for encoder compatibility,
     * making adjustments when necessary to meet codec requirements.
     *
     * @param encoder The MediaCodec encoder instance that will be used
     * @param inputFormat The source video's MediaFormat containing original parameters
     * @param settings Compression configuration including target dimensions and bitrate
     * @return A fully configured MediaFormat ready for encoder configuration
     *
     * @see adjustVideoDimensions For details on size adjustment logic
     * @see MediaCodecInfo.VideoCapabilities For supported encoder constraints
     */
    fun createOutputFormat(
        encoder: MediaCodec,
        inputFormat: MediaFormat,
        settings: CompressionSettings
    ): MediaFormat {
        val capabilities = encoder.codecInfo.getCapabilitiesForType("video/avc")
        val videoCapabilities = capabilities.videoCapabilities

        val (width, height) = if (settings.allowSizeAdjustments)
            adjustVideoDimensions(
                targetWidth = settings.width,
                targetHeight = settings.height,
                videoCapabilities = videoCapabilities
            )
        else
            settings.width to settings.height

        // Create basic format
        val outputFormat = MediaFormat.createVideoFormat("video/avc", width, height)

        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrate)
        outputFormat.setInteger(
            MediaFormat.KEY_BITRATE_MODE,
            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
        )

        outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, getFrameRate(inputFormat))
        outputFormat.setInteger(
            MediaFormat.KEY_I_FRAME_INTERVAL,
            getIFrameIntervalRate(inputFormat)
        )

        outputFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            outputFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, getColorStandard(inputFormat))
            outputFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, getColorTransfer(inputFormat))
            outputFormat.setInteger(MediaFormat.KEY_COLOR_RANGE, getColorRange(inputFormat))
        }

        val profile = encoder.codecInfo
            .getCapabilitiesForType("video/avc")
            .profileLevels
            .map { it.profile }
            .filter { it <= MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 }
            .max()

        outputFormat.setInteger(MediaFormat.KEY_PROFILE, profile)

        return outputFormat
    }

    /**
     * Adjusts video dimensions to meet encoder alignment and range requirements.
     *
     * Handles two key constraints:
     * 1. Dimensions must be within the encoder's supported ranges
     * 2. Dimensions must be multiples of the encoder's alignment requirements
     *
     * Note: Does not currently preserve aspect ratio (planned for future update).
     * Some advanced encoder constraints (block-level requirements) cannot be checked
     * through public APIs.
     *
     * @param targetWidth Desired output width before adjustments
     * @param targetHeight Desired output height before adjustments
     * @param videoCapabilities Encoder's supported capabilities
     * @return Pair of (adjustedWidth, adjustedHeight) meeting basic encoder requirements
     */
    private fun adjustVideoDimensions(
        targetWidth: Int,
        targetHeight: Int,
        videoCapabilities: MediaCodecInfo.VideoCapabilities
    ): Pair<Int, Int> {
        // 1. Coerce to supported ranges
        val coercedWidth = targetWidth.coerceIn(
            videoCapabilities.supportedWidths.lower,
            videoCapabilities.supportedWidths.upper
        )
        val coercedHeight = targetHeight.coerceIn(
            videoCapabilities.supportedHeights.lower,
            videoCapabilities.supportedHeights.upper
        )

        // 2. Align to encoder requirements
        val widthAlignment = videoCapabilities.widthAlignment
        val heightAlignment = videoCapabilities.heightAlignment
        val adjustedWidth = (coercedWidth / widthAlignment) * widthAlignment
        val adjustedHeight = (coercedHeight / heightAlignment) * heightAlignment

        return adjustedWidth to adjustedHeight
    }

    private fun getFrameRate(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_FRAME_RATE))
            format.getInteger(MediaFormat.KEY_FRAME_RATE)
        else 30
    }

    private fun getIFrameIntervalRate(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL))
            format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL)
        else I_FRAME_INTERVAL
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getColorStandard(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD))
            format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
        else 0
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getColorTransfer(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER))
            format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
        else 0
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun getColorRange(format: MediaFormat): Int {
        return if (format.containsKey(MediaFormat.KEY_COLOR_RANGE))
            format.getInteger(MediaFormat.KEY_COLOR_RANGE)
        else 0
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
}