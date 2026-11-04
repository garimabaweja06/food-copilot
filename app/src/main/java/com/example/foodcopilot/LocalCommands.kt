package com.example.foodcopilot

import android.view.accessibility.AccessibilityNodeInfo

object LocalCommands {
    private var menuCursor = 0

    private val priceRx = Regex("^₹?\\s?\\d+(\\.\\d+)?$")
    private val ratingRx2 = Regex("^\\d(\\.\\d)?$|rating", RegexOption.IGNORE_CASE)
    private val menuNoise = Regex(
        "^(pure veg|rating 4\\+|bestseller|search for dishes|filter|sort|veg|non-veg)$",
        RegexOption.IGNORE_CASE
    )

    private fun dishLines(screen: ParsedScreen): List<String> {
        val els = screen.elements
        val out = mutableListOf<String>()
        var prevAdd = -1
        for ((i, e) in els.withIndex()) {
            if (!e.label.trim().equals("add", ignoreCase = true)) continue
            val window = els.subList(prevAdd + 1, i).map { it.label.trim() }
            prevAdd = i
            val diet = window.firstOrNull {
                it.contains("vegetarian", true) || it.contains("non-veg", true)
            }
            val price = window.firstOrNull { priceRx.matches(it) || it.contains("₹") }
            val name = window.firstOrNull {
                it.length > 2 && it != diet && it != price &&
                        !ratingRx2.containsMatchIn(it) && !menuNoise.matches(it)
            }
            if (name != null) {
                out.add(listOfNotNull(diet, name, price?.let { "rupees ${it.replace("₹", "")}" })
                    .joinToString(", "))
            }
        }
        return out
    }

    private val foods = listOf(
        "biryani", "paneer", "pizza", "burger", "dosa", "idli", "momos", "pasta",
        "noodles", "sandwich", "thali", "chicken", "roll", "cake", "coffee", "tea",
        "ice cream", "samosa", "chole", "rajma", "paratha", "fries", "salad"
    )

    private val prefixes = listOf("search for ", "search ", "find ", "order ", "show me ", "i want ", "i need ")

    /** Returns a Decision handled on the phone, or null if Gemini should handle it. */
    private var readFrom = 0

    private val dietLabels = listOf("vegetarian", "non-vegetarian", "non vegetarian", "egg")
    private val noise = setOf("ad", "ads", "sponsored", "promoted")
    private val fragment = Regex(
        "^(rs\\.?\\s*|₹\\s*)?\\d[\\d.,-]*\\s*(mins?|km|%|off)?(\\s*\\(.*\\))?$|^★?\\s*\\d(\\.\\d)?\\s*\\(\\d+\\)$",
        RegexOption.IGNORE_CASE
    )
    private val ratingRx = Regex("^★?\\s*(\\d(?:\\.\\d)?)\\s*\\((\\d+)\\)$")

    private fun tidy(t: String) =
        ratingRx.replace(t) { "${it.groupValues[1]} stars, ${it.groupValues[2]} ratings" }

