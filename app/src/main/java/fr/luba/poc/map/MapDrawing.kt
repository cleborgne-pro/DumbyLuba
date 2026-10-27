package fr.luba.poc.map

import kotlin.math.cos
import kotlin.math.sin

/**
 * Mammotion drawing (SVG pattern, map element type 13) read with the robot map ([MapTransfer], `svg_message_t`): a tile
 * of [widthM] × [heightM] meters centered on ([centerX], [centerY]), in the frame of the boundary points, turned by
 * [rotationRad] (counterclockwise positive), inside the zone [zoneHash] (`paternalHashA`). The SVG is stretched over
 * the tile (viewBox `[vbx, vby, vbw, vbh]`, no aspect ratio kept) and its Y axis, pointing down, is flipped:
 *
 *     a = ((u − vbx) / vbw − 0.5) · W      b = (0.5 − (v − vby) / vbh) · H
 *     x = centerX + a·cos θ − b·sin θ      y = centerY + a·sin θ + b·cos θ
 *
 * This placement comes from two community implementations. **Confirmed on a real map on 2026-10-27** (a text added in
 * the Mammotion app, drawn correctly in its zone): centering on `x_move`/`y_move`, size from `base_width_m`/
 * `base_height_m` with `scale` = 1, counterclockwise rotation (`rotate` ≈ −0.39 rad) and Y flip. **Still to be
 * confirmed on a capture**: the meaning of a `scale` other than 1 (and of 0), the stretching of a viewBox whose aspect
 * ratio differs from the tile, the pixel sizes (0 on that map).
 *
 * [hidden] (`hide_svg`): pattern switched off, the robot mows the zone normally; active, the robot spares the grass of
 * the pattern. Display only: the local planning and the moves ignore the drawings. [svg]: SVG text, or something else
 * (built-in pattern name…) that gives no outline; only the footprint is drawn then.
 */
data class MapDrawing(
    val hash: Long,
    val zoneHash: Long,
    val centerX: Double,
    val centerY: Double,
    val widthM: Double,
    val heightM: Double,
    val rotationRad: Double,
    val hidden: Boolean,
    val fileName: String = "",
    val svg: String = "",
    /** Pixel size of the drawing (`base_width_pix` / `base_height_pix`), frame of an SVG without viewBox nor size. */
    val basePixelWidth: Int = 0,
    val basePixelHeight: Int = 0,
) {
    /** Placement usable on the map: finite values, size within (0, [MAX_SIZE_M]]. */
    val valid: Boolean get() = centerX.isFinite() && centerY.isFinite() && rotationRad.isFinite() &&
        widthM.isFinite() && heightM.isFinite() && widthM > 0 && heightM > 0 && widthM <= MAX_SIZE_M && heightM <= MAX_SIZE_M

    /** Map point at ([a], [b]) meters from the tile center along the tile axes (a to the right, b up, before rotation). */
    fun tilePoint(a: Double, b: Double): Point {
        val c = cos(rotationRad); val s = sin(rotationRad)
        return Point(centerX + a * c - b * s, centerY + a * s + b * c)
    }

    /** SVG frame [box] → map meters. */
    fun transform(box: ViewBox): Affine {
        val c = cos(rotationRad); val s = sin(rotationRad)
        // a = ka·u + a0 ; b = kb·v + b0
        val ka = widthM / box.width; val a0 = (-box.x / box.width - 0.5) * widthM
        val kb = -heightM / box.height; val b0 = (0.5 + box.y / box.height) * heightM
        return Affine(a = ka * c, b = ka * s, c = -kb * s, d = kb * c, e = centerX + a0 * c - b0 * s, f = centerY + a0 * s + b0 * c)
    }

    /** Tile corners (closed rectangle, 4 points), drawn even without outline. */
    val footprint: List<Point> by lazy {
        val w = widthM / 2; val h = heightM / 2
        listOf(tilePoint(-w, -h), tilePoint(w, -h), tilePoint(w, h), tilePoint(-w, h))
    }

    /** SVG outlines in map coordinates, read once ([SvgOutline]); empty when the SVG cannot be read. */
    val outline: DrawingOutline by lazy {
        if (!valid) DrawingOutline() else SvgOutline.parse(svg, basePixelWidth, basePixelHeight, ::transform)
    }

    companion object {
        /** Largest tile side accepted (meters). */
        const val MAX_SIZE_M = 500.0

        /**
         * Drawing from the `svg_message_t` fields: tile size = base size × [scale], a [scale] of 0 or less (unset)
         * meaning 1. Null when the placement is unusable ([valid]).
         */
        fun of(hash: Long, zoneHash: Long, xMove: Double, yMove: Double, scale: Double, rotate: Double, baseWidthM: Double,
               baseHeightM: Double, basePixelWidth: Int, basePixelHeight: Int, fileName: String, svg: String, hidden: Boolean): MapDrawing? {
            val s = if (scale > 0 && scale.isFinite()) scale else 1.0
            return MapDrawing(hash, zoneHash, xMove, yMove, baseWidthM * s, baseHeightM * s, rotate, hidden, fileName, svg,
                basePixelWidth, basePixelHeight).takeIf { it.valid }
        }
    }
}
