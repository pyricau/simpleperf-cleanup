/*
File imported from
https://cs.android.com/android-studio/platform/tools/adt/idea/+/mirror-goog-studio-main:profilers/src/com/android/tools/profilers/cpu/simpleperf/SimpleperfTraceParser.java;l=54;drc=e79366d3c93f6715f53150de5b9cdce43c3e8ba5
 */

/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package simpleperf.cleanup

import com.google.common.io.LittleEndianDataOutputStream
import simpleperf.cleanup.proto.SimpleperfReport.Record
import simpleperf.cleanup.proto.SimpleperfReport.Record.RecordDataCase.SAMPLE
import simpleperf.cleanup.proto.SimpleperfReport.Record.RecordDataCase.THREAD
import simpleperf.cleanup.proto.SimpleperfReport.Sample
import simpleperf.cleanup.proto.SimpleperfReport.Sample.CallChainEntry
import simpleperf.cleanup.proto.SimpleperfReport.Thread
import java.io.DataOutput
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel.MapMode

/**
 * Parses a trace file obtained using simpleperf, which should have the following format:
 * char magic[10] = "SIMPLEPERF";
 * LittleEndian16(version) = 1;
 * LittleEndian32(record_size_0)
 * SimpleperfReport.Record (having record_size_0 bytes)
 * LittleEndian32(record_size_1)
 * message Record(record_1) (having record_size_1 bytes)
 * ...
 * LittleEndian32(record_size_N)
 * message Record(record_N) (having record_size_N bytes)
 * LittleEndian32(0)
 */
object SimpleperfTraceParser {

  private class MainThreadMetadata(
    val mainThreadId: Int,
    val callStackRoot: CallChainEntry
  )

  fun fixDetachedMainSamples(source: File, destination: File) {
    println("Copying $source to $destination")
    val mainThreadMetadata = readMainThreadMetadata(source)

    if (destination.exists()) {
      println("Deleting pre existing $destination")
      destination.delete()
    }
    copyFixingStackRoot(source, destination, mainThreadMetadata)
  }

  private fun readMainThreadMetadata(trace: File): MainThreadMetadata {
    // Maps a thread id to its corresponding [SimpleperfReport.Thread] object.
    val threadsById = mutableMapOf<Int, Thread>()

    // List of samples containing method trace data.
    val samples = mutableListOf<Sample>()

    val buffer = trace.readAllIntoByteBuffer(ByteOrder.LITTLE_ENDIAN)
    verifyMagicNumber(buffer)
    // version number
    buffer.short

    // Read the first record size
    var recordSize = buffer.int

    // 0 is used to indicate the end of the trace
    while (recordSize != 0) {
      // The next recordSize bytes should represent the record
      val recordBytes = ByteArray(recordSize)
      buffer[recordBytes]
      val record = Record.parseFrom(recordBytes)
      when (record.recordDataCase) {
        SAMPLE -> {
          val sample = record.sample
          samples.add(sample)
        }
        THREAD -> {
          val thread = record.thread
          threadsById[thread.threadId] = thread
        }
      }
      // read the next record size
      recordSize = buffer.int
    }

    val mainThreadId = threadsById.values.single { it.threadId == it.processId }.threadId
    val firstMainThreadSample = samples.first { it.threadId == mainThreadId }
    val callStackRoot = firstMainThreadSample.callchainList.last()
    return MainThreadMetadata(mainThreadId, callStackRoot)
  }

