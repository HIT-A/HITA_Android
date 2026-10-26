package cn.limpu.hita.data.source.preference

import android.content.SharedPreferences
import cn.limpu.hita.data.model.eas.EasQueryScope
import cn.limpu.hita.data.model.eas.EASToken
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Shared by Hilt and manually constructed repositories using the same preference file. */
internal class EasQuerySessionState private constructor(
    val storage: SharedPreferences,
    initialToken: EASToken,
) {
    val lock = Any()
    val authEpoch = AtomicLong(initialToken.sessionGeneration)
    @Volatile var acceptServiceRefresh = initialToken.isLogin()
    private val current = AtomicReference(EasQueryScope.from(initialToken))
    private val updates = MutableStateFlow(current.get())
    val changes: StateFlow<EasQueryScope> get() = updates
    fun current(): EasQueryScope = current.get()

    fun publish(token: EASToken) {
        if (token.sessionGeneration > 0L) authEpoch.set(token.sessionGeneration)
        acceptServiceRefresh = token.isLogin()
        val snapshot = EasQueryScope.from(token)
        current.set(snapshot)
        updates.value = snapshot
    }

    companion object {
        private val states = mutableMapOf<SharedPreferences, EasQuerySessionState>()
        fun forPreferences(storage: SharedPreferences, initial: () -> EASToken): EasQuerySessionState =
            synchronized(states) { states.getOrPut(storage) { EasQuerySessionState(storage, initial()) } }
    }
}
