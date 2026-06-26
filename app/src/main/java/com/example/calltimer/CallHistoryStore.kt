package com.example.calltimer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CallRecord(
    val timestamp: Long,
    val number: String,
    val mode: String,
    val durationSec: Double,
    val result: String
)

/** Lưu lịch sử cuộc gọi vào SharedPreferences (giữ qua các lần mở app). */
class CallHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("call_history", Context.MODE_PRIVATE)

    fun load(): List<CallRecord> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = ArrayList<CallRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                CallRecord(
                    o.optLong("ts"),
                    o.optString("num"),
                    o.optString("mode"),
                    o.optDouble("dur"),
                    o.optString("res")
                )
            )
        }
        return list
    }

    fun add(record: CallRecord) {
        val arr = JSONArray(prefs.getString("items", "[]") ?: "[]")
        val o = JSONObject()
        o.put("ts", record.timestamp)
        o.put("num", record.number)
        o.put("mode", record.mode)
        o.put("dur", record.durationSec)
        o.put("res", record.result)
        arr.put(o)
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("items").apply()
    }
}
