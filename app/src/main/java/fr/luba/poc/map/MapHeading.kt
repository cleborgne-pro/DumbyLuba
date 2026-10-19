package fr.luba.poc.map

/** Compass heading (0° north, clockwise) to map angle (0° +x, counter-clockwise). */
object MapHeading {
    fun normalize(angle: Double) = ((angle % 360.0) + 360.0) % 360.0
    fun fromCompass(compass: Double) = normalize(90.0 - compass)
    fun dockEntry(compass: Double) = normalize(fromCompass(compass) + 180.0)
    /** Old cache: compass heading + 180°, wrongly used as a Cartesian angle. */
    fun migrateDockEntry(legacy: Double) = normalize(90.0 - legacy)
}
