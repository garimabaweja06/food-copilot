package com.example.foodcopilot

import android.view.accessibility.AccessibilityNodeInfo

data class Element(
    val id: Int,
    val label: String,
    val type: String,
    val node: AccessibilityNodeInfo
)

data class ParsedScreen(
    val elements: List<Element>,
    val isSensitive: Boolean
) {
    fun toPrompt(): String =
        elements.joinToString("\n") { "[${it.id}] ${it.type}: ${it.label}" }
}

class ScreenParser {

    private val sensitiveKeywords = listOf(
        "otp", "cvv", "card number", "upi pin", "enter pin",
        "net banking", "expiry", "add card", "debit card", "credit card",
        "billing", "payment methods", "saved cards", "saved payment",
        "manage payments", "bank account", "upi id", "card ending",
        "wallet balance"
    )

    private val pin = Regex("\\b\\d{6}\\b")
    private val addressHint = Regex(
        "(?i),\\s*(india|haryana|punjab|delhi|uttar pradesh|rajasthan|himachal pradesh|chandigarh|maharashtra|karnataka|tamil nadu|gujarat|west bengal|telangana|kerala)\\b|\\b\\d{6}\\b|deliver(ing)? to|delivery location"
    )

    private fun redact(s: String): String =
        s.replace(Regex("\\+?\\d[\\d\\s-]{8,}\\d"), "[number]")
            .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "[email]")

    fun parse(root: AccessibilityNodeInfo?): ParsedScreen {
        if (root == null) return ParsedScreen(emptyList(), false)

        val out = mutableListOf<Element>()
        var sensitive = false
        var nextId = 1

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || !node.isVisibleToUser) return

            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()

            if (text.isNotEmpty() && sensitiveKeywords.any { text.lowercase().contains(it) }) {
                sensitive = true
            }
            if (node.isPassword) sensitive = true

            val type = when {
                node.isEditable -> "input"
                node.isClickable -> "button"
                node.isScrollable -> "list"
                else -> "text"
            }
            val useful = text.isNotEmpty() || node.isClickable || node.isEditable
            if (useful && text.length < 200) {
                out.add(Element(nextId++, redact(text).ifEmpty { "unlabeled $type" }, type, node))
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }

        walk(root, 0)

        val cleaned = out.toMutableList()
        for (i in cleaned.indices) {
            val l = cleaned[i].label
            if (addressHint.containsMatchIn(l) || l.contains("address", ignoreCase = true)) {
                cleaned[i] = cleaned[i].copy(label = "[address hidden]")
                if (i > 0 && cleaned[i - 1].label.length < 20) {
                    cleaned[i - 1] = cleaned[i - 1].copy(label = "[address hidden]")
                }
            }
        }
        return ParsedScreen(cleaned.take(60), sensitive)
    }
}