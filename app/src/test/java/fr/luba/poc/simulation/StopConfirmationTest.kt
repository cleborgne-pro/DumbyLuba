package fr.luba.poc.simulation

import fr.luba.poc.map.Point
import org.junit.Assert.*
import org.junit.Test

class StopConfirmationTest {
    /**
     * Battery return of 2026-10-10 (15:27): STOP at 143,690 ms, robot at 0.32 m/s, positions at 1 Hz
     * (144,613: still 25 cm of sliding since the previous one, then 145,613, 146,613). The old
     * check (new position required 600 ms later) failed at 145,302 without a real fault.
     */
    @Test fun `stop confirmed by two positions published after the stop takes effect`() {
        val stop = StopConfirmation(143_690)
        // Position older than the effect of the stop (0.6 s delay + deceleration): ignored.
        assertNull(stop.observe(144_613, 144_613, Point(-13.448, -3.668)))
        assertNull(stop.observe(145_302, 144_613, Point(-13.448, -3.668)))
        // Robot still sliding: new reference, wait.
        assertNull(stop.observe(145_613, 145_613, Point(-13.49, -3.74)))
        assertNull(stop.observe(146_000, 145_613, Point(-13.49, -3.74)))
        // Stationary within 1 cm (RTK noise): stop confirmed.
        assertEquals(true, stop.observe(146_613, 146_613, Point(-13.495, -3.748)))
    }

    @Test fun `robot still moving after the delay refuses the stop`() {
        val stop = StopConfirmation(0)
        var result: Boolean? = null
        var at = 1_000L
        var x = 0.0
        while (result == null) { x += 0.2; result = stop.observe(at, at, Point(x, 0.0)); at += 1_000 }
        assertEquals(false, result)
        assertEquals(0.2, stop.lastMoveM, 1e-9)
    }
}
