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

import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.view.Surface

class OutputSurface : OnFrameAvailableListener {

    private var mSurfaceTexture: SurfaceTexture? = null
    private var mSurface: Surface? = null
    private val mFrameSyncObject = Object()
    private var mFrameAvailable = false
    private var mTextureRender: TextureRenderer? = null

    /**
     * Creates an OutputSurface using the current EGL context. This Surface will be
     * passed to MediaCodec.configure().
     */
    init {
        setup()
    }

    /**
     * Creates instances of TextureRender and SurfaceTexture, and a Surface associated
     * with the SurfaceTexture.
     */
    private fun setup() {
        mTextureRender = TextureRenderer()
        mTextureRender?.let {
            it.surfaceCreated()

            // Even if we don't access the SurfaceTexture after the constructor returns, we
            // still need to keep a reference to it. The Surface doesn't retain a reference
            // at the Java level, so if we don't either then the object can get GCed, which
            // causes the native finalizer to run.
            mSurfaceTexture = SurfaceTexture(it.getTextureId())
            mSurfaceTexture?.let { surfaceTexture ->
                surfaceTexture.setOnFrameAvailableListener(this)
                mSurface = Surface(mSurfaceTexture)
            }
        }
    }

    /**
     * Discards all resources held by this class, notably the EGL context.
     */
    fun release() {
        mSurface?.release()

        mTextureRender = null
        mSurface = null
        mSurfaceTexture = null
    }

    /**
     * Returns the Surface that we draw onto.
     */
    fun getSurface(): Surface? = mSurface

    /**
     * Latches the next buffer into the texture.  Must be called from the thread that created
     * the OutputSurface object, after the onFrameAvailable callback has signaled that new
     * data is available.
     */
    fun awaitNewImage() {
        val timeOutMS = 100
        synchronized(mFrameSyncObject) {
            while (!mFrameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    mFrameSyncObject.wait(timeOutMS.toLong())
                    if (!mFrameAvailable) {
                        throw RuntimeException("Surface frame wait timed out")
                    }
                } catch (ie: InterruptedException) {
                    throw RuntimeException(ie)
                }
            }
            mFrameAvailable = false
        }
        mTextureRender?.checkGlError("before updateTexImage")
        mSurfaceTexture?.updateTexImage()
    }

    /**
     * Draws the data from SurfaceTexture onto the current EGL surface.
     */
    fun drawImage() {
        mTextureRender?.drawFrame(mSurfaceTexture!!)
    }

    override fun onFrameAvailable(p0: SurfaceTexture?) {
        synchronized(mFrameSyncObject) {
            if (mFrameAvailable) {
                throw RuntimeException("mFrameAvailable already set, frame could be dropped")
            }
            mFrameAvailable = true
            mFrameSyncObject.notifyAll()
        }
    }
}
