package com.mrj.fancyai.terminal

import java.io.OutputStream

/**
 * Handle to an active running command execution, allowing stdin input and interactive control.
 */
class ActiveCommandHandle internal constructor(
    private val outputStream: OutputStream?,
) {
    fun sendInput(text: String) {
        runCatching {
            outputStream?.apply {
                write(text.toByteArray(Charsets.UTF_8))
                flush()
            }
        }
    }

    fun sendLine(line: String) {
        sendInput("$line\r")
    }

    fun sendInterrupt() {
        // ASCII ETX (Ctrl+C)
        sendInput("\u0003")
    }

    fun sendEof() {
        // ASCII EOT (Ctrl+D)
        sendInput("\u0004")
    }

    fun sendUpArrow() {
        sendInput("\u001b[A")
    }

    fun sendDownArrow() {
        sendInput("\u001b[B")
    }
}
