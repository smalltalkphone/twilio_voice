package com.twilio.twilio_voice.types

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.PermissionChecker
import com.twilio.twilio_voice.service.TVConnectionService
import com.twilio.twilio_voice.types.ContextExtension.appName
import com.twilio.twilio_voice.types.ContextExtension.hasReadPhoneNumbersPermission
import com.twilio.twilio_voice.types.ContextExtension.hasReadPhoneStatePermission
import com.twilio.twilio_voice.R
import com.twilio.twilio_voice.call.TVParameters

object TelecomManagerExtension {

    /**
     *  Register a phone account with the system telecom manager
     *  @param ctx application context
     *  @param phoneAccountHandle The handle for the phone account
     *  @param label The label for the phone account
     *  @param shortDescription The short description for the phone account
     */
    @RequiresPermission(value = "android.permission.READ_PHONE_STATE")
    fun TelecomManager.registerPhoneAccount(ctx: Context, phoneAccountHandle: PhoneAccountHandle) {
        if (hasCallCapableAccount(ctx, phoneAccountHandle.componentName.className)) {
            // phone account already registered
            Log.d("TelecomManager", "registerPhoneAccount: phone account already re-registering.")
//            return
        }

        val label = ctx.getString(R.string.phone_account_name).apply {
            this.ifEmpty {
                ctx.appName
            }
        }
        val description = ctx.getString(R.string.phone_account_desc).apply {
            this.ifEmpty {
                "Provides calling services for $label"
            }
        }

        // register phone account
        //
        // SELF_MANAGED, not CALL_PROVIDER. The two are mutually exclusive, and
        // the distinction decides who presents an incoming call.
        //
        // As a CALL_PROVIDER we were registering as a telephony-style carrier
        // account: Telecom handed incoming calls to the SYSTEM DIALER to
        // present. Observed consequences on a Pixel 9 Pro (2026-07-25 bench):
        //
        //   * incoming calls ANSWERED THEMSELVES — the app's own Accept /
        //     Decline screen never got to render, so the microphone opened
        //     without the callee consenting;
        //   * Google Call Screen intercepted incoming calls as suspicious,
        //     because a call provider presenting an unrecognised `tel:` number
        //     is exactly what it exists to filter.
        //
        // SELF_MANAGED is what every other VoIP app on the device uses
        // (WhatsApp, Signal, X all register this way): the call stays out of
        // the system dialer, the app owns its in-call and incoming UI — which
        // this app already draws — and it is never screened.
        //
        // Requires MANAGE_OWN_CALLS, which the host app already declares and
        // holds. CALL_SUBJECT is dropped with CALL_PROVIDER: it is a
        // dialer-presentation feature and means nothing to a self-managed
        // account that renders its own screens.
        val phoneAccount = PhoneAccount.builder(phoneAccountHandle, label)
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setShortDescription(description)
//            .addSupportedUriScheme(TVConnectionService.TWI_SCHEME)
            .setIcon(Icon.createWithResource(ctx, phoneAccountIcon(ctx)))
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .build()

        registerPhoneAccount(phoneAccount)
    }

    /**
     * Drawable to show for this PhoneAccount in Settings -> Calling accounts.
     *
     * Prefers a drawable the host app names `ic_phone_account`, falling back to
     * the app's launcher icon (the previous, unconditional behaviour) when it
     * defines none - so this is backwards compatible for any existing consumer.
     *
     * Why: the launcher icon is a full-colour, rounded product mark, but the
     * system renders this one small (~24px) in a themed list. Apps generally
     * want a dedicated single-colour mark there, and had no way to supply one.
     */
    private fun phoneAccountIcon(ctx: Context): Int {
        val res = ctx.resources.getIdentifier("ic_phone_account", "drawable", ctx.packageName)
        return if (res != 0) res else ctx.applicationInfo.icon
    }

    fun TelecomManager.openPhoneAccountSettings(activity: Activity) {
        if (Build.MANUFACTURER.equals("Samsung", ignoreCase = true)|| Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)) {
            try {
                val intent = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
                intent.component = ComponentName(
                    "com.android.server.telecom",
                    "com.android.server.telecom.settings.EnableAccountPreferenceActivity"
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                activity.startActivity(intent, null)
            } catch (e: Exception) {
                Log.e("TelecomManager", "openPhoneAccountSettings: ${e.message}")

                // use fallback method
                val intent = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
                activity.startActivity(intent, null)
            }

        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
            activity.startActivity(intent, null)
        }
    }


    /**
     * Check if a phone account has been registered with the system telecom manager
     * @param ctx application context
     * @param name The name of the componentName Class (i.e. ConnectionService)
     */
    @RequiresPermission(value = "android.permission.READ_PHONE_STATE")
    fun TelecomManager.hasCallCapableAccount(ctx: Context, name: String): Boolean {
        if (!canReadPhoneState(ctx)) return false
        if (callCapablePhoneAccounts.any { it.componentName.className == name }) {
            return true
        }
        // A SELF_MANAGED account is never returned by
        // getCallCapablePhoneAccounts() — the platform excludes self-managed
        // accounts from that list by design, since they are not available to
        // the system dialer for placing calls.
        //
        // Checking only that list therefore reports "no registered phone
        // account" for an account that Telecom has just confirmed registering,
        // and the plugin refuses to place the call with:
        //
        //   E TwilioVoicePlugin: No registered phone account, call
        //   `registerPhoneAccount()` first
        //
        // Observed on a Pixel 9 Pro immediately after switching this plugin to
        // CAPABILITY_SELF_MANAGED: outgoing calls silently never dialled, and
        // hang-up then failed too (ACTION_HANGUP with no EXTRA_CALL_HANDLE,
        // because no call had been created to hang up).
        //
        // Ask about the account directly instead — getPhoneAccount() answers
        // for self-managed and managed accounts alike, and needs no API-level
        // guard, unlike getSelfManagedPhoneAccounts() (API 31+).
        return getPhoneAccount(getPhoneAccountHandle(ctx)) != null
    }

    /**
     * Get the [PhoneAccountHandle] for the app
     * @param ctx application context
     * @return PhoneAccountHandle The phone account handle for the app
     */
    fun TelecomManager.getPhoneAccountHandle(ctx: Context): PhoneAccountHandle {
        val appName = ctx.appName
        val componentName = ComponentName(ctx, TVConnectionService::class.java)

        Log.d(TVConnectionService.TAG, "getPhoneAccountHandle: Get PhoneAccountHandle with name: $appName, componentName: $componentName")
        return PhoneAccountHandle(componentName, appName)
    }

    /**
     * Check if the app has the READ_PHONE_STATE permission
     * @param ctx application context
     * @return Boolean True if the app has the READ_PHONE_STATE permission
     */
    fun TelecomManager.canReadPhoneState(ctx: Context): Boolean {
        return ctx.hasReadPhoneStatePermission()
    }

    /**
     * Check if the app has the READ_PHONE_NUMBERS permission
     * @param ctx application context
     * @return Boolean True if the app has the READ_PHONE_NUMBERS permission
     */
    fun TelecomManager.canReadPhoneNumbers(ctx: Context): Boolean {
        return ctx.hasReadPhoneNumbersPermission()
    }

    @RequiresPermission(value = "android.permission.READ_PHONE_STATE")
    fun TelecomManager.isOnCall(ctx: Context): Boolean {
        if (!canReadPhoneState(ctx)) return false
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O) isInCall else isInManagedCall
    }
}