package fr.luba.poc.alerts

import org.junit.Assert.*
import org.junit.Test

class AlertJournalTest {
    private fun alert(id: String="robot-1", battery: Int?=5, position: String?="x=1 m, y=2 m") = RobotAlert(AlertKind.BATTERY,Severity.CRITICAL,123456,
        AlertContext(id,"Luba été","STOPPED",battery,position),"Arrêt\nVérifier",7)
    @Test fun pendingPermissionAndCooldownSurviveRestart() {
        val alert=alert()
        val key=AlertJournal.key(alert)
        val record=AlertRecord(Severity.CRITICAL,1000,6)
        val journal=AlertJournal(listOf(AlertEntry.of(alert)),mapOf(key to record),mapOf(key to alert),seenAt=99)
        val restored=AlertJournal.decode(journal.encode())
        assertEquals(journal,restored)
        assertFalse(AlertDedup.decide(restored.records[key],Severity.CRITICAL,2000).audible)
        assertEquals(alert,restored.pending[key])
    }
    @Test fun unknownContextSurvivesRestart() {
        val alert=alert(battery=null,position=null)
        val journal=AlertJournal(pending=mapOf(AlertJournal.key(alert) to alert))
        assertEquals(journal,AlertJournal.decode(journal.encode()))
    }
    @Test fun obstaclePhotoSurvivesRestartAndOlderJournalsHaveNone() {
        val alert=alert().copy(kind=AlertKind.OBSTACLE,severity=Severity.WARNING,photo="/data/obstacle-photos/1_1_-1014_0_0.jpg")
        val journal=AlertJournal(pending=mapOf(AlertJournal.key(alert) to alert))
        assertEquals(alert,AlertJournal.decode(journal.encode()).pending.values.single())
        // Journal written before photos were added: no "photo" key.
        val older=org.json.JSONObject(journal.encode()).apply { getJSONArray("pending").getJSONObject(0).remove("photo") }.toString()
        assertNull(AlertJournal.decode(older).pending.values.single().photo)
    }
    @Test fun groupingIsolatesRobotsAndIncidentKinds() {
        val a=alert();val b=alert("robot-2");val c=a.copy(kind=AlertKind.BLOCKED)
        val keys=listOf(a,b,c).map(AlertJournal::key)
        assertEquals(3,keys.toSet().size)
        val journal=AlertJournal(pending=listOf(a,b,c).associateBy(AlertJournal::key))
        assertEquals(journal,AlertJournal.decode(journal.encode()))
    }
    @Test fun allKindsAndSeveritiesArePersisted() {
        for(kind in AlertKind.entries) for(severity in Severity.entries) {
            val a=alert().copy(kind=kind,severity=severity)
            val journal=AlertJournal(pending=mapOf(AlertJournal.key(a) to a))
            assertEquals(journal,AlertJournal.decode(journal.encode()))
        }
    }
    private fun entry(at: Long, kind: AlertKind = AlertKind.TEST) = AlertEntry(kind, Severity.INFO, at, "Luba", "Événement $at")
    @Test fun entriesAreBoundedAndKeepLatestFirst() {
        // Shuffled insertion order: the journal always returns the most recent first and keeps the 50 most recent.
        val journal=AlertJournal((0L..100L).shuffled(java.util.Random(4)).map { entry(it * 1000) })
        val restored=AlertJournal.decode(journal.encode())
        assertEquals(50,restored.entries.size)
        assertEquals((100L downTo 51L).map { it * 1000 },restored.entries.map { it.at })
    }
    @Test fun sameTimeKeepsArrivalOrder() {
        val a=entry(5_000,AlertKind.BLOCKED); val b=entry(5_000,AlertKind.LIFTED)
        assertEquals(listOf(a,b),AlertJournal.latest(listOf(a,b)))
    }
    @Test fun entryWithUnknownContextSurvivesRestart() {
        val e=AlertEntry(AlertKind.BLE_LOST,Severity.CRITICAL,42,"Luba","Perte\nliaison",3)
        val e2=e.copy(state="STOPPED",battery=0,position="x=0 m")
        val journal=AlertJournal(listOf(e,e2))
        assertEquals(journal.entries.toSet(),AlertJournal.decode(journal.encode()).entries.toSet())
    }
    @Test fun legacyTextHistoryIsConverted() {
        val now=java.util.Calendar.getInstance(java.util.Locale.FRANCE).apply { set(2026,9,15,12,0,0); set(java.util.Calendar.MILLISECOND,0) }.timeInMillis
        val alert=RobotAlert(AlertKind.BATTERY,Severity.CRITICAL,java.util.Calendar.getInstance(java.util.Locale.FRANCE).apply { set(2026,9,14,9,30,5); set(java.util.Calendar.MILLISECOND,0) }.timeInMillis,
            AlertContext("r","Luba été","STOPPED",5,"x=1 m"),"Batterie 5 %\nsous le seuil",2)
        val unknown=alert.copy(context=alert.context.copy(battery=null,position=null),kind=AlertKind.TEST,severity=Severity.INFO,detail="Test")
        // December seen in October: the alert dates from the previous year.
        val december=alert.copy(at=java.util.Calendar.getInstance(java.util.Locale.FRANCE).apply { set(2026,11,31,23,0,0); set(java.util.Calendar.MILLISECOND,0) }.timeInMillis)
        val legacy=org.json.JSONObject().put("history",org.json.JSONArray(listOf(LocalNotifications.format(alert),"ligne libre",
            LocalNotifications.format(unknown),LocalNotifications.format(december)))).toString()
        val restored=AlertJournal.decode(legacy,now)
        assertEquals(3,restored.entries.size)
        assertEquals(AlertEntry.of(alert),restored.entries[0])
        assertEquals(AlertEntry.of(unknown),restored.entries[1])
        assertEquals(java.util.Calendar.getInstance(java.util.Locale.FRANCE).apply { set(2025,11,31,23,0,0); set(java.util.Calendar.MILLISECOND,0) }.timeInMillis,restored.entries[2].at)
        // Nothing new after the update: the badge stays off.
        assertEquals(alert.at,restored.seenAt)
    }
    @Test fun unseenCountsEntriesAfterLastSeen() {
        val ui=NotificationUi(entries=listOf(entry(3000),entry(2000),entry(1000)),seenAt=2000)
        assertEquals(1,ui.unseen)
        assertEquals(0,ui.copy(seenAt=3000).unseen)
    }
    @Test fun firstLaunchHasEmptyJournal() { assertEquals(AlertJournal(),AlertJournal.decode("{}")) }
    @Test(expected=Exception::class) fun corruptJournalFailsExplicitly() { AlertJournal.decode("incorrect") }
}
