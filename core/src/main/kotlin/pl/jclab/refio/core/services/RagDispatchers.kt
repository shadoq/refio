package pl.jclab.refio.core.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated, bounded thread pool for background RAG work (project indexing + embedding generation).
 *
 * Background indexing fires blocking embedding calls and SQLite writes that can each occupy a
 * thread for tens of seconds. Running them on the shared [kotlinx.coroutines.Dispatchers.IO]
 * pool means RAG competes for the same threads as the interactive turn loop's read-only tools
 * (read_file, tasks run via withContext(Dispatchers.IO)). Giving RAG its own small pool removes
 * that whole class of contention: no matter how slow indexing gets, the interactive IO pool stays
 * free for the agent turn.
 *
 * Threads are daemon so the pool never blocks JVM shutdown. Process-lifetime singleton (not closed).
 */
object RagDispatchers {

    /** Max concurrent background RAG threads. RAG is secondary work; a small slice is enough. */
    const val RAG_MAX_THREADS = 4

    private val threadCounter = AtomicInteger(0)

    val background: CoroutineDispatcher = Executors.newFixedThreadPool(RAG_MAX_THREADS) { runnable ->
        Thread(runnable, "refio-rag-${threadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
}
