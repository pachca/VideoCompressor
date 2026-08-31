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

import android.util.Log
import com.primaverahq.videocompressor.data.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal object StreamableVideo {

    private const val TAG = "StreamableVideo"
    private const val ATOM_PREAMBLE_SIZE = 8

    /** Returns true only when moov precedes the first non-empty mdat atom. */
    fun isFastStartOptimized(input: File): Boolean {
        return try {
            FileInputStream(input).use { inputStream ->
                isFastStartOptimized(inputStream.channel)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * @param input  Input file.
     * @param output Output file.
     * @return false if input file was already fast start and copied unchanged.
     * @throws IOException
     * @throws IllegalArgumentException
     */
    fun start(input: File, output: File): Boolean {
        require(input.canonicalFile != output.canonicalFile) {
            "Input and output files must be different"
        }

        return try {
            val converted = FileInputStream(input).use { inStream ->
                FileOutputStream(output).use { outStream ->
                    convert(inStream.channel, outStream.channel)
                }
            }

            if (!converted) input.copyTo(output, overwrite = true)
            converted
        } catch (e: Exception) {
            output.delete()
            throw e
        }
    }

    private fun isFastStartOptimized(infile: FileChannel): Boolean {
        val atomBytes = ByteBuffer.allocate(ATOM_PREAMBLE_SIZE).order(ByteOrder.BIG_ENDIAN)
        val fileSize = infile.size()
        var moovSeen = false

        while (readAndFill(infile, atomBytes)) {
            val encodedSize = uInt32ToLong(atomBytes.int)
            val atomType = atomBytes.int
            val payloadSize = when (encodedSize) {
                0L -> fileSize - infile.position()
                1L -> {
                    if (!readAndFill(infile, atomBytes)) return false

                    val extendedSize = uInt64ToLong(atomBytes.long)
                    if (extendedSize < ATOM_PREAMBLE_SIZE * 2) return false
                    extendedSize - ATOM_PREAMBLE_SIZE * 2
                }
                else -> {
                    if (encodedSize < ATOM_PREAMBLE_SIZE) return false
                    encodedSize - ATOM_PREAMBLE_SIZE
                }
            }

            if (payloadSize < 0 || payloadSize > fileSize - infile.position()) return false
            if (atomType == MOOV_ATOM && payloadSize > 0) moovSeen = true
            if (atomType == MDAT_ATOM && payloadSize > 0) return moovSeen

            infile.position(infile.position() + payloadSize)
        }

        return false
    }

    private fun convert(infile: FileChannel, outfile: FileChannel): Boolean {
        val atomBytes = ByteBuffer.allocate(ATOM_PREAMBLE_SIZE).order(ByteOrder.BIG_ENDIAN)
        var atomType = 0
        var atomSize: Long = 0
        val lastOffset: Long
        val moovAtom: ByteBuffer
        var ftypAtom: ByteBuffer? = null
        var startOffset: Long = 0
        var mdatSeen = false
        var moovBeforeMdat = false

        // traverse through the atoms in the file to make sure that 'moov' is at the end
        while (readAndFill(infile, atomBytes)) {
            atomSize = uInt32ToLong(atomBytes.int)
            atomType = atomBytes.int

            if (atomType != FREE_ATOM
                && atomType != JUNK_ATOM
                && atomType != MDAT_ATOM
                && atomType != MOOV_ATOM
                && atomType != PNOT_ATOM
                && atomType != SKIP_ATOM
                && atomType != WIDE_ATOM
                && atomType != PICT_ATOM
                && atomType != UUID_ATOM
                && atomType != FTYP_ATOM
            ) {
                throw IOException("encountered non-QT top-level atom")
            }

            if (atomType == MOOV_ATOM && !mdatSeen) moovBeforeMdat = true
            if (atomType == MDAT_ATOM) mdatSeen = true

            if (atomSize == 0L) {
                infile.position(infile.size())
                continue
            }
            if (atomSize != 1L && atomSize < ATOM_PREAMBLE_SIZE) {
                throw IOException("invalid atom size")
            }

            // keep ftyp atom
            if (atomType == FTYP_ATOM) {
                if (atomSize == 1L) throw IOException("extended ftyp atom is not supported")
                if (atomSize - ATOM_PREAMBLE_SIZE > infile.size() - infile.position()) {
                    throw IOException("ftyp atom exceeds input size")
                }

                val ftypAtomSize = uInt32ToInt(atomSize)
                ftypAtom = ByteBuffer.allocate(ftypAtomSize).order(ByteOrder.BIG_ENDIAN)
                atomBytes.rewind()
                ftypAtom.put(atomBytes)
                if (infile.read(ftypAtom) < ftypAtomSize - ATOM_PREAMBLE_SIZE) {
                    throw IOException("failed to read ftyp atom")
                }
                ftypAtom.flip()
                startOffset = infile.position() // after ftyp atom
            } else {
                if (atomSize == 1L) {
                    /* 64-bit special case */
                    atomBytes.clear()
                    if (!readAndFill(infile, atomBytes)) {
                        throw IOException("failed to read extended atom size")
                    }
                    atomSize = uInt64ToLong(atomBytes.long)
                    if (atomSize < ATOM_PREAMBLE_SIZE * 2) {
                        throw IOException("invalid extended atom size")
                    }
                    if (atomSize - ATOM_PREAMBLE_SIZE * 2 > infile.size() - infile.position()) {
                        throw IOException("atom exceeds input size")
                    }
                    infile.position(infile.position() + atomSize - ATOM_PREAMBLE_SIZE * 2) // seek
                } else {
                    if (atomSize - ATOM_PREAMBLE_SIZE > infile.size() - infile.position()) {
                        throw IOException("atom exceeds input size")
                    }
                    infile.position(infile.position() + atomSize - ATOM_PREAMBLE_SIZE) // seek
                }
            }
        }
        if (moovBeforeMdat && mdatSeen) {
            return false
        }
        if (atomType != MOOV_ATOM) {
            throw IOException("last atom in file was not a moov atom")
        }

        // atomSize is uint64, but for moov uint32 should be stored.
        val moovAtomSize = uInt32ToInt(atomSize)
        lastOffset = infile.size() - moovAtomSize
        moovAtom = ByteBuffer.allocate(moovAtomSize).order(ByteOrder.BIG_ENDIAN)
        if (!readAndFill(infile, moovAtom, lastOffset)) {
            throw IOException("failed to read moov atom")
        }

        if (moovAtom.getInt(12) == CMOV_ATOM) {
            throw IOException("this utility does not support compressed moov atoms yet")
        }

        // crawl through the moov chunk in search of stco or co64 atoms
        while (moovAtom.remaining() >= 8) {
            val atomHead = moovAtom.position()
            atomType = moovAtom.getInt(atomHead + 4)
            if (!(atomType == STCO_ATOM || atomType == CO64_ATOM)) {
                moovAtom.position(moovAtom.position() + 1)
                continue
            }
            atomSize = uInt32ToLong(moovAtom.getInt(atomHead)) // uint32
            if (atomSize > moovAtom.remaining()) {
                throw IOException("bad atom size")
            }
            // skip size (4 bytes), type (4 bytes), version (1 byte) and flags (3 bytes)
            moovAtom.position(atomHead + 12)
            if (moovAtom.remaining() < 4) {
                throw IOException("malformed atom")
            }
            // uint32_t, but assuming moovAtomSize is in int32 range, so this will be in int32 range
            val offsetCount = uInt32ToInt(moovAtom.int)
            if (atomType == STCO_ATOM) {
                Log.i(TAG, "patching stco atom...")
                if (moovAtom.remaining() < offsetCount * 4) {
                    throw IOException("bad atom size/element count")
                }
                for (i in 0 until offsetCount) {
                    val currentOffset = moovAtom.getInt(moovAtom.position())
                    val newOffset =
                        currentOffset + moovAtomSize // calculate uint32 in int, bitwise addition

                    if (currentOffset < 0 && newOffset >= 0) {
                        throw IOException(
                            "This is bug in original qt-faststart.c: "
                                    + "stco atom should be extended to co64 atom as new offset value overflows uint32, "
                                    + "but is not implemented."
                        )
                    }
                    moovAtom.putInt(newOffset)
                }
            } else if (atomType == CO64_ATOM) {
                Log.wtf(TAG, "patching co64 atom...")
                if (moovAtom.remaining() < offsetCount * 8) {
                    throw IOException("bad atom size/element count")
                }
                for (i in 0 until offsetCount) {
                    val currentOffset = moovAtom.getLong(moovAtom.position())
                    moovAtom.putLong(currentOffset + moovAtomSize) // calculate uint64 in long, bitwise addition
                }
            }
        }
        infile.position(startOffset) // seek after ftyp atom
        if (ftypAtom != null) {
            // dump the same ftyp atom
            Log.i(TAG, "writing ftyp atom...")
            ftypAtom.rewind()
            outfile.write(ftypAtom)
        }

        // dump the new moov atom
        Log.i(TAG, "writing moov atom...")
        moovAtom.rewind()
        outfile.write(moovAtom)

        // copy the remainder of the infile, from offset 0 -> (lastOffset - startOffset) - 1
        Log.i(TAG, "copying rest of file...")
        infile.transferTo(startOffset, lastOffset - startOffset, outfile)
        return true
    }

    private fun readAndFill(infile: FileChannel, buffer: ByteBuffer): Boolean {
        buffer.clear()
        val size = infile.read(buffer)
        buffer.flip()
        return size == buffer.capacity()
    }

    private fun readAndFill(infile: FileChannel, buffer: ByteBuffer, position: Long): Boolean {
        buffer.clear()
        val size = infile.read(buffer, position)
        buffer.flip()
        return size == buffer.capacity()
    }
}
