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
package com.primaverahq.videocompressor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.primaverahq.videocompressor.data.Metadata
import com.primaverahq.videocompressor.utils.CompressorUtils.findTrack
import com.primaverahq.videocompressor.utils.CompressorUtils.hasQTI
import com.primaverahq.videocompressor.utils.CompressorUtils.setOutputFileParameters
import com.primaverahq.videocompressor.utils.CompressorUtils.setUpMP4Movie
import com.primaverahq.videocompressor.utils.StreamableVideo
import com.primaverahq.videocompressor.video.InputSurface
import com.primaverahq.videocompressor.video.MP4Builder
import com.primaverahq.videocompressor.video.OutputSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

class VideoCompressor private constructor(private val input: File) {

    private var _width: Int = DEFAULT_USE_SOURCE
    var width: Int
        get() = _width
        set(value) {
            require(value > 0) {

            }
            _width = value
        }

    private var _height: Int = DEFAULT_USE_SOURCE
    var height: Int
        get() = _height
        set(value) {
            require(value > 0) {

            }
            _height = value
        }

    private var _bitrate: Int = DEFAULT_BITRATE
    var bitrate: Int
        get() = _bitrate
        set(value) {
            require(value > 0) {

            }
            _bitrate = value
        }

    var streamable: Boolean = DEFAULT_STREAMABLE

    @Suppress("DEPRECATION")
    private suspend fun compress(context: Context, metadata: Metadata, output: File) = runAsResult {
        if (_width == DEFAULT_USE_SOURCE)
            _width = metadata.width

        if (_height == DEFAULT_USE_SOURCE)
            _height = metadata.height

        val cache = File(context.cacheDir, input.name)

        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath, null)

            // MediaCodec accesses encoder and decoder components and processes the new video
            //input to generate a compressed/smaller size video
            val bufferInfo = MediaCodec.BufferInfo()

            // Setup mp4 movie
            val movie = setUpMP4Movie(0, cache)

            // MediaMuxer outputs MP4 in this app
            val mediaMuxer = MP4Builder().createMovie(movie)

            // Start with video track
            val videoIndex = findTrack(extractor, isVideo = true)

            extractor.selectTrack(videoIndex)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = extractor.getTrackFormat(videoIndex)

            val outputFormat: MediaFormat =
                MediaFormat.createVideoFormat(MIME_TYPE, _width, _height)
            //set output format
            setOutputFileParameters(
                inputFormat,
                outputFormat,
                _bitrate,
            )

            val hasQTI = hasQTI()
            val encoder = prepareEncoder(outputFormat, hasQTI)

            var inputDone = false
            var outputDone = false

            var videoTrackIndex = -5

            val inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            val outputSurface = OutputSurface()

            //Move to executing state
            encoder.start()

            val decoder = prepareDecoder(inputFormat, outputSurface)

            //Move to executing state
            decoder.start()

