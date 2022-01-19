/*
Copied from https://cs.android.com/android-studio/platform/tools/adt/idea/+/mirror-goog-studio-main:profilers/src/com/android/tools/profilers/cpu/CpuThreadInfo.java;l=21;drc=5b4951f95f0b348f4efb4662957ec19047587cd4
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

import org.jetbrains.annotations.NotNull;

public class CpuThreadInfo {
  /**
   * The platform RenderThread is hard coded to have this name.
   */
  public static final String RENDER_THREAD_NAME = "RenderThread";

  public static final String GPU_THREAD_NAME = "GPU completion";

  /** Thread id */
  private final int myId;

  /** Thread name */
  private final String myName;

  /**
   * Whether this {@link CpuThreadInfo} contains information of a main thread.
   */
  private final boolean myIsMainThread;

  public CpuThreadInfo(int threadId, @NotNull String name, boolean isMainThread) {
    myId = threadId;
    myName = name;
    myIsMainThread = isMainThread;
  }

  public CpuThreadInfo(int threadId, @NotNull String name) {
    this(threadId, name, false);
  }

  public int getId() {
    return myId;
  }

  @NotNull
  public String getName() {
    return myName;
  }

  public boolean isMainThread() {
    return myIsMainThread;
  }

  public boolean isRenderThread() {
    return getName().equals(RENDER_THREAD_NAME);
  }

  public boolean isGpuThread() {
    return getName().equals(GPU_THREAD_NAME);
  }

  public boolean isRenderingRelatedThread() {
    return isMainThread() || isRenderThread() || isGpuThread();
  }

  @Override
  public String toString() {
    return getName() + ":" + getId();
  }
}
