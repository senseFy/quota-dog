package saien.quotadog

import java.awt.EventQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertTrue

/** Deliberately has no Compose rule: UI test rules can mask a missing Main dispatcher. */
class DesktopUpdaterRuntimeTest {
    @Test
    fun updaterMainDispatcherRunsOnTheDesktopEventThread() = runBlocking {
        withContext(Dispatchers.Main.immediate) {
            assertTrue(EventQueue.isDispatchThread())
        }
    }
}
