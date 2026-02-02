package com.roubao.autopilot.agent

import android.util.Log

/**
 * Executor Agent - 决定具体执行什么动作
 */
class Executor {

    companion object {
        val GUIDELINES = """
General:
- For any pop-up window, close it (e.g., by clicking 'Don't Allow' or 'Accept') before proceeding.
- For requests that are questions, remember to use the `answer` action to reply before finish!
- If the desired state is already achieved, you can just complete the task.

Action Related:
- Use `open_app` to open an app, do not use the app drawer.
- Consider using `swipe` to reveal additional content.
- If swiping doesn't change the page, it may have reached the bottom.

Text Related:
- To input text: first click the input box, make sure keyboard is visible, then use `type` action.
- To clear text: long press the backspace button in the keyboard.
        """.trimIndent()
    }

    /**
     * 生成执行 Prompt
     */
    fun getPrompt(infoPool: InfoPool): String = buildString {
        append("You are an agent who can operate an Android phone. ")
        append("Decide the next action based on the current state.\n\n")

        append("### User Request ###\n")
        append("${infoPool.instruction}\n\n")

        append("### Overall Plan ###\n")
        append("${infoPool.plan}\n\n")

        append("### Current Subgoal ###\n")
        val subgoals = infoPool.plan.split(Regex("(?<=\\d)\\. ")).take(3)
        append("${subgoals.joinToString(". ")}\n\n")

        append("### Progress Status ###\n")
        if (infoPool.progressStatus.isNotEmpty()) {
            append("${infoPool.progressStatus}\n\n")
        } else {
            append("No progress yet.\n\n")
        }

        append("### Guidelines ###\n")
        append("$GUIDELINES\n\n")

        append("---\n")
        append("Examine all information and decide on the next action.\n\n")

        append("#### Atomic Actions ####\n")
        append("- click(coordinate): Click at (x, y). Example: {\"action\": \"click\", \"coordinate\": [x, y]}\n")
        append("- double_tap(coordinate): Double tap at (x, y) for zoom or like. Example: {\"action\": \"double_tap\", \"coordinate\": [x, y]}\n")
        append("- long_press(coordinate): Long press at (x, y). Example: {\"action\": \"long_press\", \"coordinate\": [x, y]}\n")
        append("- type(text): Type text into activated input box. Example: {\"action\": \"type\", \"text\": \"hello\"}\n")
        append("- swipe(coordinate, coordinate2): Swipe from point1 to point2. Example: {\"action\": \"swipe\", \"coordinate\": [x1, y1], \"coordinate2\": [x2, y2]}\n")
        append("- system_button(button): Press Back/Home/Enter. Example: {\"action\": \"system_button\", \"button\": \"Back\"}\n")
        append("- open_app(text): Open an app by name. ALWAYS use this instead of looking for app icons on screen! Example: {\"action\": \"open_app\", \"text\": \"设置\"}\n")
        if (infoPool.installedApps.isNotEmpty()) {
            append("  Available apps: ${infoPool.installedApps}\n")
        }
        append("- wait(duration): Wait for page loading. Duration in seconds (1-10). Example: {\"action\": \"wait\", \"duration\": 3}\n")
        append("- take_over(message): Request user to manually complete login/captcha/verification. Example: {\"action\": \"take_over\", \"message\": \"请完成登录验证\"}\n")
        append("- answer(text): Answer user's question. Example: {\"action\": \"answer\", \"text\": \"The answer is...\"}\n")
        append("\n")

        append("#### Sensitive Operations ####\n")
        append("For payment, password, or privacy-related actions, add 'message' field to request user confirmation:\n")
        append("Example: {\"action\": \"click\", \"coordinate\": [500, 800], \"message\": \"确认支付 ¥100\"}\n")
        append("The user will see a confirmation dialog and can choose to confirm or cancel.\n")
        append("\n")

        append("### Latest Action History ###\n")
        if (infoPool.actionHistory.isNotEmpty()) {
            val numActions = minOf(5, infoPool.actionHistory.size)
            val latestActions = infoPool.actionHistory.takeLast(numActions)
            val latestSummaries = infoPool.summaryHistory.takeLast(numActions)
            val latestOutcomes = infoPool.actionOutcomes.takeLast(numActions)
            val latestErrors = infoPool.errorDescriptions.takeLast(numActions)

            latestActions.forEachIndexed { i, act ->
                val outcome = latestOutcomes.getOrNull(i) ?: "?"
                if (outcome == "A") {
                    append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Successful\n")
                } else {
                    append("- Action: $act | Description: ${latestSummaries.getOrNull(i)} | Outcome: Failed | Error: ${latestErrors.getOrNull(i)}\n")
                }
            }
        } else {
            append("No actions have been taken yet.\n")
        }
        append("\n")

        append("---\n")
        append("IMPORTANT:\n")
        append("1. Do NOT repeat previously failed actions. Try a different approach.\n")
        append("2. Prioritize the current subgoal.\n")
        append("3. Always analyze the screen BEFORE deciding on an action.\n\n")

        append("Provide your output in the following format:\n\n")
        append("### Thought ###\n")
        append("1. **Screen**: What app/page is currently shown? Is it the expected page for the current subgoal?\n")
        append("2. **Blocker**: Any popup, dialog, keyboard, or loading state that needs to be handled first?\n")
        append("3. **Target**: Is the target element visible? If not, should I scroll or navigate?\n")
        append("4. **Decision**: Based on the above analysis, what action should I take and why?\n\n")
        append("### Action ###\n")
        append("A valid JSON specifying the action. Example: {\"action\":\"click\", \"coordinate\": [500, 800]}\n\n")
        append("### Description ###\n")
        append("A brief description of the chosen action.\n")
    }

