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
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.primaverahq.videocompressor.data.Metadata
import com.primaverahq.videocompressor.settings.CompressionSettings
import com.primaverahq.videocompressor.settings.EncoderSelectionMode
import com.primaverahq.videocompressor.utils.CompressorUtils
import com.primaverahq.videocompressor.utils.CompressorUtils.findTrack
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

    private suspend fun compress(
        context: Context,
        settings: CompressionSettings,
        output: File
    ) = runAsResult {
        val cache = File(context.cacheDir, input.name)

        val encoders = selectEncoders(settings.encoderSelectionMode)

        val errors = encoders.associate { name ->
            try {
                attemptCompression(settings, name, cache)
                processOutputFile(settings, cache, output)

                return@runAsResult
            } catch (e: Exception) {
                return@associate name to e
            }
        }

        // TODO: improve error propagation to calling code
        throw errors.toList().last().second
    }

    private fun selectEncoders(mode: EncoderSelectionMode): List<String> {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter {
                it.isEncoder && it.supportedTypes.contains("video/avc")
            }
            .map { it.name }

        return when (mode) {
            // Take the first on the list
            EncoderSelectionMode.DEFAULT -> codecs.take(1)
            EncoderSelectionMode.TRY_ALL -> codecs
        }
    }

    private suspend fun attemptCompression(
        settings: CompressionSettings,
        codecName: String,
        cache: File
    ) {
        val extractor = MediaExtractor().apply {
            setDataSource(input.absolutePath, null)
        }

        val mediaMuxer = MP4Builder().createMovie(setUpMP4Movie(0, cache))

        try {
            processVideo(codecName, settings, extractor, mediaMuxer)
            processAudio(extractor, mediaMuxer)

            mediaMuxer.finishMovie()
        } catch (e: Exception) {
            cache.delete()
            throw e
        } finally {
            extractor.release()
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun processVideo(
        codecName: String?,
        settings: CompressionSettings,
        extractor: MediaExtractor,
        mediaMuxer: MP4Builder
    ) = withContext(Dispatchers.Default) {
        val videoIndex = findTrack(extractor, isVideo = true)

        extractor.selectTrack(videoIndex)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val inputFormat = extractor.getTrackFormat(videoIndex)

        val bufferInfo = MediaCodec.BufferInfo()

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null

        try {
            encoder = createVideoEncoder(inputFormat, settings, codecName)
            inputSurface = InputSurface(encoder.createInputSurface()).apply { makeCurrent() }
            outputSurface = OutputSurface()
            decoder = createVideoDecoder(inputFormat, outputSurface)

            encoder.start()
            decoder.start()

            var inputDone = false
            var outputDone = false

            var videoTrackIndex = -5

            // encoding loop
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
                        throw CancellationException()
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

        } finally {
            decoder?.stop()
            decoder?.release()
            encoder?.stop()
            encoder?.release()
            inputSurface?.release()
            outputSurface?.release()
        }
        extractor.unselectTrack(videoIndex)
    }

    private suspend fun processOutputFile(
        settings: CompressionSettings,
        cache: File,
        output: File
    ) = withContext(Dispatchers.IO) {
        if (settings.streamable)
            StreamableVideo.start(cache, output)
        else
            cache.copyTo(output)

        cache.delete()
    }

    private fun createVideoEncoder(
        inputFormat: MediaFormat,
        settings: CompressionSettings,
        codecName: String?
    ): MediaCodec {
        val encoder = codecName?.let { MediaCodec.createByCodecName(it) }
            ?: throw IllegalStateException()

        val outputFormat = CompressorUtils.createOutputFormat(encoder, inputFormat, settings)

        try {
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            encoder.release()
            throw e
        }
        return encoder
    }

    private fun createVideoDecoder(
        format: MediaFormat,
        surface: OutputSurface
    ): MediaCodec {
        val mimeType = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalStateException()
        val decoder = MediaCodec.createDecoderByType(mimeType)

        try {
            decoder.configure(format, surface.getSurface(), null, 0)
        } catch (e: Exception) {
            decoder.release()
            throw e
        }
        return decoder
    }

    private suspend fun processAudio(
        extractor: MediaExtractor,
        mediaMuxer: MP4Builder
    ) = withContext(Dispatchers.IO) {
        val audioIndex = findTrack(extractor, isVideo = false)
        if (audioIndex >= 0) {
            extractor.selectTrack(audioIndex)
            val audioFormat = extractor.getTrackFormat(audioIndex)
            val muxerTrackIndex = mediaMuxer.addTrack(audioFormat, true)
            var maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            if (maxBufferSize <= 0) {
                maxBufferSize = 64 * 1024
            }

            var buffer = ByteBuffer.allocateDirect(maxBufferSize)
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
                if (!isActive) {
                    throw CancellationException()
                }
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

    companion object {

        private const val MEDIACODEC_TIMEOUT_DEFAULT = 1000L

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
         *        Return `null` to cancel compression, or an instance of `CompressionSettings` to proceed.
         *
         * @return A [CompressionResult] object indicating the result of the operation (success, failure, or cancellation).
         */
        suspend fun compress(
            context: Context,
            input: File,
            output: File,
            onMetadataDecoded: (VideoCompressor, Metadata) -> CompressionSettings?
        ): CompressionResult {
            val decoder = VideoCompressor(input)
            val metadata = decoder.decodeMetadata()
            val settings = onMetadataDecoded.invoke(decoder, metadata)
            if (settings == null)
                return CompressionResult.Cancelled

            return decoder.compress(context, settings, output)
        }
    }
}