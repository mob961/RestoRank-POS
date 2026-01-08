package ai.restorank.bridge

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

class VoiceManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val handler = Handler(Looper.getMainLooper())
    private var toneGenerator: ToneGenerator? = null

    companion object {
        private const val TAG = "VoiceManager"
        private const val MAX_ITEMS_TO_SPEAK = 5
        private const val REPEAT_DELAY_MS = 3000L
        
        enum class DeviceRole {
            KITCHEN,
            CASHIER
        }
        
        val deviceRole = DeviceRole.KITCHEN
        var voiceEnabled = true
    }

    private var pendingRepeatText: String? = null

    init {
        tts = TextToSpeech(context, this)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ToneGenerator: ${e.message}")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported, trying default")
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(1.0f)
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "kot_initial" && pendingRepeatText != null) {
                        val textToRepeat = pendingRepeatText
                        pendingRepeatText = null
                        handler.postDelayed({
                            playBeep()
                            handler.postDelayed({
                                speak(textToRepeat ?: "", "kot_repeat")
                            }, 500)
                        }, REPEAT_DELAY_MS)
                    }
                }
                
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error for utterance: $utteranceId")
                    pendingRepeatText = null
                }
            })
            
            isInitialized = true
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    fun speakKotOrder(order: JSONObject, repeatOnce: Boolean = true) {
        if (!voiceEnabled || deviceRole != DeviceRole.KITCHEN) {
            Log.d(TAG, "Voice disabled or not kitchen device, skipping")
            return
        }

        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized yet")
            return
        }
        
        val eventType = order.optString("eventType", "")
        if (eventType == "delivery_order_created" || 
            eventType == "bill_printed" || 
            eventType == "payment_completed") {
            Log.d(TAG, "Skipping voice for event type: $eventType")
            return
        }

        val announcement = buildKotAnnouncement(order)
        if (announcement.isNotEmpty()) {
            Log.d(TAG, "KOT Voice announcement: $announcement")
            playBeepAndSpeak(announcement, repeatOnce)
        }
    }

    private fun buildKotAnnouncement(order: JSONObject): String {
        val tableName = order.optString("tableName", "")
        val tableNumber = order.optString("tableNumber", "")
        val items = order.optJSONArray("items") ?: JSONArray()
        
        if (items.length() == 0) {
            return ""
        }

        val sb = StringBuilder()
        
        if (tableName.isNotEmpty()) {
            sb.append("Table $tableName. ")
        } else if (tableNumber.isNotEmpty()) {
            sb.append("Table $tableNumber. ")
        } else {
            val orderType = order.optString("type", "")
            if (orderType.equals("takeaway", ignoreCase = true)) {
                sb.append("Takeaway order. ")
            } else if (orderType.equals("delivery", ignoreCase = true)) {
                sb.append("Delivery order. ")
            } else {
                sb.append("New order. ")
            }
        }

        if (items.length() > MAX_ITEMS_TO_SPEAK) {
            sb.append("Multiple items. ")
        } else {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val name = item.optString("name", "")
                val qty = item.optInt("quantity", 1)
                
                if (name.isNotEmpty()) {
                    if (qty > 1) {
                        sb.append("$qty $name. ")
                    } else {
                        sb.append("$name. ")
                    }
                }
            }
        }

        val notes = extractNotes(items)
        if (notes.isNotEmpty()) {
            sb.append("Notes: $notes")
        }

        return sb.toString().trim()
    }

    private fun extractNotes(items: JSONArray): String {
        val notesList = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val notes = item.optString("notes", "").trim()
            val instructions = item.optString("instructions", "").trim()
            if (notes.isNotEmpty()) {
                notesList.add(notes)
            }
            if (instructions.isNotEmpty() && instructions != notes) {
                notesList.add(instructions)
            }
        }
        return notesList.take(3).joinToString(". ")
    }

    private fun playBeepAndSpeak(text: String, repeatOnce: Boolean) {
        playBeep()
        
        if (repeatOnce) {
            pendingRepeatText = text
        }
        
        handler.postDelayed({
            speak(text, "kot_initial")
        }, 500)
    }

    private fun playBeep() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play beep: ${e.message}")
        }
    }

    private fun speak(text: String, utteranceId: String) {
        if (!isInitialized || text.isEmpty()) {
            return
        }
        
        Log.d(TAG, "Speaking: $text")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        toneGenerator?.release()
        toneGenerator = null
        isInitialized = false
    }
}
