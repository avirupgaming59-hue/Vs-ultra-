package com.example.runtime

enum class CommandSafetyDecision {
    ALLOWED,
    REQUIRES_CONFIRMATION,
    BLOCKED,
}

data class CommandValidationResult(
    val decision: CommandSafetyDecision,
    val normalizedCommand: String,
    val reason: String = "",
)

/**
 * Validates shell and AI-generated setup commands before execution in the built-in User Terminal.
 * Blocks destructive system-wide commands and requires explicit user confirmation for high-impact actions.
 */
object CommandSafetyValidator {

    private val blockedPatterns: List<Pair<Regex, String>> = listOf(
        Regex(""":[(]\s*[)][\s\S]*[:][|][:]""") to "Blocked fork-bomb shell pattern.",
        Regex("""\brm\s+-[a-zA-Z]*[rf][a-zA-Z]*\s+(/($|\s)|/system\b|/vendor\b|/proc\b|/dev\b|/data/data\b)""") to
            "Blocked recursive delete targeting Android system or private app root.",
        Regex("""\b(mkfs|fdisk|parted)\b""") to "Blocked low-level filesystem formatting command.",
        Regex("""\bdd\s+.*\bof=/dev/""") to "Blocked direct block-device write via dd.",
        Regex("""\bchmod\s+(-R\s+)?0?777\s+/($|\s|/system|/data)""") to
            "Blocked unsafe recursive 777 permission change on system path.",
        Regex("""\u0000""") to "Blocked null-byte injection in command.",
    )

    private val confirmationPatterns: List<Pair<Regex, String>> = listOf(
        Regex("""\brm\s+-[a-zA-Z]*[rR]""") to "Recursive delete command will remove files permanently inside the workspace.",
        Regex("""\b(curl|wget)\b[^|]*\|\s*(sh|bash|ash)\b""") to "Piping remote script content directly into a shell interpreter requires explicit approval.",
        Regex("""\b(pkg|apt|npm|pnpm|yarn|pip|pip3)\s+(install|upgrade|update|uninstall|remove)\b""") to
            "Package installation or modification requires confirmation before execution.",
        Regex("""\bchmod\b|\bchown\b""") to "Modifying file permissions or ownership requires confirmation.",
        Regex("""\bkillall\b|\bkill\s+-9\b""") to "Force-terminating processes requires confirmation.",
    )

    fun validate(rawCommand: String): CommandValidationResult {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) {
            return CommandValidationResult(
                decision = CommandSafetyDecision.BLOCKED,
                normalizedCommand = "",
                reason = "Command cannot be empty.",
            )
        }
        if (cmd.length > 4096) {
            return CommandValidationResult(
                decision = CommandSafetyDecision.BLOCKED,
                normalizedCommand = cmd.take(200),
                reason = "Command exceeds 4096-character safety limit.",
            )
        }
        for ((pattern, reason) in blockedPatterns) {
            if (pattern.containsMatchIn(cmd)) {
                return CommandValidationResult(
                    decision = CommandSafetyDecision.BLOCKED,
                    normalizedCommand = cmd,
                    reason = reason,
                )
            }
        }
        for ((pattern, reason) in confirmationPatterns) {
            if (pattern.containsMatchIn(cmd)) {
                return CommandValidationResult(
                    decision = CommandSafetyDecision.REQUIRES_CONFIRMATION,
                    normalizedCommand = cmd,
                    reason = reason,
                )
            }
        }
        return CommandValidationResult(
            decision = CommandSafetyDecision.ALLOWED,
            normalizedCommand = cmd,
        )
    }
}
