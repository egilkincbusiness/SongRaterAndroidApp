package com.songrater

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("songrater", Context.MODE_PRIVATE)

    fun source(c: Context): Uri? = sp(c).getString("source", null)?.let { Uri.parse(it) }
    fun dest(c: Context): Uri? = sp(c).getString("dest", null)?.let { Uri.parse(it) }
    fun setSource(c: Context, u: Uri) = sp(c).edit().putString("source", u.toString()).apply()
    fun setDest(c: Context, u: Uri) = sp(c).edit().putString("dest", u.toString()).apply()

    fun theme(c: Context): Int = sp(c).getInt("theme", THEME_SYSTEM)
    fun setTheme(c: Context, t: Int) = sp(c).edit().putInt("theme", t).apply()

    fun applyTheme(c: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(c)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // A rating the user has tapped but whose file has not been moved yet. Saved so it survives the
    // app being closed mid-song; it is applied the next time the app starts.
    fun pendingUri(c: Context): String? = sp(c).getString("pending_uri", null)
    fun pendingStars(c: Context): Int = sp(c).getInt("pending_stars", 0)
    fun setPending(c: Context, uri: String, stars: Int) =
        sp(c).edit().putString("pending_uri", uri).putInt("pending_stars", stars).apply()
    fun clearPending(c: Context) =
        sp(c).edit().remove("pending_uri").remove("pending_stars").apply()
}
