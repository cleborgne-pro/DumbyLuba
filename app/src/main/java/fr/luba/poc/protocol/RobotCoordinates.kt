package fr.luba.poc.protocol
import fr.luba.poc.map.MapHeading

/** real_toward is a compass heading in the local frame: 0° towards +y, 90° towards +x. */
object RobotCoordinates {
    fun mapHeading(compassDegrees: Double): Double {
        require(compassDegrees.isFinite())
        return MapHeading.fromCompass(compassDegrees)
    }
}
