package com.example.data.model

import androidx.compose.ui.graphics.Color

enum class CodeLanguage(
    val displayName: String,
    val badgeLabel: String,
    val primaryExtension: String,
    val extensions: Set<String>,
    val mimeType: String,
    val badgeColor: Color,
    val starterTemplate: String,
) {
    PYTHON(
        displayName = "Python",
        badgeLabel = "PY",
        primaryExtension = "py",
        extensions = setOf("py", "pyw", "ipynb"),
        mimeType = "text/x-python",
        badgeColor = Color(0xFF3572A5),
        starterTemplate = "def main():\n    print(\"Hello from phone workspace!\")\n\nif __name__ == \"__main__\":\n    main()\n",
    ),
    TYPESCRIPT(
        displayName = "TypeScript",
        badgeLabel = "TS",
        primaryExtension = "ts",
        extensions = setOf("ts", "tsx", "mts"),
        mimeType = "text/typescript",
        badgeColor = Color(0xFF3178C6),
        starterTemplate = "export function greet(name: string): string {\n  return `Hello, \${name}`;\n}\n\nconsole.log(greet(\"VS Code\"));\n",
    ),
    JAVASCRIPT(
        displayName = "JavaScript",
        badgeLabel = "JS",
        primaryExtension = "js",
        extensions = setOf("js", "jsx", "mjs", "cjs"),
        mimeType = "application/javascript",
        badgeColor = Color(0xFFD9B426),
        starterTemplate = "export function main() {\n  console.log(\"Ready for code-server\");\n}\n\nmain();\n",
    ),
    KOTLIN(
        displayName = "Kotlin",
        badgeLabel = "KT",
        primaryExtension = "kt",
        extensions = setOf("kt", "kts"),
        mimeType = "text/x-kotlin",
        badgeColor = Color(0xFFA97BFF),
        starterTemplate = "fun main() {\n    println(\"Kotlin file created on phone\")\n}\n",
    ),
    JAVA(
        displayName = "Java",
        badgeLabel = "JAVA",
        primaryExtension = "java",
        extensions = setOf("java"),
        mimeType = "text/x-java-source",
        badgeColor = Color(0xFFB07219),
        starterTemplate = "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello from phone\");\n    }\n}\n",
    ),
    HTML(
        displayName = "HTML",
        badgeLabel = "HTML",
        primaryExtension = "html",
        extensions = setOf("html", "htm"),
        mimeType = "text/html",
        badgeColor = Color(0xFFE34C26),
        starterTemplate = "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"UTF-8\" />\n  <title>Phone Page</title>\n</head>\n<body>\n  <h1>Hello VS Code</h1>\n</body>\n</html>\n",
    ),
    CSS(
        displayName = "CSS",
        badgeLabel = "CSS",
        primaryExtension = "css",
        extensions = setOf("css", "scss", "less"),
        mimeType = "text/css",
        badgeColor = Color(0xFF563D7C),
        starterTemplate = ":root {\n  --accent: #007acc;\n}\n\nbody {\n  font-family: system-ui, sans-serif;\n}\n",
    ),
    JSON(
        displayName = "JSON",
        badgeLabel = "JSON",
        primaryExtension = "json",
        extensions = setOf("json", "jsonc"),
        mimeType = "application/json",
        badgeColor = Color(0xFF4EC9B0),
        starterTemplate = "{\n  \"name\": \"phone-snippet\",\n  \"version\": \"1.0.0\"\n}\n",
    ),
    CPP(
        displayName = "C / C++",
        badgeLabel = "C++",
        primaryExtension = "cpp",
        extensions = setOf("c", "h", "cpp", "hpp", "cc"),
        mimeType = "text/x-c++src",
        badgeColor = Color(0xFFF34B7D),
        starterTemplate = "#include <iostream>\n\nint main() {\n    std::cout << \"Hello from phone\" << std::endl;\n    return 0;\n}\n",
    ),
    SHELL(
        displayName = "Shell",
        badgeLabel = "SH",
        primaryExtension = "sh",
        extensions = setOf("sh", "bash", "zsh"),
        mimeType = "application/x-sh",
        badgeColor = Color(0xFF89E051),
        starterTemplate = "#!/usr/bin/env bash\nset -euo pipefail\necho \"Running script from phone\"\n",
    ),
    MARKDOWN(
        displayName = "Markdown",
        badgeLabel = "MD",
        primaryExtension = "md",
        extensions = setOf("md", "mdx", "txt"),
        mimeType = "text/markdown",
        badgeColor = Color(0xFF083FA1),
        starterTemplate = "# Notes\n\nCreated on phone and ready to push to `code-server`.\n",
    ),
    OTHER(
        displayName = "Other",
        badgeLabel = "FILE",
        primaryExtension = "txt",
        extensions = emptySet(),
        mimeType = "text/plain",
        badgeColor = Color(0xFF6E7681),
        starterTemplate = "",
    );

    companion object {
        fun fromFileName(fileName: String): CodeLanguage {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return OTHER
            return entries.firstOrNull { ext in it.extensions } ?: OTHER
        }
    }
}
