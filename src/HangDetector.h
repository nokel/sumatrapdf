/* Copyright 2026 the SumatraPDF project authors (see AUTHORS file).
   License: GPLv3 */

void StartUiHangDetector();
void StopUiHangDetector();
bool IsUiHangDetectorRunning();

// chunk 34: dumps the callstack of every thread of this process to a
// single temp string. Used by the regression test to identify a hot
// thread that is pinning one core without responding to the UI
// hang detector (the hot thread keeps doing work, so the UI thread
// stays responsive and the watchdog never fires). All non-UI
// threads are suspended while their stacks are captured.
TempStr DumpAllThreadStacksTemp();
