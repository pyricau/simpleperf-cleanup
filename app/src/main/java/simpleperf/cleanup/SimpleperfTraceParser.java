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
package simpleperf.cleanup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import simpleperf.cleanup.proto.SimpleperfReport;

/**
 * Parses a trace file obtained using simpleperf.
 */
public class SimpleperfTraceParser {

  /**
   * Magic string that should appear in the very beginning of the simpleperf trace.
   */
  private static final String MAGIC = "SIMPLEPERF";

  /**
   * When the name of a function (symbol) is not found in the symbol table, the symbol_id field is set to -1.
   */
  private static final int INVALID_SYMBOL_ID = -1;

  /**
   * Directory containing files (.art, .odex, .so, .apk) related to app's. Each app's files are located in a subdirectory whose name starts
   * with the app ID. For instance, "com.google.sample.tunnel" app's directory could be something like
   * "/data/app/com.google.sample.tunnel-qpKipbnc0pE6uQs6gxAmbQ=="
   */
  private static final String DATA_APP_DIR = "/data/app";

  /**
   * The name of the event that should be used in simpleperf record command to support thread time.
   * <p>
   * Older versions of Android Studio used "cpu-cycles" which may have a better sampling cadence because it's
   * hardware based. However, CPU cycles are harder to correlate to wall clock time. Therefore, we support thread
   * time only if "cpu-clock" is used.
   */
  private static final String CPU_CLOCK_EVENT = "cpu-clock";

  /**
   * The message to surface to the user when dual clock isn't supported.
   */
  private static final String DUAL_CLOCK_DISABLED_MESSAGE =
      "This imported trace supports Wall Clock Time only.<p>" +
          "To view Thread Time, take a new recording using the latest version of Android Studio.";

  /**
   * Version of the trace file to be parsed. Should be obtained from the file itself.
   */
  private int myTraceVersion;

  /**
   * Maps a file id to its correspondent {@link SimpleperfReport.File}.
   */
  private final Map<Integer, SimpleperfReport.File> myFiles;

  /**
   * Maps a thread id to its corresponding {@link SimpleperfReport.Thread} object.
   */
  private final Map<Integer, SimpleperfReport.Thread> myThreads;

  /**
   * List of samples containing method trace data.
   */
  private final List<SimpleperfReport.Sample> mySamples;

  /**
   * Number of samples read from trace file.
   */
  private long mySampleCount;

  /**
   * Number of samples lost when recording the trace.
   */
  private long myLostSampleCount;

  /**
   * List of event types (e.g. cpu-cycles, sched:sched_switch) present in the trace.
   */
  private List<String> myEventTypes;

  private String myAppPackageName;

  /**
   * Prefix (up to the app name) of the /data/app subfolder corresponding to the app being profiled. For example:
   * "/data/app/com.google.sample.tunnel".
   */
  private String myAppDataFolderPrefix;

  private Set<String> myTags = new TreeSet<>(TAG_COMPARATOR);

  public SimpleperfTraceParser() {
    myFiles = new HashMap<>();
    mySamples = new ArrayList<>();
    myThreads = new HashMap<>();
  }

  /**
   * Given Unix-like path string (e.g. /system/my-path/file.so), returns the file name (e.g. file.so).
   */
  private static String fileNameFromPath(String path) {
    String[] splitPath = path.split("/");
    return splitPath[splitPath.length - 1];
  }

  private static boolean equals(SimpleperfReport.Sample.CallChainEntry c1, SimpleperfReport.Sample.CallChainEntry c2) {
    boolean isSameFileAndSymbolId = c1.getFileId() == c2.getFileId() && c1.getSymbolId() == c2.getSymbolId();
    if (!isSameFileAndSymbolId) {
      // Call chain entries need to be obtained from the same file and have the same symbol id in order to be equal.
      return false;
    }
    if (c1.getSymbolId() == -1) {
      // Symbol is invalid, fallback to vaddress
      return c1.getVaddrInFile() == c2.getVaddrInFile();
    }
    // Both file and symbol id match, and symbol is valid
    return true;
  }

