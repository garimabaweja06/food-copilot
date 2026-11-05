package com.example.foodcopilot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Decision(
    val speak: String,
    val action: String,
    val target: Int?,
    val text: String?,
    val needsConfirmation: Boolean
)

class LlmClient(private val proxyUrl: String = "https://copilot-proxy-five.vercel.app//api/ai") {

    private val http = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val systemPrompt = """
You are a voice co-pilot for a blind user ordering food in Swiggy or Zomato.
Input: the screen as lines like "[id] type: label", plus the user's request.
Reply ONLY with JSON: {"speak":"","action":"click|type|scroll|back|none","target":null,"text":null,"needs_confirmation":false}
Rules: keep speech short, max 4 items. Use only ids that are on the screen. Set needs_confirmation true for cart changes, orders, address changes or spending. Never touch payment, card, UPI, OTP or PIN; tell the user to pay themselves. If unclear, ask one short question with action none.
""".trimIndent()

    suspend fun decide(screen: ParsedScreen, userCommand: String): Decision =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("system", systemPrompt)
                put("user", "SCREEN:\n${screen.toPrompt()}\n\nUSER SAID: $userCommand")
            }
            val req = Request.Builder()
                .url(proxyUrl)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Decision(
                            when (resp.code) {
                                503 -> "The assistant is busy. Please say that again."
                                429 -> "The assistant has reached its limit for now. Please try again later."
                                else -> "Something went wrong. Please try again."
                            },
                            "none", null, null, false
                        )
                    } else {
                        val reply = JSONObject(raw).optString("text", "")
                        if (reply.isBlank()) {
                            Decision("Empty answer. ${raw.take(150)}", "none", null, null, false)
                        } else {
                            val json = JSONObject(reply.substring(reply.indexOf('{'), reply.lastIndexOf('}') + 1))
                            Decision(
                                speak = json.optString("speak", "Sorry, I did not understand."),
                                action = json.optString("action", "none"),
                                target = if (json.isNull("target")) null else json.optInt("target"),
                                text = if (json.isNull("text")) null else json.optString("text"),
                                needsConfirmation = json.optBoolean("needs_confirmation", true)
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Decision("I could not connect. Please check your internet and try again.", "none", null, null, false)
            }
        }
}