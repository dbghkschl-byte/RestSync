package com.restsync.demo

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object GleoBridge {
    val actions = setOf("FIND_REST_STOP", "SELECT_REST_STOP", "START_REST", "CHECK_REST_TIME", "EXTEND_REST", "END_REST")
    fun parameters(raw: String): JSONObject {
        val value = JSONTokener(raw).nextValue()
        val obj = if (value is String) JSONObject(value) else value as? JSONObject
            ?: throw IllegalArgumentException("Action parameters must be an object")
        return obj.optJSONObject("actionParameters") ?: obj
    }
    fun response(name: String, status: String, message: String, data: JSONArray = JSONArray()): String =
        JSONObject().apply {
            put("functionName", name); put("status", status); put("message", message)
            // The official callback schema requires a JSON-array STRING, not an array value.
            put("data", data.toString())
        }.toString()
    fun send(context: Context, name: String, status: String, message: String, data: JSONArray = JSONArray()) {
        context.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").apply {
            putExtra("packageName", context.packageName)
            putExtra("response", response(name, status, message, data))
        })
    }
}