    /**
     * 解析执行响应
     * 支持多种格式:
     * 1. do() 格式 (do(action="...", app="..."))
     * 2. 标准格式 (### Thought / ### Action / ### Description)
     * 3. 纯 JSON 格式 ({"action": {"action": "click", ...}, "description": "..."})
     * 4. MAI-UI 格式 (<thinking>...</thinking> ...)
     */
    fun parseResponse(response: String): ExecutorResult {
        // 检测是否为 do() 格式
        if (response.contains("do(action=")) {
            return parseDoFormat(response)
        }

        // 检测是否为 MAI-UI 格式
        if (response.contains("<thinking>")) {
            return parseMAIUIResponse(response)
        }

        // 检测是否为纯 JSON 格式 (没有 ### 分隔符)
        if (!response.contains("###") && response.trim().startsWith("{")) {
            return parsePureJsonFormat(response)
        }

        // 标准格式解析
        // 首先规范化分隔符：支持 ### Action 和 ### Action ### 两种格式
        var normalizedResponse = response
            .replace("### Thought ###", "### Thought")
            .replace("### Action ###", "### Action")
            .replace("### Description ###", "### Description")

        val thought = normalizedResponse
            .substringAfter("### Thought", "")
            .substringBefore("### Action")
            .replace("###", "")
            .trim()

        var actionStr = normalizedResponse
            .substringAfter("### Action", "")
            .substringBefore("### Description")
            .replace("###", "")
            .replace("```json", "")
            .replace("```", "")
            .trim()
        Log.w("parseResponse", "parseResponse--actionStr:$actionStr")

        val description = normalizedResponse
            .substringAfter("### Description", "")
            .replace("###", "")
            .trim()

        Log.w("动作原始解析值：",actionStr)
        // 尝试解析 action
        var action = Action.fromJson(actionStr)

        // 如果标准格式解析失败，尝试在整个响应中查找 JSON
        if (action == null) {
            // 方法1: 查找 {"action" 开头的 JSON 对象（括号匹配）
            val actionStartIndex = normalizedResponse.indexOf("""{"action"""")
            if (actionStartIndex >= 0) {
                var braceCount = 0
                var actionEndIndex = actionStartIndex
                var found = false
                for (i in actionStartIndex until normalizedResponse.length) {
                    when (normalizedResponse[i]) {
                        '{' -> braceCount++
                        '}' -> {
                            braceCount--
                            if (braceCount == 0) {
                                actionEndIndex = i + 1
                                found = true
                                break
                            }
                        }
                    }
                }
                if (found) {
                    val extractedJson = normalizedResponse.substring(actionStartIndex, actionEndIndex)
                    action = Action.fromJson(extractedJson)
                    if (action != null) {
                        actionStr = extractedJson
                    }
                }
            }
        }

        // 如果仍然失败，使用简单的首尾括号匹配
        if (action == null) {
            val firstBraceIndex = normalizedResponse.indexOf('{')
            val lastBraceIndex = normalizedResponse.lastIndexOf('}')
            if (firstBraceIndex >= 0 && lastBraceIndex > firstBraceIndex) {
                val extractedJson = normalizedResponse.substring(firstBraceIndex, lastBraceIndex + 1)
                action = Action.fromJson(extractedJson)
                if (action != null) {
                    actionStr = extractedJson
                }
            }
        }

        return ExecutorResult(thought, action, actionStr, description)
    }

    /**
     * 解析 do() 格式响应
     * 格式: <思考内容> do(action="...", app="...")
     */
    private fun parseDoFormat(response: String): ExecutorResult {
        // 提取思考内容 (do() 之前的所有内容)
        val thought = response.substringBefore("do(").trim()

        // 提取 do() 的内容
        val doContent = response.substringAfter("do(").substringBeforeLast(")")

        // 解析参数并转换为 JSON
        val action = parseDoParams(doContent)

        val actionStr = action?.toJson() ?: ""
        val description = when (action?.type) {
            "click" -> "点击坐标 (${action.x}, ${action.y})"
            "long_press" -> "长按坐标 (${action.x}, ${action.y})"
            "double_tap" -> "双击坐标 (${action.x}, ${action.y})"
            "swipe" -> {
                if (action.direction != null) {
                    "向${action.direction}滑动"
                } else {
                    "从 (${action.x}, ${action.y}) 滑动到 (${action.x2}, ${action.y2})"
                }
            }
            "type" -> "输入文字: ${action.text}"
            "open_app", "openapp" -> "打开应用: ${action.text}"
            "system_button" -> "按${action.button}键"
            "wait" -> "等待 ${action.duration} 秒"
            "answer" -> "回答: ${action.text}"
            "take_over" -> "请求用户: ${action.text}"
            else -> action?.type ?: "未知动作"
        }

        return ExecutorResult(thought, action, actionStr, description)
    }

    /**
     * 解析 do() 的参数并转换为 Action
     * 格式: action="click", coordinate=[100, 200]
     */
    private fun parseDoParams(doContent: String): Action? {
        return try {
            // 构建一个 JSON 对象
            val json = org.json.JSONObject()

            // 正则匹配参数: key="value" 或 key=[...]
            val paramPattern = Regex("""(\w+)\s*=\s*"([^"]*)"|(\w+)\s*=\s*(\[[^\]]+\])""")
            paramPattern.findAll(doContent).forEach { match ->
                val key = match.groupValues[1].ifEmpty { match.groupValues[3] }
                val value = match.groupValues[2].ifEmpty { match.groupValues[4] }

                when (key) {
                    "action" -> {
                        // 处理 action 类型名称转换
                        val normalizedAction = when (value) {
                            "openapp" -> "open_app"
                            else -> value
                        }
                        json.put("action", normalizedAction)
                    }
                    "app", "text", "button", "direction", "status" -> {
                        // 如果是 openapp，将 app 参数映射为 text
                        val jsonKey = if (key == "app") "text" else key
                        json.put(jsonKey, value)
                    }
                    "coordinate", "element" -> {
                        // 解析数组格式 [100, 200]
                        val nums = value.removeSurrounding("[", "]").split(",").map { it.trim().toIntOrNull() ?: 0 }
                        if (nums.size >= 2) {
                            json.put("coordinate", org.json.JSONArray().apply {
                                put(nums[0])
                                put(nums[1])
                            })
                        }
                    }
                    "duration" -> {
                        json.put(key, value.toIntOrNull() ?: 3)
                    }
                }
            }

            Action.fromJson(json.toString())
        } catch (e: Exception) {
            Log.e("parseDoParams", "解析 do() 参数失败: ${e.message}")
            null
        }
    }

    /**
     * 解析纯 JSON 格式响应
     * 格式: {"action": {"action": "click", "coordinate": [x, y]}, "description": "..."}
     */
    private fun parsePureJsonFormat(response: String): ExecutorResult {
        val cleanJson = response.trim()
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val action = Action.fromJson(cleanJson)
        var description = ""
        var thought = ""

        try {
            val obj = org.json.JSONObject(cleanJson)
            // 尝试从外层提取 description
            description = obj.optString("description", "")
            Log.v("解析parsePureJsonFormat-thought",thought)
            // 如果没有 description，尝试从嵌套的 action 中提取
            if (description.isEmpty()) {
                val nestedAction = obj.optJSONObject("action")
                if (nestedAction != null) {
                    description = nestedAction.optString("description", "")
                }
            }
        } catch (e: Exception) {
            // 忽略解析错误，使用 action 生成描述
        }

        // 如果没有 description，从 action 生成
        if (description.isEmpty()) {
            description = when (action?.type) {
                "click" -> "点击坐标 (${action.x}, ${action.y})"
                "long_press" -> "长按坐标 (${action.x}, ${action.y})"
                "double_tap" -> "双击坐标 (${action.x}, ${action.y})"
                "swipe" -> {
                    if (action.direction != null) {
                        "向${action.direction}滑动"
                    } else {
                        "从 (${action.x}, ${action.y}) 滑动到 (${action.x2}, ${action.y2})"
                    }
                }
                "type" -> "输入文字: ${action.text}"
                "open_app" -> "打开应用: ${action.text}"
                "system_button" -> "按${action.button}键"
                "wait" -> "等待 ${action.duration} 秒"
                "answer" -> "回答: ${action.text}"
                "take_over" -> "请求用户: ${action.text}"
                else -> action?.type ?: "未知动作"
            }
        }

        return ExecutorResult(thought, action, cleanJson, description)
    }

    /**
     * 解析 MAI-UI 格式响应
     * 格式: <thinking>...</thinking> ...
     */
    private fun parseMAIUIResponse(response: String): ExecutorResult {
        val thought = Action.extractThinking(response)
        val action = Action.fromMAIUIFormat(response)
        Log.v("解析MAIUIResponse-thought",thought)
        Log.v("解析MAIUIResponse-action",""+ action)

        // 从 action 生成描述
        val description = when (action?.type) {
            "click" -> "点击坐标 (${action.x}, ${action.y})"
            "long_press" -> "长按坐标 (${action.x}, ${action.y})"
            "double_tap" -> "双击坐标 (${action.x}, ${action.y})"
            "swipe" -> {
                if (action.direction != null) {
                    "向${action.direction}滑动"
                } else {
                    "从 (${action.x}, ${action.y}) 滑动到 (${action.x2}, ${action.y2})"
                }
            }
            "type" -> "输入文字: ${action.text}"
            "open_app" -> "打开应用: ${action.text}"
            "system_button" -> "按${action.button}键"
            "wait" -> "等待"
            "terminate" -> "任务${if (action.status == "success") "完成" else "失败"}"
            "answer" -> "回答: ${action.text?.take(30)}"
            "take_over" -> "请求用户: ${action.text}"
            else -> action?.type ?: "未知动作"
        }

        val actionStr = action?.toJson() ?: ""
        return ExecutorResult(thought, action, actionStr, description)
    }
}

data class ExecutorResult(
    val thought: String,
    val action: Action?,
    val actionStr: String,
    val description: String
)
