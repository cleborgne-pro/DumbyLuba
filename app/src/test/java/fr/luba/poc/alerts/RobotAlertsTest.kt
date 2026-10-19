package fr.luba.poc.alerts

import org.junit.Assert.*
import org.junit.Test

class RobotAlertsTest {
    private val events = mutableListOf<Pair<AlertKind, Severity>>()
    private val emit: (AlertKind, Severity) -> Unit = { kind, severity -> events.add(kind to severity) }
    private fun tick(m: AlertMonitor, now: Long, last: Long = now, battery: Int? = 70, fresh: Boolean = true, moving: Boolean = false, fix: Boolean = true) =
        m.tick(now, last, battery, fresh, moving, fix, emit)
    @Test fun noAlertsBeforeTelemetry() {
        tick(AlertMonitor(), 100000, last=0, battery=2, moving=true, fix=false)
        assertTrue(events.isEmpty())
    }
    @Test fun communicationDelaysAndNoSpam() {
        val m=AlertMonitor(); m.arm()
        tick(m,4999,last=0); assertTrue(events.isEmpty())
        tick(m,5000,last=0); tick(m,6000,last=0)
        assertEquals(listOf(AlertKind.BLE_LOST to Severity.CRITICAL),events)
        tick(m,29999,last=0); assertEquals(1,events.size)
        tick(m,30000,last=0); tick(m,90000,last=0)
        assertEquals(AlertKind.OFFLINE to Severity.CRITICAL,events.last()); assertEquals(2,events.size)
    }
    @Test fun startupGapBeforePeriodicReportsIsNotABleLoss() {
        // Test of 2026-10-13: connection reply at 1.9 s, first periodic reports at 6.8 s.
        val m=AlertMonitor(); m.report()
        assertTrue(m.armed); assertFalse(m.streaming)
        tick(m,6500,last=1000); tick(m,9000,last=1000)
        assertTrue(events.isEmpty())
        m.report()
        tick(m,9500,last=9500); tick(m,14600,last=9500)
        assertEquals(listOf(AlertKind.BLE_LOST to Severity.CRITICAL),events)
    }
    @Test fun streamThatNeverStartsStillEndsOffline() {
        val m=AlertMonitor(); m.report()
        tick(m,31000,last=1000)
        assertEquals(listOf(AlertKind.OFFLINE to Severity.CRITICAL),events)
        m.disarm(); assertFalse(m.streaming)
    }
    @Test fun recoveryOnceAndNewLossRearms() {
        val m=AlertMonitor();m.arm();tick(m,30000,last=0);events.clear()
        tick(m,31000);tick(m,32000)
        assertEquals(listOf(AlertKind.RECOVERY to Severity.INFO),events)
        tick(m,37000,last=32000);assertEquals(AlertKind.BLE_LOST,events.last().first)
    }
    @Test fun intentionalStopCancelsOfflineAndRtkTimers() {
        val m=AlertMonitor();m.arm();tick(m,0,moving=true,fix=false);m.disarm()
        tick(m,90000,last=0,battery=2,moving=true,fix=false);assertTrue(events.isEmpty())
    }
    @Test fun rtkLossProlongedAndRecoveryReset() {
        val m=AlertMonitor();m.arm();tick(m,0,moving=true,fix=false);tick(m,9999,moving=true,fix=false)
        assertTrue(events.isEmpty());tick(m,10000,moving=true,fix=false);tick(m,11000,moving=true,fix=false)
        assertEquals(listOf(AlertKind.RTK_LOST to Severity.CRITICAL),events)
        tick(m,12000,moving=true);tick(m,13000,moving=true,fix=false);tick(m,23000,moving=true,fix=false)
        assertEquals(2,events.size)
    }
    @Test fun stationaryWithoutPositioningDoesNotAlertRtk() {
        val m=AlertMonitor();m.arm();tick(m,0,fix=false);tick(m,60000,fix=false);assertTrue(events.isEmpty())
    }
    @Test fun criticalBatteryEscalatesImmediatelyAndUsesHysteresis() {
        val m=AlertMonitor();m.arm();tick(m,0,battery=10);tick(m,1,battery=9);tick(m,2,battery=5);tick(m,3,battery=6);tick(m,4,battery=5)
        assertEquals(listOf(AlertKind.BATTERY to Severity.WARNING,AlertKind.BATTERY to Severity.CRITICAL),events)
        tick(m,5,battery=14);tick(m,6,battery=10);assertEquals(3,events.size)
    }
    @Test fun staleBatteryDoesNotCreateOrClearIncident() {
        val m=AlertMonitor();m.arm();tick(m,0,battery=2,fresh=false);assertTrue(events.isEmpty())
        tick(m,1,battery=5);tick(m,2,battery=90,fresh=false);tick(m,3,battery=5);assertEquals(1,events.size)
    }
    @Test fun unknownBatteryIsNotZero() {
        val m=AlertMonitor();m.arm();tick(m,0,battery=null);assertTrue(events.isEmpty())
    }
    @Test fun bumperWindowAndLatchedFault() {
        val m=AlertMonitor();m.bumper(0,false,emit);m.bumper(1,false,emit);assertTrue(events.isEmpty())
        m.bumper(2,false,emit);assertEquals(AlertKind.BUMPER to Severity.WARNING,events.last())
        m.bumper(3,false,emit);m.bumper(4,false,emit);assertEquals(AlertKind.BUMPER to Severity.CRITICAL,events.last())
        events.clear();m.bumper(40000,false,emit);assertTrue(events.isEmpty())
        m.bumper(40001,true,emit);assertEquals(AlertKind.BUMPER to Severity.CRITICAL,events.single())
    }
    @Test fun dedupSilentUntilCooldownAndEscalationImmediate() {
        val first=AlertDedup.decide(null,Severity.WARNING,1000)
        assertTrue(first.audible)
        val repeated=AlertDedup.decide(first.record,Severity.WARNING,2000)
        assertFalse(repeated.audible);assertEquals(2,repeated.record.count)
        val critical=AlertDedup.decide(repeated.record,Severity.CRITICAL,2001)
        assertTrue(critical.audible)
        val lower=AlertDedup.decide(critical.record,Severity.WARNING,2002)
        assertFalse(lower.audible)
        assertFalse(AlertDedup.decide(lower.record,Severity.CRITICAL,2003).audible)
        assertTrue(AlertDedup.decide(lower.record,Severity.CRITICAL,2001+AlertDedup.COOLDOWN_MS).audible)
    }
    @Test fun clockRollbackDoesNotSuppressForever() {
        assertTrue(AlertDedup.decide(AlertRecord(Severity.CRITICAL,10000,3),Severity.WARNING,1000).audible)
    }
    @Test fun everyIncidentHasActionAndDistinctTitle() {
        assertEquals(18,AlertKind.entries.size)
        assertEquals(18,AlertKind.entries.map { it.title }.toSet().size)
        assertTrue(AlertKind.entries.all { it.action.isNotBlank() })
    }
    @Test fun formattedAlertContainsContextAndUnknownFields() {
        val context=AlertContext("id","Luba","READY",5,"x=1 m, y=2 m · dernier FIX")
        val body=LocalNotifications.format(RobotAlert(AlertKind.BLOCKED,Severity.CRITICAL,0,context,"Arrêt demandé",3))
        listOf("CRITICAL","Robot bloqué","Luba","READY","5 %","x=1 m","3 événement","Action :","01/01").forEach { assertTrue(it,body.contains(it)) }
        val unknown=LocalNotifications.format(RobotAlert(AlertKind.OFFLINE,Severity.WARNING,0,context.copy(battery=null,position=null),"Sans télémétrie"))
        assertTrue(unknown.contains("Batterie : inconnue"));assertTrue(unknown.contains("Position : inconnue"))
    }
    @Test fun allReturnStagesSurfaceReturnFailure() {
        listOf(fr.luba.poc.simulation.RoutePhase.RETURNING,fr.luba.poc.simulation.RoutePhase.ALIGNING,fr.luba.poc.simulation.RoutePhase.DOCKING).forEach {
            assertEquals(AlertKind.RETURN_FAILED,RouteAlertPolicy.failure(it))
        }
    }
    @Test fun chargingAndDepartureFailuresSurfaceResumeFailure() {
        listOf(fr.luba.poc.simulation.RoutePhase.CHARGING,fr.luba.poc.simulation.RoutePhase.RESUMING).forEach {
            assertEquals(AlertKind.RESUME_FAILED,RouteAlertPolicy.failure(it))
        }
    }
    @Test fun ordinaryAndCompletedPhasesDoNotInventReturnFailures() {
        listOf(fr.luba.poc.simulation.RoutePhase.FOLLOWING,fr.luba.poc.simulation.RoutePhase.COMPLETE,fr.luba.poc.simulation.RoutePhase.STOPPED).forEach {
            assertNull(RouteAlertPolicy.failure(it))
        }
    }

}
