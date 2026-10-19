package fr.luba.poc.simulation

import fr.luba.poc.map.InterruptionSummary
import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Move to a point resumed after a stop or an app restart (2026-10-16). */
class PointResumeTest {
    @get:Rule val folder = TemporaryFolder()
    private val running = PointResume("AA:BB", Point(-8.25, 3.5), 1_792_150_000_000L)

    @Test fun keptUnlessItArrived() {
        assertNull(PointResume.after(running, "PASS", "Arrivé"))
        assertEquals("Contact bumper (-1014)", PointResume.after(running, "FAILED", "Contact bumper (-1014)")!!.interruption)
        assertEquals(running.target, PointResume.after(running, "ABORTED", "STOP manuel")!!.target)
        assertNull(PointResume.after(null, "FAILED", "x"))
    }

    @Test fun survivesARestart() {
        val store = PointResumeStore(folder.root)
        store.save(running)
        // App closed during the move: save without a reason, summarised as such.
        val read = requireNotNull(PointResumeStore(folder.root).read("AA:BB"))
        assertEquals(running, read)
        assertEquals(InterruptionSummary.of(null).label, InterruptionSummary.of(read.interruption).label)
        store.save(running.copy(interruption = "Liaison perdue"))
        assertEquals("Liaison perdue", store.read("AA:BB")!!.interruption)
        assertNull(store.read("CC:DD"))
        store.delete("AA:BB")
        assertNull(store.read("AA:BB"))
    }
}
