package com.cinator.app.engines.nuvio.runtime;

/** Java ABI twin used only by host CTest; production compiles NativeQuickJs.kt. */
final class NativeQuickJs {
    static final NativeQuickJs INSTANCE = new NativeQuickJs();
    static { System.loadLibrary("cinator_quickjs"); }
    native byte[] evaluate(byte[] source, byte[] document, byte[] uuid, int height, int timeoutMs);
    native String version();
}