    private fun readable(screen: ParsedScreen): List<String> {
        val raw = screen.elements
            .filter { it.type == "text" && !it.label.startsWith("unlabeled") && !it.label.startsWith("[") }
            .map { it.label.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in noise }
        val out = mutableListOf<String>()
        var diet: String? = null
        for (t in raw) {
            if (dietLabels.any { it.equals(t, ignoreCase = true) }) { diet = t; continue }
            if (fragment.matches(t) && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + ", " + tidy(t)
                continue
            }
            out.add(if (diet != null) "$t, $diet" else t)
            diet = null
        }
        return out.distinct()
    }
    fun match(said: String, screen: ParsedScreen): Decision? {
        val s = said.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (Regex("\\b(read|show)( the)? (menu|dishes|items)\\b|\\bskip to (menu|dishes)\\b").containsMatchIn(s)) {
            menuCursor = 0
        }
        if (Regex("\\b(read|show)( the)? (menu|dishes|items)\\b|\\bskip to (menu|dishes)\\b|\\b(more|next) (dishes|items)\\b").containsMatchIn(s)) {
            val dishes = dishLines(screen)
            if (dishes.isEmpty()) {
                return Decision("I could not find dishes here. Try scroll down.", "none", 0, "", false)
            }
            val chunk = dishes.drop(menuCursor).take(4)
            if (chunk.isEmpty()) {
                return Decision("That is all the dishes on this screen. Say scroll down, then read menu.", "none", 0, "", false)
            }
            menuCursor += chunk.size
            return Decision(chunk.joinToString(". ") + ". Say more dishes for more.", "none", 0, "", false)
        }
        val wantsRead = Regex("\\b(read|red|reed|describe)\\b.*\\b(screen|page)\\b|what.*on.*screen").containsMatchIn(s)
        if (Regex("^(remove|delete)\\b").containsMatchIn(s)) return null

        if (s.startsWith("add ")) {
            val name = s.removePrefix("add ")
                .removeSuffix(" to cart").removeSuffix(" to my cart").trim()
            val words = name.split(" ").filter { it.isNotBlank() }
            if (words.isEmpty()) return Decision("Say add, then the dish name.", "none", 0, "", false)

            val els = screen.elements
            // find the dish: a label that contains every word you said
            val dishIdx = els.indexOfFirst { e ->
                val l = e.label.lowercase()
                e.label.lowercase() != "add" && words.all { l.contains(it) }
            }
            if (dishIdx < 0) {
                return Decision("I could not find $name on this screen. Try scroll down or read screen.", "none", 0, "", false)
            }
            // the Add button is the first "add" label after the dish, within the next few items
            val addEl = els.drop(dishIdx + 1).take(8)
                .firstOrNull { it.label.trim().equals("add", ignoreCase = true) }
            if (addEl == null) {
                return Decision("I found $name but not its Add button. Try scroll down.", "none", 0, "", false)
            }
            return Decision("Add $name to cart. Say yes to confirm.", "click", addEl.id, "", true)
        }

        if (s == "back" || s == "go back")
            return Decision("Going back.", "back", null, null, false)

        if (s in listOf("scroll", "scroll down", "more", "show more"))
            return Decision("Scrolling down.", "scroll", null, null, false)

        val wantsMore = Regex("\\bread more\\b|\\bcontinue\\b|\\bkeep reading\\b|\\bnext\\b").containsMatchIn(s)
        if (wantsRead || wantsMore || s == "read") {
            val items = readable(screen)
            if (!wantsMore) readFrom = 0
            if (items.isEmpty())
                return Decision("I cannot find any text on this screen.", "none", null, null, false)
            if (readFrom >= items.size) {
                readFrom = 0
                return Decision("That is all on this screen. Say scroll down for more.", "none", null, null, false)
            }
            val chunk = mutableListOf<String>()
            var chars = 0
            for (item in items.drop(readFrom)) {
                if (chunk.isNotEmpty() && chars + item.length > 260) break
                chunk.add(item)
                chars += item.length
            }
            readFrom += chunk.size
            val tail = if (readFrom < items.size) " Say read more to continue."
            else " That is all on this screen. Say scroll down for more."
            return Decision(chunk.joinToString(". ") + "." + tail, "none", null, null, false)
        }

        val openPrefixes = listOf("open ", "select ", "go to ", "choose ","click ","tap ","press ")
        for (p in openPrefixes) {
            if (s.startsWith(p)) {
                val name = s.removePrefix(p).trim()
                if (name.length >= 3) {
                    val words = name.split(" ").filter { it.isNotBlank() }
                    val candidates = screen.elements.filter { e ->
                        e.type != "input" && words.all { e.label.lowercase().contains(it) }
                    }
                    val match = candidates.firstOrNull { canClick(it.node) } ?: candidates.firstOrNull()
                    if (match != null)
                        return Decision("Opening ${match.label}.", "click", match.id, null, false)
                    return Decision("I could not find $name on this screen.", "none", null, null, false)
                }
            }
        }
        var query: String? = null
        for (p in prefixes) {
            if (s.startsWith(p)) { query = s.removePrefix(p).trim(); break }
        }
        if (query == null && s.split(" ").size <= 4 && foods.any { s.contains(it) }) query = s
        if (query.isNullOrBlank()) return null

        val input = screen.elements.firstOrNull { it.type == "input" }
        if (input != null) return Decision("Searching for $query.", "type", input.id, query, false)

        val searchBox = screen.elements.firstOrNull { it.label.contains("search", ignoreCase = true) }
        if (searchBox != null) return Decision("Opening search.", "click", searchBox.id, query, false)

        return null
    }
    private fun canClick(n: AccessibilityNodeInfo?): Boolean {
        var x = n
        var d = 0
        while (x != null && d < 6) {
            if (x.isClickable) return true
            x = x.parent
            d++
        }
        return false
    }
}