package com.rpsonline.app

import android.content.Context
import android.content.res.Configuration
import com.google.firebase.auth.FirebaseAuth
import java.util.Locale

/** Forces English resources regardless of device language. */
object AppLocale {
    private val ENGLISH = Locale.ENGLISH
    private const val FIREBASE_LANGUAGE_CODE = "en"

    fun wrap(context: Context): Context {
        Locale.setDefault(ENGLISH)
        val config = Configuration(context.resources.configuration)
        config.setLocale(ENGLISH)
        return context.createConfigurationContext(config)
    }

    /** Sets X-Firebase-Locale on Auth HTTP requests (avoids null-header log spam). */
    fun applyFirebaseAuthLanguage(auth: FirebaseAuth = FirebaseAuth.getInstance()) {
        auth.setLanguageCode(FIREBASE_LANGUAGE_CODE)
    }
}
