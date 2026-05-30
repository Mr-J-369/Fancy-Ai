package com.mrj.fancyai.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ToolCall(
    val name: String,
    val args: Map<String, String>
)

class AgentService {
    private val availableTools = mapOf(
        "web_search" to "Search the web for information",
        "read_file" to "Read contents of a file",
        "write_file" to "Write data to a file",
        "execute_command" to "Execute a shell command",
        "get_weather" to "Get current weather for a location",
        "get_time" to "Get current time"
    )

    fun getToolDefinitions(): String {
        return """
Available tools:
${availableTools.entries.joinToString("\n") { (name, desc) -> "- $name: $desc" }}
"""
    }

    fun parseToolCall(llmOutput: String): ToolCall? {
        return try {
            // Try to extract JSON from the output
            val jsonStart = llmOutput.indexOf('{')
            val jsonEnd = llmOutput.lastIndexOf('}')

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = llmOutput.substring(jsonStart, jsonEnd + 1)
                val json = JSONObject(jsonStr)

                val name = json.getString("tool")
                val args = json.getJSONObject("args").toMap()

                if (availableTools.containsKey(name)) {
                    ToolCall(name, args)
                } else {
                    Log.w("AgentService", "Unknown tool: $name")
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("AgentService", "Failed to parse tool call", e)
            null
        }
    }

    suspend fun executeTool(toolCall: ToolCall): String {
        return withContext(Dispatchers.IO) {
            try {
                when (toolCall.name) {
                    "web_search" -> executeWebSearch(toolCall.args["query"] ?: "")
                    "read_file" -> executeReadFile(toolCall.args["path"] ?: "")
                    "write_file" -> executeWriteFile(
                        toolCall.args["path"] ?: "",
                        toolCall.args["content"] ?: ""
                    )
                    "execute_command" -> executeCommand(toolCall.args["command"] ?: "")
                    "get_weather" -> executeGetWeather(toolCall.args["location"] ?: "")
                    "get_time" -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(System.currentTimeMillis())
                    else -> "Unknown tool: ${toolCall.name}"
                }
            } catch (e: Exception) {
                "Tool execution failed: ${e.message}"
            }
        }
    }

    private fun executeWebSearch(query: String): String {
        // TODO: Implement web search via API
        return "[Web search results for: $query - requires API integration]"
    }

    private fun executeReadFile(path: String): String {
        return try {
            val file = java.io.File(path)
            if (file.exists() && file.isFile) {
                file.readText()
            } else {
                "File not found: $path"
            }
        } catch (e: Exception) {
            "Failed to read file: ${e.message}"
        }
    }

    private fun executeWriteFile(path: String, content: String): String {
        return try {
            val file = java.io.File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            "File written successfully: $path"
        } catch (e: Exception) {
            "Failed to write file: ${e.message}"
        }
    }

    private fun executeCommand(command: String): String {
        // TODO: Implement safe command execution
        return "[Command execution restricted for security - would run: $command]"
    }

    private fun executeGetWeather(location: String): String {
        // TODO: Implement weather API integration
        return "[Weather for $location - requires API integration]"
    }
}

// Helper extension to convert JSONObject to Map
fun org.json.JSONObject.toMap(): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val keys = this.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = this.get(key).toString()
    }
    return result
}
