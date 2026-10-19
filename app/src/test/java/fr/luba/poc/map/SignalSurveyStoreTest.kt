package fr.luba.poc.map

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SignalSurveyStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private val sample=SignalSample(Point(3.0,4.0),1000,-70,null,-80,0,24)
    private val map=RobotMap(listOf(MapShape(7,0,listOf(Point(0.0,0.0),Point(10.0,0.0),Point(10.0,10.0)),"Zone été")),true)
    private fun survey(id: String, date: Long, simulated: Boolean=false)=SignalSurvey(if(simulated) "simulation" else "AA:BB",SignalCoverage.mapKey(map),listOf(7),listOf(sample),id,date,simulated,CoverageMode.SURVEY_GRID,map)

    @Test fun retainsDifferentToursOnTheSameDayAndRestoresAllSignalLayersAndMap() {
        val store=SignalSurveyStore(temp.root)
        store.save(survey("one",1000));store.save(survey("two",2000))
        val restarted=SignalSurveyStore(temp.root)
        assertEquals(listOf("two","one"),restarted.history().map { it.sessionId })
        assertEquals("two",restarted.read("AA:BB")!!.sessionId)
        val old=restarted.load("one")!!
        assertEquals(listOf(sample),old.samples)
        assertEquals(map.shapes,old.mapSnapshot!!.shapes)
        assertEquals(CoverageMode.SURVEY_GRID,old.mode)
    }
    @Test fun updatesOneSessionWithoutDuplicatingHistoryOrLosingPreviousTours() {
        val store=SignalSurveyStore(temp.root)
        val one=survey("one",1000)
        store.save(one);store.save(one.copy(samples=one.samples+sample.copy(at=2000)))
        assertEquals(1,store.history().size)
        assertEquals(2,store.history().single().samples.size)
        store.save(survey("two",3000).copy(samples=emptyList()))
        assertEquals(listOf("one"),store.history().map { it.sessionId })
        assertEquals(2,store.load("one")!!.samples.size)
    }
    @Test fun realAndSimulatedSurveysStayDistinctAfterRestart() {
        val store=SignalSurveyStore(temp.root)
        store.save(survey("real",2000));store.save(survey("fake",3000,true))
        val history=SignalSurveyStore(temp.root).history()
        assertEquals(2,history.size)
        assertTrue(history.first().simulated)
        assertFalse(history.last().simulated)
        assertEquals("real",store.read("AA:BB")!!.sessionId)
    }
    @Test fun preservesThePreviousVersionLastSurveyWhenStartingANewTour() {
        File(temp.root,"signal-tour-AA_BB.json").writeText("""{"map":"old","zones":[7],"samples":[{"x":1,"y":2,"at":1000,"wifi":-75,"ble":null,"mobile":null,"rtk":0,"gps":20}]}""")
        val store=SignalSurveyStore(temp.root)
        val old=store.read("AA:BB")!!
        assertEquals(1000L,old.startedAt)
        assertNull(old.mapSnapshot)
        store.save(survey("new",5000))
        assertEquals(2,store.history().size)
        assertEquals(old,store.load(old.sessionId))
    }
    @Test fun aDamagedArchiveDoesNotHideTheOtherSurveys() {
        val store=SignalSurveyStore(temp.root)
        store.save(survey("valid",1000))
        File(temp.root,"signal-surveys/broken.json").writeText("not json")
        assertEquals(listOf("valid"),store.history().map { it.sessionId })
        assertThrows(IllegalArgumentException::class.java) { store.load("../outside") }
    }
}
