package fr.luba.poc

import android.annotation.SuppressLint
import android.content.SharedPreferences

/**
 * Synchronous write whose result matters: the edits of [block] are on disk when it returns true. For the few values
 * that must not be lost or kept (session secret, alert journal, relay sequence counter); everything else uses the
 * asynchronous `edit { }` of core-ktx, which returns nothing and so cannot report a failed write.
 */
@SuppressLint("ApplySharedPref", "UseKtx")
fun SharedPreferences.commitNow(block: SharedPreferences.Editor.() -> Unit): Boolean = edit().apply(block).commit()
