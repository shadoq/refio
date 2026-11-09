package pl.jclab.refio.cli.tui

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/** About 30 frames per second: enough for streaming text, cheap enough for slow terminals. */
internal const val FRAME_INTERVAL_MS = 33L

/**
 * Passes on at most one value per [intervalMs]. Values arriving in between are dropped except the newest,
 * which is delivered once the interval has passed, so the screen always ends up showing the latest state.
 */
internal fun <T> Flow<T>.throttleLatest(intervalMs: Long): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(intervalMs)
    }
