package com.primaverahq.videocompressor.settings

import android.util.Size
import java.util.Base64

/**
 * Configuration settings for video compression operations.
 *
 * This class provides a builder pattern to configure video compression parameters.
 * All settings are immutable once built. Use [Builder] to create instances.
 *
 * ### Usage Example:
 * ```kotlin
 * val settings = CompressionSettings.Builder()
 *     .setTargetSize(1280, 720)
 *     .setBitrate(5_000_000)
 *     .setStreamable(false)
 *     .allowSizeAdjustments(true)
 *     .build()
 * ```
 *
 * @property width The target width in pixels for the compressed video
 * @property height The target height in pixels for the compressed video
 * @property bitrate The target bitrate in bits per second (default: 4,000,000)
 * @property streamable Whether the output should be optimized for streaming (default: true)
 * @property allowSizeAdjustments Whether minor size adjustments are allowed to maintain
 *                                codec requirements (default: true)
 */
class CompressionSettings private constructor(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val streamable: Boolean,
    val allowSizeAdjustments: Boolean,
    val encoderSelectionMode: EncoderSelectionMode
) {

    /**
     * Builder for creating [CompressionSettings] instances.
     *
     * Provides a fluent API to configure all compression parameters.
     * Required parameters must be set before calling [build()].
     */
    class Builder {
        private var width: Int? = null
        private var height: Int? = null
        private var bitrate: Int = 4_000_000
        private var streamable: Boolean = true
        private var allowSizeAdjustments: Boolean = true
        private var encoderSelectionMode: EncoderSelectionMode = EncoderSelectionMode.DEFAULT

        /**
         * Sets the target dimensions for the compressed video.
         *
         * @param width The target width in pixels
         * @param height The target height in pixels
         * @return This builder instance for method chaining
         */
        fun setTargetSize(width: Int, height: Int) = apply {
            require(width > 0) { "Width must be a positive value" }
            require(height > 0) { "Height must be a positive value" }

            this.width = width
            this.height = height
        }

        /**
         * Sets the target dimensions using a [android.util.Size] object.
         *
         * @param size The target dimensions as a [android.util.Size] object
         * @return This builder instance for method chaining
         */
        fun setTargetSize(size: Size) = apply {
            require(size.width > 0) { "Width must be a positive value" }
            require(size.height > 0) { "Height must be a positive value" }

            this.width = size.width
            this.height = size.height
        }

        /**
         * Sets the target bitrate for the compressed video.
         *
         * @param bitrate The bitrate in bits per second
         * @return This builder instance for method chaining
         * @throws IllegalArgumentException if bitrate is not positive
         */
        fun setBitrate(bitrate: Int) = apply {
            require(bitrate > 0) { "Bitrate must be a positive value" }
            this.bitrate = bitrate
        }

        /**
         * Sets whether the output should be optimized for streaming.
         *
         * @param streamable true to optimize for streaming (default: true)
         * @return This builder instance for method chaining
         */
        fun setStreamable(streamable: Boolean) = apply {
            this.streamable = streamable
        }

        /**
         * Sets whether size adjustments are allowed to meet codec requirements.
         *
         * When true, the compressor may adjust dimensions to meet codec capabilities.
         *
         * @param allow true to permit adjustments (default: true)
         * @return This builder instance for method chaining
         */
        fun allowSizeAdjustments(allow: Boolean) = apply {
            this.allowSizeAdjustments = allow
        }

        /**
         * Sets the strategy for video encoder selection during compression.
         *
         * @param mode The selection strategy to use, which can be one of:
         * - [EncoderSelectionMode.DEFAULT]: Uses only the system's default encoder (fastest)
         * - [EncoderSelectionMode.TRY_ALL]: Tests all available encoders until one succeeds (most compatible)
         *
         * @return This builder instance for method chaining
         *
         * @see EncoderSelectionMode for detailed behavior of each mode
         */
        fun setEncoderSelectionMode(mode: EncoderSelectionMode) = apply {
            this.encoderSelectionMode = mode
        }

        /**
         * Builds the [CompressionSettings] instance.
         *
         * @return The immutable compression settings
         * @throws IllegalArgumentException if required parameters are not set
         */
        fun build(): CompressionSettings {
            val width = width
                ?: throw IllegalArgumentException("Width and height must be set")
            val height = height
                ?: throw IllegalArgumentException("Width and height must be set")

            return CompressionSettings(
                width = width,
                height = height,
                bitrate = bitrate,
                streamable = streamable,
                allowSizeAdjustments = allowSizeAdjustments,
                encoderSelectionMode = encoderSelectionMode
            )
        }
    }
}