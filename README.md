[![JitPack](https://jitpack.io/v/pachca/VideoCompressor.svg)](https://jitpack.io/#pachca/VideoCompressor)

# VideoCompressor

A lightweight Android library for compressing video files using `MediaCodec`.

Based on the [LightCompressor](https://github.com/AbedElazizShe/LightCompressor)
and uses some of its parts.
The API is inspired by Android
[ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder).

## Features

- Compress video files using H.264 codecs
  via [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)
- Inspect video metadata and apply settings before compression
- Coroutines and cancellation
- Compatible with Android 5.0+ (API 21+)

## Usage

Add the JitPack repository to your project level `build.gradle.kts`:

```kotlin
allprojects {
    repositories {
        google()
        maven(url = "https://jitpack.io")
    }
}
```

Add this to your app `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.pachca:VideoCompressor:v2.0.3")
}
```

```kotlin
scope.launch {
    val result = VideoCompressor.compress(
        context = context,

        // Input File
        input = input,

        // Output file (make sure the app can actually write at this path)
        output = output,

        // Callback is fired before the actual compression.
        // Allows setting parameters like video resolution and bitrate.
        onMetadataDecoded = { compressor, metadata ->
            
            // Return a CompressionSettings instance to proceed or null to cancel
            CompressionSettings.Builder()
                .setTargetSize(
                    width = metadata.actualWidth / 2,
                    height = metadata.actualHeight / 2
                )
                .setBitrate(2_000_000)
                .setStreamable(true)
                .allowSizeAdjustments(true)
                .setEncoderSelectionMode(EncoderSelectionMode.TRY_ALL)
                .build()
        }
    )
}
```

## Compatibility

Minimum Android SDK: VideoCompressor requires a minimum API level of 21.

## What's next?

- Improve logging
- Extend available metadata and encoding settings
- Switch
  to [Asynchronous processing](https://developer.android.com/reference/android/media/MediaCodec#data-processing)
- H.265 (_Maybe?_)

## Credits

[Telegram](https://github.com/DrKLO/Telegram) for Android.

[LightCompressor](https://github.com/AbedElazizShe/LightCompressor) - original library.

