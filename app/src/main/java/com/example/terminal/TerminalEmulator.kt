package com.example.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class TerminalTone {
    DEFAULT,
    INFO,
    SUCCESS,
    WARN,
    ERROR,
    MUTED,
}

data class TerminalSpan(
    val text: String,
    val tone: TerminalTone = TerminalTone.DEFAULT,
    val bold: Boolean = false,
)

data class TerminalLine(
    val id: Long,
    val timestamp: String,
    val source: String,
    val spans: List<TerminalSpan>,
) {
    val plainText: String
        get() = spans.joinToString(separator = "") { it.text }
}

/**
 * Lightweight ANSI/VT100-compatible terminal emulator buffer for displaying local
 * code-server process output, Termux checks, Gemini bootstrap logs, and crash logs.
 */
class TerminalEmulator(private val maxLines: Int = 400) {

    private val lineSeq = AtomicLong(1L)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<TerminalLine>>(emptyList())
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    fun info(source: String, message: String) {
        appendAnsi(source, "\u001B[36m$message\u001B[0m")
    }

    fun success(source: String, message: String) {
        appendAnsi(source, "\u001B[32m$message\u001B[0m")
    }

    fun warn(source: String, message: String) {
        appendAnsi(source, "\u001B[33m$message\u001B[0m")
    }

    fun error(source: String, message: String) {
        appendAnsi(source, "\u001B[31m$message\u001B[0m")
    }

    fun appendPlain(source: String, message: String) {
        appendAnsi(source, message)
    }

    fun appendAnsi(source: String, rawText: String) {
        val now = synchronized(timeFormat) { timeFormat.format(Date()) }
        val normalized = rawText.replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = normalized.split('\n')
        val newEntries = rawLines.mapNotNull { line ->
            if (line.isBlank() && rawLines.size > 1) {
                null
            } else {
                TerminalLine(
                    id = lineSeq.getAndIncrement(),
                    timestamp = now,
                    source = source,
                    spans = parseAnsiSpans(line),
                )
            }
        }
        if (newEntries.isEmpty()) return
        _lines.update { current ->
            val combined = current + newEntries
            if (combined.size > maxLines) {
                combined.takeLast(maxLines)
            } else {
                combined
            }
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun dumpPlainText(): String {
        return _lines.value.joinToString(separator = "\n") { line ->
            "[${line.timestamp}] [${line.source}] ${line.plainText}"
        }
    }

    companion object {
        private val ANSI_CSI_REGEX = Regex("""\u001B\[([0-9;]*)m""")

        fun parseAnsiSpans(input: String): List<TerminalSpan> {
            if (input.isEmpty()) return listOf(TerminalSpan(""))
            val result = mutableListOf<TerminalSpan>()
            var currentTone = TerminalTone.DEFAULT
            var currentBold = false
            var lastIdx = 0

            for (match in ANSI_CSI_REGEX.findAll(input)) {
                if (match.range.first > lastIdx) {
                    result.add(
                        TerminalSpan(
                            text = input.substring(lastIdx, match.range.first),
                            tone = currentTone,
                            bold = currentBold,
                        ),
                    )
                }
                val codes = match.groupValues[1]
                    .split(';')
                    .mapNotNull { it.toIntOrNull() }
                    .ifEmpty { listOf(0) }
                for (code in codes) {
                    when (code) {
                        0 -> {
                            currentTone = TerminalTone.DEFAULT
                            currentBold = false
                        }
                        1 -> currentBold = true
                        22 -> currentBold = false
                        31, 91 -> currentTone = TerminalTone.ERROR
                        32, 92 -> currentTone = TerminalTone.SUCCESS
                        33, 93 -> currentTone = TerminalTone.WARN
                        34, 36, 94, 96 -> currentTone = TerminalTone.INFO
                        90, 37 -> currentTone = TerminalTone.MUTED
                        39 -> currentTone = TerminalTone.DEFAULT
                    }
                }
                lastIdx = match.range.last + 1
            }

            if (lastIdx < input.length) {
                result.add(
                    TerminalSpan(
                        text = input.substring(lastIdx),
                        tone = currentTone,
                        bold = currentBold,
                    ),
                )
            }
            return if (result.isEmpty()) listOf(TerminalSpan(input)) else result
        }
    }
}
