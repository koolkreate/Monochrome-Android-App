package com.sample.monochome

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONException
import org.json.JSONObject

class WebAppInterface(private val context: Context) {

    @JavascriptInterface
    fun updatePlaybackState(stateJson: String) {
        try {
            val json = JSONObject(stateJson)
            val title = json.optString("title", "Unknown Title")
            val artist = json.optString("artist", "Unknown Artist")
            val coverUrl = json.optString("coverUrl", "")
            val isPlaying = json.optBoolean("isPlaying", false)
            val duration = json.optLong("duration", 0L)
            val position = json.optLong("position", 0L)
            val isLiked = json.optBoolean("isLiked", false)
            
            // Broadcast the state to the service
            val intent = Intent("com.sample.monochome.PLAYBACK_STATE_UPDATED")
            intent.putExtra("title", title)
            intent.putExtra("artist", artist)
            intent.putExtra("coverUrl", coverUrl)
            intent.putExtra("isPlaying", isPlaying)
            intent.putExtra("duration", duration)
            intent.putExtra("position", position)
            intent.putExtra("isLiked", isLiked)
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
            
        } catch (e: JSONException) {
            Log.e("WebAppInterface", "Error parsing state JSON", e)
        }
    }
}
