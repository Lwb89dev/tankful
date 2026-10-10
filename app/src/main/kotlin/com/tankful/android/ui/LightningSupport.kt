package com.tankful.android.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Same Lightning address Roadstr opens from Settings. */
internal const val LIGHTNING_ADDRESS = "lwb89@blink.sv"

internal fun lightningSupportUri(): String = "lightning:$LIGHTNING_ADDRESS"

/**
 * Opens a wallet on the Roadstr Lightning address.
 * When no wallet accepts the link, copies the address and returns false.
 */
internal fun openLightningSupport(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(lightningSupportUri()))
    intent.addCategory(Intent.CATEGORY_DEFAULT)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (intent.resolveActivity(context.packageManager) == null) {
        copyLightningAddress(context)
        return false
    }
    return try {
        context.startActivity(intent)
        true
    } catch (ignored: ActivityNotFoundException) {
        copyLightningAddress(context)
        false
    }
}

private fun copyLightningAddress(context: Context) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Lightning", LIGHTNING_ADDRESS))
}
