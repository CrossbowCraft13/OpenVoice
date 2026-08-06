package com.example.openvoice.operator

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SmsManager
import com.example.openvoice.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OperatorRegistry @Inject constructor() {

    private val ops = mutableMapOf<String, suspend (Context, Map<String, String>) -> OperatorResult>()

    init {
        ops["LAUNCH_APP"] = launch@ { ctx, params ->
            val name = params["app"] ?: return@launch OperatorResult(false, "No app specified")
            val pkg = KNOWN_APPS[name.lowercase()] ?: name
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                OperatorResult(true, "Opened $name") }
            else OperatorResult(false, "Can't open $name")
        }
        ops["SEND_SMS"] = sms@ { ctx, params ->
            val phone = params["contact"] ?: params["phone"] ?: return@sms OperatorResult(false, "No recipient")
            val msg = params["message"] ?: params["text"] ?: return@sms OperatorResult(false, "No message")
            SmsManager.getDefault().sendTextMessage(phone, null, msg, null, null)
            OperatorResult(true, "Message sent")
        }
        ops["MAKE_CALL"] = call@ { ctx, params ->
            val phone = params["contact"] ?: params["phone"] ?: return@call OperatorResult(false, "No recipient")
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            OperatorResult(true, "Dialing $phone")
        }
        ops["SET_TIMER"] = timer@ { ctx, params ->
            val dur = params["duration"] ?: return@timer OperatorResult(false, "No duration")
            val secs = parseDuration(dur)
            ctx.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, secs)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            OperatorResult(true, "Timer set for ${secs}s")
        }
        ops["SET_ALARM"] = alarm@ { ctx, params ->
            val time = params["time"] ?: return@alarm OperatorResult(false, "No time")
            val (h, m) = parseTime(time)
            ctx.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h); putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_SKIP_UI, false); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            OperatorResult(true, "Alarm set for ${h}:${"%02d".format(m)}")
        }
        ops["OPEN_SETTINGS"] = { ctx, params ->
            val target = (params["target"] ?: "main").lowercase()
            val action = when (target) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS; "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "sound" -> Settings.ACTION_SOUND_SETTINGS; "display" -> Settings.ACTION_DISPLAY_SETTINGS
                "apps" -> Settings.ACTION_APPLICATION_SETTINGS; "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            OperatorResult(true, "Opened $target settings")
        }
        ops["ADJUST_VOLUME"] = { ctx, params ->
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val level = params["level"]
            val dir = params["direction"]
            when {
                level != null -> {
                    val pct = parsePercent(level)
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, pct * max / 100, AudioManager.FLAG_SHOW_UI)
                    OperatorResult(true, "Volume $pct%")
                }
                dir != null -> {
                    val flag = when (dir.lowercase()) { "up" -> AudioManager.ADJUST_RAISE; "down" -> AudioManager.ADJUST_LOWER; "mute" -> AudioManager.ADJUST_MUTE; "unmute" -> AudioManager.ADJUST_UNMUTE; else -> AudioManager.ADJUST_SAME }
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, flag, AudioManager.FLAG_SHOW_UI)
                    OperatorResult(true, "Volume $dir")
                }
                else -> OperatorResult(false, "No level or direction")
            }
        }
        ops["HELP"] = { _, _ -> OperatorResult(true, "I can open apps, send messages, set timers and alarms, adjust volume, and more. Try saying 'open Spotify' or 'set a timer for 5 minutes'.") }
        ops["STOP"] = { _, _ -> OperatorResult(true, "Stopped") }
        ops["QUERY"] = { _, _ -> OperatorResult(true, "I'm a local assistant. For complex queries, I need a language model loaded.") }
    }

    fun ids() = ops.keys
    suspend fun exec(id: String, ctx: Context, params: Map<String, String>): OperatorResult {
        val op = ops[id] ?: return OperatorResult(false, "Unknown operator: $id")
        return try {
            op(ctx, params)
        } catch (e: Exception) {
            // A system denial (e.g. activity not startable, SMS permission
            // missing) must never crash the voice pipeline.
            Logger.e("Operator $id failed: ${e.message}", "Operator", e)
            OperatorResult(false, "${id} failed: ${e.message}")
        }
    }

    private fun parseDuration(s: String): Int {
        val c = s.lowercase().replace(Regex("(for |a |an | )+"), "")
        val re = Regex("""(\d+)\s*(min|minute|minutes|sec|second|seconds|hour|hours)?""")
        var total = 0
        for (m in re.findAll(c)) {
            val v = m.groupValues[1].toIntOrNull() ?: continue
            total += when (m.groupValues[2].take(3)) { "hou" -> v * 3600; "min" -> v * 60; else -> v }
        }
        if (total == 0) total = c.toIntOrNull() ?: 0
        return total
    }

    private fun parseTime(s: String): Pair<Int, Int> {
        val c = s.lowercase().replace(Regex("[ap]m"), "").trim()
        val parts = c.split(Regex("[:.\\s]+")).map { it.toIntOrNull() ?: -1 }
        if (parts.size < 1 || parts[0] < 0 || parts[0] > 23) return Pair(-1, -1)
        val isPm = s.lowercase().contains("pm")
        var h = parts[0]; val m = if (parts.size > 1) parts[1] else 0
        if (m < 0 || m > 59) return Pair(-1, -1)
        if (isPm && h != 12) h += 12
        if (!isPm && h == 12) h = 0
        return Pair(h, m)
    }

    private fun parsePercent(s: String): Int {
        val n = s.lowercase().replace(Regex("[% ]"), "").toIntOrNull()
        return when { n != null -> n.coerceIn(0, 100)
            s.lowercase() in listOf("max", "maximum", "full", "loud") -> 100
            s.lowercase() in listOf("min", "minimum", "off", "silent", "mute") -> 0
            s.lowercase() in listOf("half", "mid", "medium") -> 50
            else -> n ?: 0
        }
    }

    companion object {
        val KNOWN_APPS = mapOf(
            "settings" to "com.android.settings", "camera" to "com.android.camera2",
            "chrome" to "com.android.chrome", "photos" to "com.google.android.apps.photos",
            "maps" to "com.google.android.apps.maps", "phone" to "com.android.dialer",
            "messages" to "com.android.messaging", "spotify" to "com.spotify.music",
            "youtube" to "com.google.android.youtube", "clock" to "com.android.deskclock",
            "calculator" to "com.android.calculator2", "calendar" to "com.android.calendar",
            "gmail" to "com.google.android.gm",
        )
    }
}