  private fun copyFixingStackRoot(
    source: File,
    destination: File,
    mainThreadMetadata: MainThreadMetadata
  ) {
    val mainThreadId = mainThreadMetadata.mainThreadId
    val callStackRoot = mainThreadMetadata.callStackRoot

    val buffer = source.readAllIntoByteBuffer(ByteOrder.LITTLE_ENDIAN)

    var mainThreadSampleCount = 0
    var brokenMainThreadSampleCount = 0

    LittleEndianDataOutputStream(FileOutputStream(destination)).use { output ->
      output.write(ByteArray(MAGIC.length).apply { buffer[this] })

      // version number
      output.writeShort(buffer.short.toInt())

      // Read the first record size
      var recordSize = buffer.int

      // 0 is used to indicate the end of the trace
      lateinit var lastValidSample: Sample //
      val brokenRecords = mutableListOf<Record>()
      while (recordSize != 0) {
        // The next recordSize bytes should represent the record
        val recordBytes = ByteArray(recordSize)
        buffer[recordBytes]
        val record = Record.parseFrom(recordBytes)
        when (record.recordDataCase) {
          SAMPLE -> {
            val sample = record.sample
            if (sample.threadId == mainThreadId) {
              mainThreadSampleCount++
              if (equals(sample.callchainList.last(), callStackRoot)) {
                if (brokenRecords.isNotEmpty()) {
                  // Reversed so that root is at index 0
                  val lastValidCallchain = lastValidSample.callchainList.reversed()
                  val nextValidCallchain = sample.callchainList.reversed()
                  // Find the node where the current call chain diverge from the previous one
                  var divergenceIndex = 0
                  while (divergenceIndex < nextValidCallchain.size && divergenceIndex < lastValidCallchain.size &&
                    equals(lastValidCallchain[divergenceIndex], nextValidCallchain[divergenceIndex])
                  ) {
                    divergenceIndex++
                  }
                  val sharedCallChain = nextValidCallchain.subList(0, divergenceIndex).reversed()

                  for (brokenRecord in brokenRecords) {
                    output.writeFixedRecord(brokenRecord, sharedCallChain)
                  }
                  brokenRecords.clear()
                }
                lastValidSample = sample
                output.writeInt(recordSize)
                output.write(recordBytes)
              } else {
                brokenRecords += record
                brokenMainThreadSampleCount++
              }
            } else {
              output.writeInt(recordSize)
              output.write(recordBytes)
            }
          }
          else -> {
            output.writeInt(recordSize)
            output.write(recordBytes)
          }
        }
        // read the next record size
        recordSize = buffer.int
      }

      // Ensure any trailing broken records gets written, with the last valid callchain as shared
      // prefix.
      if (brokenRecords.isNotEmpty()) {
        for (brokenRecord in brokenRecords) {
          output.writeFixedRecord(brokenRecord, lastValidSample.callchainList)
        }
      }
      output.writeInt(0)
    }
    println("Done fixing trace, fixed $brokenMainThreadSampleCount / $mainThreadSampleCount main thread samples")
  }

  private fun DataOutput.writeFixedRecord(
    brokenRecord: Record,
    sharedCallChain: List<CallChainEntry>
  ) {
    val fixedRecord = brokenRecord.toBuilder().run {
      sampleBuilder.addAllCallchain(sharedCallChain)
      build()
    }
    val fixedRecordBytes = fixedRecord.toByteArray()
    writeInt(fixedRecordBytes.size)
    write(fixedRecordBytes)
  }

  /**
   * Magic string that should appear in the very beginning of the simpleperf trace.
   */
  private const val MAGIC = "SIMPLEPERF"

  private fun equals(c1: CallChainEntry, c2: CallChainEntry): Boolean {
    val isSameFileAndSymbolId = c1.fileId == c2.fileId && c1.symbolId == c2.symbolId
    if (!isSameFileAndSymbolId) {
      // Call chain entries need to be obtained from the same file and have the same symbol id in order to be equal.
      return false
    }
    return if (c1.symbolId == -1) {
      // Symbol is invalid, fallback to vaddress
      c1.vaddrInFile == c2.vaddrInFile
    } else true
    // Both file and symbol id match, and symbol is valid
  }

  private fun File.readAllIntoByteBuffer(
    byteOrder: ByteOrder
  ): ByteBuffer {
    FileInputStream(this).use { dataFile ->
      val buffer =
        dataFile.channel.map(MapMode.READ_ONLY, 0, length())
      buffer.order(byteOrder)
      return buffer
    }
  }

  /**
   * Verifies the first 10 characters of the given [ByteBuffer] are `SIMPLEPERF`.
   * Throws an [IllegalStateException] otherwise.
   */
  private fun verifyMagicNumber(buffer: ByteBuffer) {
    val magic = ByteArray(MAGIC.length)
    buffer[magic]
    check(String(magic) == MAGIC) { "Simpleperf trace could not be parsed due to magic number mismatch." }
  }
}