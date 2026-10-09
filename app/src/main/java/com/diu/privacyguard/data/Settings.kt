package com.diu.privacyguard.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Small settings saved on the phone only. */
object Settings {
    private val LAST_UPDATED = longPreferencesKey("last_updated")
    private val DISCLOSURE_ACCEPTED = booleanPreferencesKey("disclosure_accepted")

    /** When the list was last downloaded, or 0 if only the built-in list has been used. */
    fun lastUpdated(context: Context): Flow<Long> =
        context.applicationContext.dataStore.data.map { it[LAST_UPDATED] ?: 0L }

    suspend fun setLastUpdated(context: Context, time: Long) {
        context.applicationContext.dataStore.edit { it[LAST_UPDATED] = time }
    }

    fun disclosureAccepted(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { it[DISCLOSURE_ACCEPTED] ?: false }

    suspend fun setDisclosureAccepted(context: Context) {
        context.applicationContext.dataStore.edit { it[DISCLOSURE_ACCEPTED] = true }
    }
}