            while (!outputDone) {
                if (!inputDone) {

                    val index = extractor.sampleTrackIndex

                    if (index == videoIndex) {
                        val inputBufferIndex =
                            decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                            val chunkSize = extractor.readSampleData(inputBuffer!!, 0)
                            when {
                                chunkSize < 0 -> {

                                    decoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                }

                                else -> {

                                    decoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        chunkSize,
                                        extractor.sampleTime,
                                        0
                                    )
                                    extractor.advance()

                                }
                            }
                        }

                    } else if (index == -1) { //end of file
                        val inputBufferIndex =
                            decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                        if (inputBufferIndex >= 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        }
                    }
                }

                var decoderOutputAvailable = true
                var encoderOutputAvailable = true

                loop@ while (decoderOutputAvailable || encoderOutputAvailable) {

                    if (!isActive) {
                        dispose(
                            decoder = decoder,
                            encoder = encoder,
                            inputSurface = inputSurface,
                            outputSurface = outputSurface,
                            extractor = extractor
                        )

                        return@withContext
                    }

                    //Encoder
                    val encoderStatus =
                        encoder.dequeueOutputBuffer(bufferInfo, MEDIACODEC_TIMEOUT_DEFAULT)

                    when {
                        encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER ->
                            encoderOutputAvailable = false

                        encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = encoder.outputFormat
                            if (videoTrackIndex == -5)
                                videoTrackIndex = mediaMuxer.addTrack(newFormat, false)
                        }

                        encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            // ignore this status
                        }

                        encoderStatus < 0 ->
                            throw RuntimeException("unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")

                        else -> {
                            val encodedData = encoder.getOutputBuffer(encoderStatus)
                                ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")

                            if (bufferInfo.size > 1) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    mediaMuxer.writeSampleData(
                                        videoTrackIndex,
                                        encodedData, bufferInfo, false
                                    )
                                }

                            }

                            outputDone =
                                bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            encoder.releaseOutputBuffer(encoderStatus, false)
                        }
                    }
                    if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) continue@loop

                    //Decoder
                    val decoderStatus =
                        decoder.dequeueOutputBuffer(bufferInfo, MEDIACODEC_TIMEOUT_DEFAULT)
                    when {
                        decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER ->
                            decoderOutputAvailable = false

                        decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            // ignore this status
                        }

                        decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // ignore this status
                        }

                        decoderStatus < 0 ->
                            throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")

                        else -> {
                            val doRender = bufferInfo.size != 0

                            decoder.releaseOutputBuffer(decoderStatus, doRender)
                            if (doRender) {
                                var errorWait = false
                                try {
                                    outputSurface.awaitNewImage()
                                } catch (e: Exception) {
                                    errorWait = true
                                    Log.e(
                                        "Compressor",
                                        e.message ?: "Compression failed at swapping buffer"
                                    )
                                }

                                if (!errorWait) {
                                    outputSurface.drawImage()

                                    inputSurface.setPresentationTime(bufferInfo.presentationTimeUs * 1000)

                                    inputSurface.swapBuffers()
                                }
                            }
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decoderOutputAvailable = false
                                encoder.signalEndOfInputStream()
                            }
                        }
                    }
                }
            }

            extractor.unselectTrack(videoIndex)

            dispose(
                decoder = decoder,
                encoder = encoder,
                inputSurface = inputSurface,
                outputSurface = outputSurface
            )

            processAudio(
                mediaMuxer = mediaMuxer,
                bufferInfo = bufferInfo,
                extractor = extractor
            )

            dispose(
                extractor = extractor
            )

            mediaMuxer.finishMovie()
        }

        withContext(Dispatchers.IO) {
            if (streamable) StreamableVideo.start(cache, output)
            else cache.copyTo(output)

            cache.delete()
        }
    }

    private suspend fun runAsResult(block: suspend () -> Unit): CompressionResult {
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

    private fun decodeMetadata(): Metadata {
        val retriever = MediaMetadataRetriever()

        runCatching { retriever.setDataSource(input.absolutePath) }.getOrNull()

        val width = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
            ?: -1
        val height = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
            ?: -1
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()
            ?: -1
        val bitrate = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            ?.toIntOrNull()
            ?: -1

        return Metadata(width, height, rotation, bitrate)
    }

    private fun processAudio(
        mediaMuxer: MP4Builder,
        bufferInfo: MediaCodec.BufferInfo,
        extractor: MediaExtractor
    ) {
        val audioIndex = findTrack(extractor, isVideo = false)
        if (audioIndex >= 0) {
            extractor.selectTrack(audioIndex)
            val audioFormat = extractor.getTrackFormat(audioIndex)
            val muxerTrackIndex = mediaMuxer.addTrack(audioFormat, true)
            var maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)

            if (maxBufferSize <= 0) {
                maxBufferSize = 64 * 1024
            }

            var buffer: ByteBuffer = ByteBuffer.allocateDirect(maxBufferSize)
            if (Build.VERSION.SDK_INT >= 28) {
                val size = extractor.sampleSize
                if (size > maxBufferSize) {
                    maxBufferSize = (size + 1024).toInt()
                    buffer = ByteBuffer.allocateDirect(maxBufferSize)
                }
            }
            var inputDone = false
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (!inputDone) {
                val index = extractor.sampleTrackIndex
                if (index == audioIndex) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)

                    if (bufferInfo.size >= 0) {
                        bufferInfo.apply {
                            presentationTimeUs = extractor.sampleTime
                            offset = 0
                            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        mediaMuxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo, true)
                        extractor.advance()

                    } else {
                        bufferInfo.size = 0
                        inputDone = true
                    }
                } else if (index == -1) {
                    inputDone = true
                }
            }
            extractor.unselectTrack(audioIndex)
        }
    }

    private fun prepareEncoder(outputFormat: MediaFormat, hasQTI: Boolean): MediaCodec {

        // This seems to cause an issue with certain phones
        // val encoderName = MediaCodecList(REGULAR_CODECS).findEncoderForFormat(outputFormat)
        // val encoder: MediaCodec = MediaCodec.createByCodecName(encoderName)
        // Log.i("encoderName", encoder.name)
        // c2.qti.avc.encoder results in a corrupted .mp4 video that does not play in
        // Mac and iphones
        var encoder = if (hasQTI) {
            MediaCodec.createByCodecName("c2.android.avc.encoder")
        } else {
            MediaCodec.createEncoderByType(MIME_TYPE)
        }

        try {
            encoder.configure(
                outputFormat, null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        } catch (_: Exception) {
            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder.configure(
                outputFormat, null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        }

        return encoder
    }

    private fun prepareDecoder(
        inputFormat: MediaFormat,
        outputSurface: OutputSurface,
    ): MediaCodec {
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)

        decoder.configure(inputFormat, outputSurface.getSurface(), null, 0)

        return decoder
    }

    private fun dispose(
        decoder: MediaCodec? = null,
        encoder: MediaCodec? = null,
        inputSurface: InputSurface? = null,
        outputSurface: OutputSurface? = null,
        extractor: MediaExtractor? = null
    ) {
        runCatching {
            decoder?.stop()
            decoder?.release()

            encoder?.stop()
            encoder?.release()

            inputSurface?.release()
            outputSurface?.release()
            extractor?.release()
        }
    }

    companion object {

        // H.264 Advanced Video Coding
        private const val MIME_TYPE = "video/avc"
        private const val MEDIACODEC_TIMEOUT_DEFAULT = 100L

        private const val DEFAULT_BITRATE = 8_000_000
        private const val DEFAULT_STREAMABLE = true
        private const val DEFAULT_USE_SOURCE = -1

        /**
         * Compresses a video file.
         *
         * This function handles the compression of the specified input video file and writes the result
         * to the specified output file. The compression process is configured using the provided metadata callback,
         * which is triggered before compression starts.
         *
         * @param context android.content.Context.
         * @param input The input video [File] to be compressed. Must exist and be a valid video file.
         * @param output The output [File] where the compressed video will be saved. The caller is responsible
         *        for ensuring that this file can be written to (e.g., proper permissions, writable path).
         * @param onMetadataDecoded A callback invoked after the input video's metadata is read but before compression begins.
         *        The callback receives an instance of [VideoCompressor] and the parsed [Metadata] from the input video.
         *        Use this to adjust compression parameters.
         *        Return `true` to proceed with compression, or `false` to cancel the process.
         *
         * @return A [CompressionResult] object indicating the result of the operation (success, failure, or cancellation).
         */
        suspend fun compress(
            context: Context,
            input: File,
            output: File,
            onMetadataDecoded: (VideoCompressor, Metadata) -> Boolean
        ): CompressionResult {
            val decoder = VideoCompressor(input)
            val metadata = decoder.decodeMetadata()
            val shouldContinue = onMetadataDecoded.invoke(decoder, metadata)
            if (!shouldContinue)
                return CompressionResult.Cancelled

            return decoder.compress(context, metadata, output)
        }
    }
}