  private static void log(String message) {
    System.out.println(message);
  }

  private static ByteBuffer byteBufferFromFile(File f, ByteOrder byteOrder) throws IOException {
    try (FileInputStream dataFile = new FileInputStream(f)) {
      MappedByteBuffer buffer = dataFile.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, f.length());
      buffer.order(byteOrder);
      return buffer;
    }
  }

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
   * <p>
   * Parsed data is stored in {@link #myFiles} and {@link #mySamples}.
   */
  public void parseTraceFile(File trace) throws IOException {
    ByteBuffer buffer = byteBufferFromFile(trace, ByteOrder.LITTLE_ENDIAN);
    verifyMagicNumber(buffer);
    parseVersionNumber(buffer);

    // Read the first record size
    int recordSize = buffer.getInt();

    // 0 is used to indicate the end of the trace
    while (recordSize != 0) {
      // The next recordSize bytes should represent the record
      byte[] recordBytes = new byte[recordSize];
      buffer.get(recordBytes);
      SimpleperfReport.Record record = SimpleperfReport.Record.parseFrom(recordBytes);

      switch (record.getRecordDataCase()) {
        case FILE:
          SimpleperfReport.File file = record.getFile();
          myFiles.put(file.getId(), file);
          break;
        case LOST:
          // Only one occurrence of LOST type is expected.
          SimpleperfReport.LostSituation situation = record.getLost();
          mySampleCount = situation.getSampleCount();
          myLostSampleCount = situation.getLostCount();
          break;
        case SAMPLE:
          SimpleperfReport.Sample sample = record.getSample();
          mySamples.add(sample);
          break;
        case THREAD:
          SimpleperfReport.Thread thread = record.getThread();
          myThreads.put(thread.getThreadId(), thread);
          break;
        case META_INFO:
          SimpleperfReport.MetaInfo info = record.getMetaInfo();
          myEventTypes = info.getEventTypeList();
          myAppPackageName = info.getAppPackageName();
          myAppDataFolderPrefix = String.format("%s/%s", DATA_APP_DIR, myAppPackageName);
          break;
        default:
         log("Unexpected record data type " + record.getRecordDataCase());
      }

      // read the next record size
      recordSize = buffer.getInt();
    }

    if (mySamples.size() != mySampleCount) {
      // TODO: create a trace file to test this exception is thrown when it should.
      throw new IllegalStateException("Samples count doesn't match the number of samples read.");
    }
  }

  /**
   * Parses the next 16-bit number of the given {@link ByteBuffer} as the trace version.
   */
  private void parseVersionNumber(ByteBuffer buffer) {
    myTraceVersion = buffer.getShort();
  }

  /**
   * Verifies the first 10 characters of the given {@link ByteBuffer} are {@code SIMPLEPERF}.
   * Throws an {@link IllegalStateException} otherwise.
   */
  private static void verifyMagicNumber(ByteBuffer buffer) {
    byte[] magic = new byte[MAGIC.length()];
    buffer.get(magic);
    if (!(new String(magic)).equals(MAGIC)) {
      throw new IllegalStateException("Simpleperf trace could not be parsed due to magic number mismatch.");
    }
  }

  // Order the tags coarsely depending on whether they're full paths or wild cards
  private static TagClass tagClass(String tag) {
    return tag.contains("*") ? TagClass.PREFIXED_PATH :
        tag.contains("[") ? TagClass.DESCRIPTION :
            TagClass.EXACT_PATH;
  }

  private enum TagClass {
    EXACT_PATH, DESCRIPTION, PREFIXED_PATH
  }

  static Comparator<String> TAG_COMPARATOR =
      Comparator.comparing(SimpleperfTraceParser::tagClass).thenComparing(String::compareTo);
}
