package com.jo.linktrash

import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsService
import androidx.browser.customtabs.CustomTabsSessionToken

class LinkTrashCustomTabsService : CustomTabsService() {

    override fun warmup(flags: Long): Boolean = true

    override fun newSession(sessionToken: CustomTabsSessionToken): Boolean = true

    override fun mayLaunchUrl(
        sessionToken: CustomTabsSessionToken,
        url: Uri?,
        extras: Bundle?,
        otherLikelyBundles: MutableList<Bundle>?
    ): Boolean = true

    override fun extraCommand(commandName: String, args: Bundle?): Bundle? = null

    override fun updateVisuals(sessionToken: CustomTabsSessionToken, bundle: Bundle?): Boolean = true

    override fun requestPostMessageChannel(
        sessionToken: CustomTabsSessionToken,
        postMessageOrigin: Uri
    ): Boolean = false

    override fun postMessage(
        sessionToken: CustomTabsSessionToken,
        message: String,
        extras: Bundle?
    ): Int = 0

    override fun validateRelationship(
        sessionToken: CustomTabsSessionToken,
        relation: Int,
        origin: Uri,
        extras: Bundle?
    ): Boolean = false

    override fun receiveFile(
        sessionToken: CustomTabsSessionToken,
        uri: Uri,
        purpose: Int,
        extras: Bundle?
    ): Boolean = false
}
