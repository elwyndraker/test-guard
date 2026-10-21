package com.diu.privacyguard.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Small settings saved on the phone only. */
object Settings {
    private val LAST_UPDATED = longPreferencesKey("last_updated")
    private val SOURCES_HASH = intPreferencesKey("sources_hash")
    private val DISCLOSURE_ACCEPTED = booleanPreferencesKey("disclosure_accepted")

    /** When the list was last downloaded, or 0 if only the built-in list has been used. */
    fun lastUpdated(context: Context): Flow<Long> =
        context.applicationContext.dataStore.data.map { it[LAST_UPDATED] ?: 0L }

    /** Saves the update time together with which source list was used for it. */
    suspend fun setLastUpdated(context: Context, time: Long, sourcesHash: Int) {
        context.applicationContext.dataStore.edit {
            it[LAST_UPDATED] = time
            it[SOURCES_HASH] = sourcesHash
        }
    }

    /** Identifies the source list of the last update; 0 before the first multi-source update. */
    fun sourcesHash(context: Context): Flow<Int> =
        context.applicationContext.dataStore.data.map { it[SOURCES_HASH] ?: 0 }

    fun disclosureAccepted(context: Context): Flow<Boolean> =
        context.applicationContext.dataStore.data.map { it[DISCLOSURE_ACCEPTED] ?: false }

    suspend fun setDisclosureAccepted(context: Context) {
        context.applicationContext.dataStore.edit { it[DISCLOSURE_ACCEPTED] = true }
    }
}
