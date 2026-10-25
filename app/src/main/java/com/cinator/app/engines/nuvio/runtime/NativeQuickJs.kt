package com.cinator.app.engines.nuvio.runtime

/** Service-only engine entry point. Source must be the compiled, reviewed constant. */
internal object NativeQuickJs {
    init { System.loadLibrary("cinator_quickjs") }

    // Normal UTF-8 byte arrays deliberately avoid JNI's Modified UTF-8 strings.
    external fun evaluate(source: ByteArray, document: ByteArray, uuid: ByteArray, height: Int, timeoutMs: Int): ByteArray
    external fun version(): String
}
