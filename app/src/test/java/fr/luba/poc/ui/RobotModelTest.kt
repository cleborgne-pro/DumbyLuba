package fr.luba.poc.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RobotModelTest {
    @Test fun bluetoothNamePrefixGivesModel() {
        assertEquals(RobotModel.LUBA_2, RobotModel.detect("Luba-VSDEMO02"))
        assertEquals(RobotModel.LUBA_2, RobotModel.detect("luba-vp12345"))
        assertEquals(RobotModel.LUBA_3, RobotModel.detect("Luba-VA1B2C3"))
        assertEquals(RobotModel.LUBA_MINI, RobotModel.detect("Luba-MN8F3K"))
        assertEquals(RobotModel.LUBA_MINI, RobotModel.detect("Luba-LD8F3K"))
        assertEquals(RobotModel.LUBA_MINI_2, RobotModel.detect("Luba-LA8F3K"))
        assertEquals(RobotModel.LUBA_MINI_2, RobotModel.detect("Luba-MB8F3K"))
        assertEquals(RobotModel.LUBA_MINI_2, RobotModel.detect(" Luba-MS8F3K "))
    }

    @Test fun otherLubaNamesFallBackToLuba1() {
        assertEquals(RobotModel.LUBA_1, RobotModel.detect("Luba-X7Q2B1"))
        assertEquals(RobotModel.LUBA_1, RobotModel.detect("Luba"))
    }

    @Test fun recentModelsWithoutPhotoStayGeneric() {
        val m = RobotModel.detect("Luba-HM1234")
        assertEquals(RobotModel.LUBA_OTHER, m)
        assertFalse(m.named)
    }

    @Test fun productKeyResolvesWhenNameIsNotEnough() {
        assertEquals(RobotModel.LUBA_3, RobotModel.detect("Luba-X7Q2B1", "a1Ce85210Be"))
        assertEquals(RobotModel.LUBA_2, RobotModel.detect(null, "a1iMygIwxFC"))
        assertEquals(RobotModel.LUBA_1, RobotModel.detect(null, "a1UBFdq6nNz"))
        // The name prefix wins over an inconsistent key.
        assertEquals(RobotModel.LUBA_2, RobotModel.detect("Luba-VS1", "a1Ce85210Be"))
    }

    @Test fun unknownRobotKeepsIllustration() {
        assertEquals(RobotModel.UNKNOWN, RobotModel.detect(null))
        assertEquals(RobotModel.UNKNOWN, RobotModel.detect(""))
        assertEquals(RobotModel.UNKNOWN, RobotModel.detect("Yuka-MN123"))
        assertFalse(RobotModel.UNKNOWN.named)
        assertEquals(5, RobotModel.entries.count { it.named })
    }
}
