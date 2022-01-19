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

import simpleperf.cleanup.SimpleperfTraceParser.TagClass.DESCRIPTION
import simpleperf.cleanup.SimpleperfTraceParser.TagClass.EXACT_PATH
import simpleperf.cleanup.SimpleperfTraceParser.TagClass.PREFIXED_PATH
import simpleperf.cleanup.proto.SimpleperfReport
import simpleperf.cleanup.proto.SimpleperfReport.File
import simpleperf.cleanup.proto.SimpleperfReport.Record
import simpleperf.cleanup.proto.SimpleperfReport.Record.RecordDataCase.FILE
import simpleperf.cleanup.proto.SimpleperfReport.Record.RecordDataCase.LOST
import simpleperf.cleanup.proto.SimpleperfReport.Record.RecordDataCase.META_INFO
import simpleperf.cleanup.proto.SimpleperfReport.Record.RecordDataCase.SAMPLE
import simpleperf.cleanup.proto.SimpleperfReport.Record.RecordDataCase.THREAD
import simpleperf.cleanup.proto.SimpleperfReport.Sample
import simpleperf.cleanup.proto.SimpleperfReport.Sample.CallChainEntry
import simpleperf.cleanup.proto.SimpleperfReport.Thread
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel.MapMode
import java.util.TreeSet

/**
 * Parses a trace file obtained using simpleperf.
 */
class SimpleperfTraceParser {
  /**
   * Version of the trace file to be parsed. Should be obtained from the file itself.
   */
  private var myTraceVersion = 0

  /**
   * Maps a file id to its correspondent [SimpleperfReport.File].
   */
  private val myFiles: MutableMap<Int, File>

  /**
   * Maps a thread id to its corresponding [SimpleperfReport.Thread] object.
   */
  private val myThreads: MutableMap<Int, Thread>

  /**
   * List of samples containing method trace data.
   */
  private val mySamples: MutableList<Sample>

  /**
   * Number of samples read from trace file.
   */
  private var mySampleCount: Long = 0

  /**
   * Number of samples lost when recording the trace.
   */
  private var myLostSampleCount: Long = 0

  /**
   * List of event types (e.g. cpu-cycles, sched:sched_switch) present in the trace.
   */
  private var myEventTypes: List<String>? = null
  private var myAppPackageName: String? = null

  /**
   * Prefix (up to the app name) of the /data/app subfolder corresponding to the app being profiled. For example:
   * "/data/app/com.google.sample.tunnel".
   */
  private var myAppDataFolderPrefix: String? = null
  private val myTags: Set<String> = TreeSet(TAG_COMPARATOR)

  /**
   * Parses the trace file, which should have the following format:
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
   *
   *
   * Parsed data is stored in [.myFiles] and [.mySamples].
   */
  @Throws(IOException::class) fun parseTraceFile(trace: java.io.File) {
    val buffer = byteBufferFromFile(trace, ByteOrder.LITTLE_ENDIAN)
    verifyMagicNumber(buffer)
    parseVersionNumber(buffer)

    // Read the first record size
    var recordSize = buffer.int

    // 0 is used to indicate the end of the trace
    while (recordSize != 0) {
      // The next recordSize bytes should represent the record
      val recordBytes = ByteArray(recordSize)
      buffer[recordBytes]
      val record = Record.parseFrom(recordBytes)
      when (record.recordDataCase) {
        FILE -> {
          val file = record.file
          myFiles[file.id] = file
        }
        LOST -> {
          // Only one occurrence of LOST type is expected.
          val situation = record.lost
          mySampleCount = situation.sampleCount
          myLostSampleCount = situation.lostCount
        }
        SAMPLE -> {
          val sample = record.sample
          mySamples.add(sample)
        }
        THREAD -> {
          val thread = record.thread
          myThreads[thread.threadId] = thread
        }
        META_INFO -> {
          val info = record.metaInfo
          myEventTypes = info.eventTypeList
          myAppPackageName = info.appPackageName
          myAppDataFolderPrefix = String.format("%s/%s", DATA_APP_DIR, myAppPackageName)
        }
        else -> log("Unexpected record data type " + record.recordDataCase)
      }

      // read the next record size
      recordSize = buffer.int
    }
    check(mySamples.size.toLong() == mySampleCount) {
      // TODO: create a trace file to test this exception is thrown when it should.
      "Samples count doesn't match the number of samples read."
    }
  }

  /**
   * Parses the next 16-bit number of the given [ByteBuffer] as the trace version.
   */
  private fun parseVersionNumber(buffer: ByteBuffer) {
    myTraceVersion = buffer.short.toInt()
  }

  private enum class TagClass {
    EXACT_PATH,
    DESCRIPTION,
    PREFIXED_PATH
  }

  companion object {
    /**
     * Magic string that should appear in the very beginning of the simpleperf trace.
     */
    private const val MAGIC = "SIMPLEPERF"

    /**
     * When the name of a function (symbol) is not found in the symbol table, the symbol_id field is set to -1.
     */
    private const val INVALID_SYMBOL_ID = -1

    /**
     * Directory containing files (.art, .odex, .so, .apk) related to app's. Each app's files are located in a subdirectory whose name starts
     * with the app ID. For instance, "com.google.sample.tunnel" app's directory could be something like
     * "/data/app/com.google.sample.tunnel-qpKipbnc0pE6uQs6gxAmbQ=="
     */
    private const val DATA_APP_DIR = "/data/app"

    /**
     * The name of the event that should be used in simpleperf record command to support thread time.
     *
     *
     * Older versions of Android Studio used "cpu-cycles" which may have a better sampling cadence because it's
     * hardware based. However, CPU cycles are harder to correlate to wall clock time. Therefore, we support thread
     * time only if "cpu-clock" is used.
     */
    private const val CPU_CLOCK_EVENT = "cpu-clock"

    /**
     * The message to surface to the user when dual clock isn't supported.
     */
    private const val DUAL_CLOCK_DISABLED_MESSAGE =
      "This imported trace supports Wall Clock Time only.<p>" +
        "To view Thread Time, take a new recording using the latest version of Android Studio."

    /**
     * Given Unix-like path string (e.g. /system/my-path/file.so), returns the file name (e.g. file.so).
     */
    private fun fileNameFromPath(path: String): String {
      val splitPath = path.split("/".toRegex()).toTypedArray()
      return splitPath[splitPath.size - 1]
    }

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

    private fun log(message: String) {
      println(message)
    }

    @Throws(IOException::class) private fun byteBufferFromFile(
      f: java.io.File,
      byteOrder: ByteOrder
    ): ByteBuffer {
      FileInputStream(f).use { dataFile ->
        val buffer =
          dataFile.channel.map(MapMode.READ_ONLY, 0, f.length())
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

    // Order the tags coarsely depending on whether they're full paths or wild cards
    private fun tagClass(tag: String): TagClass {
      return if (tag.contains("*")) PREFIXED_PATH else if (tag.contains("[")) DESCRIPTION else EXACT_PATH
    }

    var TAG_COMPARATOR = Comparator.comparing { tag: String ->
      tagClass(
        tag
      )
    }.thenComparing { obj: String, anotherString: String? ->
      obj.compareTo(
        anotherString!!
      )
    }
  }

  init {
    myFiles = HashMap()
    mySamples = ArrayList()
    myThreads = HashMap()
  }
}