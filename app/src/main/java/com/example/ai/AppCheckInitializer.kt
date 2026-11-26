package com.example.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import com.example.BuildConfig
import com.example.R
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
fun configureAppCheck(context: Context, intent: Intent?): Boolean {
    try {
        val firebaseApp = FirebaseApp.getApps(context).firstOrNull()
            ?: FirebaseApp.initializeApp(context)
        if (firebaseApp == null) {
            Log.e(TAG, "Firebase is not configured; add app/google-services.json before using Gemini.")
            return false
        }
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.w(TAG, "FirebaseApp is not initialized (missing or invalid google-services.json); skipping App Check.")
            return
        }

        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = firebaseApp.persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE,
                )
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance(),
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val siteKey = context.getString(R.string.recaptcha_site_key)
            if (siteKey.isNotBlank()) {
                FirebaseAppCheck.getInstance(firebaseApp).installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey),
                )
                Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
            } else {
                Log.e(TAG, "recaptcha_site_key string resource is blank.")
                return false
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to initialize Firebase App Check", t)
    }
}
