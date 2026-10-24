package saien.quotadog

import java.awt.desktop.QuitResponse
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateExitGuardTest {
    private val receipt = UpdateReceipt(UUID.randomUUID().toString(), 24, "/Applications/QuotaDog.app")

    @Test
    fun writeFailureAfterAcceptanceBlocksEveryQuitUntilTheSameReceiptIsSaved() = runBlocking {
        withStore { store ->
            var writable = false
            var handoffs = 0
            var exits = 0
            val guard = UpdateExitGuard(store::checkWritable) {
                if (!writable) throw IOException("Injected disk-full failure after helper acceptance")
                store.write(it)
            }
            assertFailsWith<IOException> {
                guard.handoff({ handoffs++; receipt }) { exits++ }
            }
            repeat(2) {
                assertFailsWith<IOException> { guard.requestExit { exits++ } }
                assertEquals(0, exits)
                assertTrue(guard.needsReceipt)
            }
            writable = true
            guard.requestExit {
                // This is the exact boundary at which the host is allowed to exit.
                assertEquals(receipt, store.read())
                exits++
            }
            assertEquals(1, handoffs)
            assertEquals(1, exits)
            assertFalse(guard.needsReceipt)
        }
    }

    @Test
    fun failedPreflightNeverStartsTheHelperAndDoesNotTrapOrdinaryQuit() = runBlocking {
        var handedOff = false
        var exited = false
        val guard = UpdateExitGuard({ throw IOException("No space") }, { error("must not write") })
        assertFailsWith<IOException> {
            guard.handoff({ handedOff = true; receipt }) { exited = true }
        }
        assertFalse(handedOff)
        assertFalse(exited)
        guard.requestExit { exited = true }
        assertTrue(exited)
    }

    @Test
    fun cancellationAfterAcceptanceCannotSkipReceiptPersistence() = runBlocking {
        withStore { store ->
            val accepted = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            var exited = false
            val guard = UpdateExitGuard(store::checkWritable) {
                allowWrite.await()
                store.write(it)
            }
            val install = launch {
                guard.handoff({ accepted.complete(Unit); receipt }) {
                    assertEquals(receipt, store.read())
                    exited = true
                }
            }
            accepted.await()
            install.cancel()
            assertFalse(exited)
            allowWrite.complete(Unit)
            install.join()
            assertTrue(exited)
            assertEquals(receipt, store.read())
        }
    }

    @Test
    fun existingRecoveryReceiptIsNotOverwrittenByPreflight() = runBlocking {
        withStore { store ->
            store.write(receipt)
            assertFailsWith<IllegalStateException> { store.checkWritable() }
            assertEquals(receipt, store.read())
        }
    }

    @Test
    fun systemQuitCancelsDirectTerminationBeforeInvokingTheGuard() {
        val events = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val delivered = CountDownLatch(1)
        val handler = guardedQuitHandler { events.add("guard"); delivered.countDown() }
        handler.handleQuitRequestWith(null, object : QuitResponse {
            override fun cancelQuit() { events.add("cancel-system-exit") }
            override fun performQuit() { error("must not bypass the updater") }
        })
        assertTrue(delivered.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("cancel-system-exit", "guard"), events.toList())
    }

    private suspend fun withStore(block: suspend (UpdateReceiptStore) -> Unit) {
        val directory = Files.createTempDirectory("quotadog-exit-guard-")
        try { block(UpdateReceiptStore(directory)) }
        finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder<Path>()).forEach(Files::delete) } }
    }
}
