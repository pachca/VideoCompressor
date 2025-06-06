package com.primaverahq.videocompressor.settings

/**
 * Defines the strategy for selecting video encoders during compression.
 *
 * The encoder selection mode determines how aggressively the compressor will
 * attempt different encoders when processing video.
 *
 * @see CompressionSettings.Builder.setEncoderSelectionMode for configuration
 */
enum class EncoderSelectionMode {
    /**
     * Uses only the system's default encoder (fastest path).
     */
    DEFAULT,

    /**
     * Attempts all available encoders in default order until compression succeeds.
     * Use this when maximum device compatibility is required, at the cost of:
     * - Longer compression time: if an encoder fails the compression at 99% progress,
     *   the next one will have to start from scratch.
     * - Potentially higher memory usage from instantiating MediaCodec objects.
     */
    TRY_ALL
}