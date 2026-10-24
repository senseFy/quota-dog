package saien.quotadog

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An accepted handoff must have a durable receipt before any ordinary exit. */
internal class UpdateExitGuard(
    private val preflight: suspend () -> Unit,
    private val persist: suspend (UpdateReceipt) -> Unit,
) {
    private val mutex = Mutex()
    private var pending: UpdateReceipt? = null
    val needsReceipt: Boolean get() = pending != null

    suspend fun handoff(accept: suspend () -> UpdateReceipt, onExit: () -> Unit) =
        withContext(NonCancellable) {
            mutex.withLock {
                check(pending == null) { "An accepted update still needs its receipt" }
                preflight()
                pending = accept()
                persistThenExit(onExit)
            }
        }

    /** A failed retry keeps the same receipt in memory and never invokes onExit. */
    suspend fun requestExit(onExit: () -> Unit) = withContext(NonCancellable) {
        mutex.withLock { persistThenExit(onExit) }
    }

    private suspend fun persistThenExit(onExit: () -> Unit) {
        pending?.let { receipt ->
            persist(receipt)
            pending = null
        }
        onExit()
    }
}
