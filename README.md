# Food Copilot

Food apps are built for people who can see: buttons have no labels, and a single dish is split into fragments like "Vegetarian", "Paneer Tikka", "₹249", "4.3", "Add". Voice assistants can open the app, but they can't operate inside it.

Food Copilot is an Android AccessibilityService that rebuilds these broken screens into a readable order for blind and low-vision users, acts only after spoken confirmation, and never touches payments.

**Demo video:** working on it

> Prototype built as a student project. Not affiliated with Swiggy or Zomato.

## What the screen gives vs what the user hears

**Raw screen (what the accessibility tree exposes):**
`Vegetarian` · `Shahi Paneer` · `₹835` · `4.2` · `unlabeled button` · `Add`

**Food Copilot says:**
"Vegetarian, Shahi Paneer, rupees 835."

Then the user says "add shahi paneer", and the app replies: "Add Shahi Paneer to cart. Say yes to confirm."

*(Example values; replace with a real one from your own test.)*

## Privacy and safety

- Phone numbers, emails and address-like text are hidden on the device before any screen text is sent to an AI model.
- The app stops reading on payment, card, UPI, wallet and billing screens.
- It asks for spoken confirmation ("Say yes to confirm") before adding anything to the cart.
- It never completes a payment. The user always pays themselves.
- It only works while Swiggy or Zomato is in the foreground, and Volume Up is ignored elsewhere.
- The app stays quiet unless the user has just used it, so it never speaks at random times.

## Design decisions

- **Local-first:** common commands run on the phone, so they are instant and use no AI quota.
- **Failover:** if one AI provider is overloaded or out of quota, the proxy tries the other.
- **Keys stay on the server:** API keys are stored only as environment variables on Vercel, never in the app or in this repository.
- **Reading order:** the screen is rebuilt into logical items (diet label, dish name and restaurant are grouped), and promotional and rating noise is filtered out.
- **Only what is visible:** elements hidden in swipeable rows or off-screen are skipped.
- **Speakable text:** abbreviations and symbols ("pcs", "gm", "₹") are expanded before speaking.
- **Fuzzy matching:** dish names are matched word by word, because speech recognition often mishears Indian dish names.

## How it works

```
Phone (Android app)
 ├─ AccessibilityService reads the screen's UI tree
 ├─ Local command layer (instant, no internet, no AI)
 └─ If not understood → sends redacted screen text to my proxy
                              │
                    Vercel serverless function (api/ai.js)
                              │
              Groq (first choice)  →  Gemini (backup)
```

**Proxy repository:** https://github.com/garimabaweja06/copilot-proxy/tree/main

## Supported commands

Press **Volume Up** while Swiggy or Zomato is open, then speak.

| Command | What happens |
|---|---|
| "search paneer" | Types into the search box and searches |
| "read screen" | Reads what is on screen in a sensible order |
| "next" / "read more" | Continues reading the next chunk |
| "scroll down", "back" | Scrolls the screen, goes back |
| "open / select / click / tap [name]" | Taps the matching item |
| "read menu", "more dishes" | Reads only the dishes with name and price |
| "add [dish]" | Finds the dish's Add button, asks for confirmation, then taps it |
| "view cart" | Opens the cart |

Anything it doesn't recognise on its own goes to the AI fallback.

## How this differs from Siri, Google, Gemini and TalkBack

| | Siri / Google Assistant | TalkBack | Food Copilot |
|---|---|---|---|
| Opens the app | Yes | Not its job | Not its job |
| Works inside the app's screens | No (in my tests it only opened the app, or a web page) | Reads elements one by one | Reads grouped, readable results |
| Knows food-app context | No | No | Yes |
| Confirms before cart changes | No | No | Yes |
| Handles payments | Not applicable | Not applicable | Deliberately never |

*Based on my own tests on one phone; behaviour may differ on other devices.*

## Tech stack

Kotlin, Android AccessibilityService, SpeechRecognizer, TextToSpeech, Kotlin coroutines, OkHttp, JSON. Backend: a Vercel serverless function (Node.js) calling the Groq and Gemini APIs.

## Setup

1. Open the project in Android Studio and let Gradle sync.
2. In `LlmClient.kt`, set the proxy URL to your own deployment of the proxy.
3. Deploy the proxy and set these environment variables on Vercel: `GEMINI_API_KEY`, `GROQ_API_KEY`, `GROQ_MODEL`.
4. Install on an Android phone, allow the microphone permission, and turn on **Food Copilot** under Settings → Accessibility.
5. Open Swiggy or Zomato, press Volume Up and speak.

## Testing

Tested on: OPPO CPH2219, Android 13, with the real Swiggy app.

| Command | Tries | Worked | Notes |
|---|---|---|---|
| search | [5 ] | [ 5] | Search for item you speak in app search box |
| read screen | [5 ] | [5 ] |read everything on screen |
| open [name] | [5 ] | [3 ] |goes to api when it is not local commands but work when i change it into local command |
| read menu | [3 ] | [3 ] |read only dish and its price and its tell whether it is veg or non veg(if written on dish) |
| add + confirm | [ 3] | [3 ] | Cart count checked each time(goes to api before after i made it into local commands) |
| view cart | [3 ] | [2 ] |view cart depend on api |


## Known limitations

- Depends on Swiggy's and Zomato's screen layouts, which can change without notice. Buttons without labels can break a command.
- Speech recognition can mishear dish names.
- Tested on one device and with no blind or low-vision users yet. [update this if that changes]
- The proxy has no authentication, which is fine for a prototype. A real version would add a token and rate limiting.
- The AI fallback uses free-tier quotas and can be temporarily unavailable.
- The app reads what is on screen; it cannot guarantee ingredients or allergy safety.

## Future work

- Answering questions such as "is it spicy?" from on-screen text, with an explicit "check with the restaurant" warning for allergies.
- Automated tests for the command layer.
- Testing with blind and low-vision users.
- Proxy authentication and rate limiting.
