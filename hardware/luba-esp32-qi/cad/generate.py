#!/usr/bin/env python3
"""ESP32 enclosure + Qi charging for the LUBA 2 AWD — parametric model.

Generates:
  - stl/*.stl            parts to print, already oriented for printing;
  - viewer/luba-esp32-qi.html  self-contained 3D scene (embedded GLB) with a docking animation;
  - a report of dimensions and clearances on standard output (and blocking checks).

Principle: the enclosure is VHB-bonded to the robot's deck; the Qi transmitter sits in a FIXED
mount, hung under the garage roof by two M6 rods and adjusted to the millimeter (one turn of a nut
= 1 mm) so that it stops just above the lid when the robot is docked. Nothing moves.

World frame: x towards the front of the robot, y to the left, z up, in mm.
The LUBA 2 docks in REVERSE: the station tower is on the negative x side.
The robot, station and garage dimensions are approximate: measure them on site
and adjust the parameters marked "TO MEASURE".

Usage: python3 -I generate.py   (from any directory)
"""
from __future__ import annotations

import base64
import math
import sys
from pathlib import Path

import manifold3d as mf
import numpy as np
import trimesh

M = mf.Manifold
CS = mf.CrossSection
ROOT = Path(__file__).resolve().parent.parent
SEG = 64

# --------------------------------------------------------------------------------------
# Parameters
# --------------------------------------------------------------------------------------
# Electronics: Olimex ESP32-S3-DevKit-Lipo-EA (Rev B) held in a printed cradle, protected 18650 in
# a separate holder with leads and a JST plug, flexible 2.4 GHz antenna on a u.FL cable, USB-C Qi
# receiver of the "phone patch" type.
BOARD_L, BOARD_W, PCB_T = 55.88, 27.94, 1.6       # PCB from the Rev B gerbers, no mounting holes
COMP_TOP, COMP_BOT = 4.0, 2.0                     # component height above / below the PCB: not documented, conservative
COMP_EDGE = 1.5                                   # strip along the long edges kept free of components (header rows)
MODULE = (18.0, 19.2, 3.2)                        # ESP32-S3-WROOM-1U (x, y, z), flush with the -y end of the board
UFL_X = 4.0                                       # u.FL cable exit, x from the board axis: corner not documented,
UFL_CLEAR = 3.0                                   # so the whole module top is kept clear by this much
USB_DX = 7.0                                      # the two USB-C sockets at the +y end, at ±USB_DX from the axis — TO CHECK
USB_PLUG_L = 22.0                                 # rigid length of a straight USB-C cable plug (flashing, lid open)
BAT_JST_Y = 22.0                                  # 2-pin 2.0 mm battery socket on the +x long edge, from the -y end — TO CHECK
# Board cradle printed with the base: 4 corner pillars (ledge under the PCB + side wall), end stops
# on the module side, one snap arm per long side. The headers are not soldered.
CRADLE_GAP, POST_T, POST_LEN, LEDGE, WALL_GAP = 0.25, 1.6, 6.0, 2.0, 0.8
SNAP_T, SNAP_W, SNAP_HOOK, SNAP_Y = 1.2, 5.0, 0.6, 14.0   # SNAP_Y: arm axis from the +y (USB) end
# 1 × 18650 holder with leads and JST plug: envelope including the cell (conservative), on a printed plinth
HOLDER_L, HOLDER_W, HOLDER_H = 78.0, 21.0, 20.0
CELL_D, CELL_L = 18.6, 70.0                        # protected cell (the board has no battery protection)
# Flexible (FPC) 2.4 GHz antenna with a u.FL cable, bonded to the inside of the -y end wall
ANT_L, ANT_W, ANT_T = 45.0, 10.0, 0.3
ANT_Z0 = 6.0                                       # bottom edge of the antenna above the underside of the base
ANT_CABLE = (100.0, 150.0)                         # cable lengths sold
ANT_CLEAR_MIN = 20.0                               # minimum distance to the Qi patch (ferrite) and to the cell
FOAM_T, FOAM_SQUEEZE = 3.0, 0.1                    # EVA foam pressing the patch against the window
RX_PAD_L, RX_PAD_W, RX_PAD_T = 70.0, 43.0, 1.2    # receiver coil area
# Adafruit 2162 "Universal Qi Wireless Charging Transmitter"
TX_COIL = (53.0, 53.0, 4.0)
TX_PCB = (55.0, 37.0, 6.0)

# Enclosure (local frame: origin at the center of the underside, x = direction of travel, y = across)
# usable inner volume of the base. IN_X = cradle + board + holder side by side (50 mm for the
# T-Energy S3 the enclosure was first drawn for); IN_Y keeps the antenna wall far from the Qi patch.
IN_X, IN_Y, IN_H = 56.0, 110.0, 26.0
R_IN = 4.0
WALL = 3.0
FLOOR = 3.0                            # flat bottom, VHB-bonded to the deck
FLANGE = 8.0                           # flange overhang beyond the wall
FLANGE_T = 6.0
BASE_H = FLOOR + IN_H                  # 29
LID_T = 3.2
WINDOW = (46.0, 94.0, 2.0)             # thinned window under the lid (x, y, depth)
# Solid silicone cord seal Ø2.4 mm in a groove of the flange: the groove must be wider and
# shallower than the cord (20–30% squeeze) without being too full (70–90% of the cross-section
# filled), otherwise the incompressible silicone prevents the lid from closing.
CORD_D = 2.4
GROOVE_C, GROOVE_W, GROOVE_D = 2.6, 3.0, 1.8   # centerline from the inner face, width, depth
SCREW_OFF = 7.0                        # screw axis from the inner face (outside the seal)
SCREW_L = 12.0                         # M3 × 12 countersunk screw
NUT_M3_FLATS, NUT_M3_H = 5.5, 2.4
# Drip lip: the lid overhangs the flange and drops in front of the seal, so rain and
# sprinkler water do not run directly onto the closing line.
LIP_GAP, LIP_T, LIP_H = 0.5, 1.6, 3.0
VHB_T = 1.1                            # thickness of the VHB tape under the enclosure
# Deck crown under the enclosure — TO MEASURE. 0 = flat deck: the flat bottom is bonded directly.
# Otherwise: lay a ruler across the deck where the enclosure goes, measure the sag h (mm)
# at the middle of a chord c (mm); DECK_R = c² / (8 h) + h / 2. A printed shim takes up the crown.
DECK_R = 0.0
DECK_CROWN_Y = 0.0                     # y of the top of the crown (robot axis)
SOLE_MIN = 1.5                         # minimum shim thickness
# Adhesive ePTFE vent on the rear wall (-x), below the flange chamfer so that it opens freely
VENT_Y, VENT_Z, VENT_R, VENT_PATCH_R = 40.0, 8.5, 2.0, 6.0

# Fixed transmitter mount (local frame: origin at the center of the underside)
SUP_X, SUP_Y, SUP_H, SUP_CAP_T = 76.0, 104.0, 30.0, 3.0
SUP_LEAD = 5.0                         # 45° lead-in chamfer on the lower front/rear edges
SUP_CAV = 60.0
SUP_WIN = 1.2                          # mount floor under the coil
ROD_Y = 42.0                           # M6 rods at y = ±ROD_Y (outside the robot path)
AIR_GAP = 2.5                          # nominal lid / mount gap, robot docked (to adjust on site)
QI_RANGE = (2.0, 8.0)                  # stated range of the Adafruit 2162 transmitter
ADJ_MIN = 8.0                          # minimum required adjustment travel, up and down
GLAND_Y, GLAND_Z = 13.0, 19.0          # cable gland on the rear side (-x) of the mount
EXPLODE_LIFT = 170.0                   # exploded view: mount and bridge raised by this much

# Bridge bonded under the garage roof (local frame: origin under the roof, z negative downwards)
BR_X, BR_Y, BR_PLATE, BR_GAP, BR_BAR, BR_BOSS = 120.0, 56.0, 4.0, 30.0, 6.0, 10.0

# Placement on the robot and the station — TO MEASURE
DECK_Z = 228.0       # height of the top of the deck behind the camera
CAM_X0 = 255.0       # rear of the camera module (robot frame, robot center = 0)
CAM_GAP = 20.0       # gap between the rear of the camera and the front of the mount, robot docked
BOX_Y = -35.0        # lateral offset, on the side opposite the rain sensor
ROOF_Z = 375.0       # underside of the garage roof, robot docked

# Hardware
NUT_M6_H, WASHER_M6_T = 5.2, 1.6
ROD_TOP_MARGIN = 2.0

# --------------------------------------------------------------------------------------
# Primitives
# --------------------------------------------------------------------------------------

def rr(x: float, y: float, r: float) -> CS:
    """Centered rounded rectangle."""
    r = min(r, x / 2 - 0.01, y / 2 - 0.01)
    return CS.square((x - 2 * r, y - 2 * r), True).offset(r, mf.JoinType.Round, 2.0, SEG)


def ext(cs: CS, z0: float, z1: float) -> M:
    return M.extrude(cs, z1 - z0).translate((0, 0, z0))


def box(x0, x1, y0, y1, z0, z1) -> M:
    return M.cube((x1 - x0, y1 - y0, z1 - z0)).translate((x0, y0, z0))


def cyl(r: float, z0: float, z1: float, x=0.0, y=0.0, seg=SEG) -> M:
    return M.cylinder(z1 - z0, r, r, seg).translate((x, y, z0))


def hexprism(flats: float, z0: float, z1: float, x=0.0, y=0.0) -> M:
    rc = flats / math.sqrt(3)
    return M.cylinder(z1 - z0, rc, rc, 6).translate((x, y, z0))


def cyl_x(r, x0, x1, y, z, seg=SEG) -> M:
    return M.cylinder(x1 - x0, r, r, seg).rotate((0, 90, 0)).translate((x0, y, z))


def cyl_y(r, y0, y1, x, z, seg=SEG) -> M:
    return M.cylinder(y1 - y0, r, r, seg).rotate((-90, 0, 0)).translate((x, y0, z))


def profile_xz(points, width_y: float) -> M:
    """(x, z) profile extruded over width y, centered in y."""
    return M.extrude(CS([points]), width_y).rotate((90, 0, 0)).translate((0, width_y / 2, 0))


def union(*parts: M) -> M:
    return M.batch_boolean(list(parts), mf.OpType.Add)


def check(m: M, name: str) -> M:
    if m.status() != mf.Error.NoError or m.is_empty():
        raise SystemExit(f"Invalid geometry: {name} ({m.status()})")
    return m


# --------------------------------------------------------------------------------------
# Printed parts
# --------------------------------------------------------------------------------------
BODY_X, BODY_Y = IN_X + 2 * WALL, IN_Y + 2 * WALL
FL_X, FL_Y = BODY_X + 2 * FLANGE, BODY_Y + 2 * FLANGE
R_BODY, R_FL = R_IN + WALL, R_IN + WALL + FLANGE
LID_X, LID_Y = FL_X + 2 * (LIP_GAP + LIP_T), FL_Y + 2 * (LIP_GAP + LIP_T)
R_LID = R_FL + LIP_GAP + LIP_T
FL_Z0 = BASE_H - FLANGE_T              # underside of the flange

# Layout of the electronics (enclosure frame). The patch sits in the thinned window, the squeezed
# foam under it; the top of the holder and the component envelope of the board are at the
# underside of the foam, so the foam presses the patch over its whole length.
PAD_Z1 = BASE_H + WINDOW[2]
PAD_Z0 = PAD_Z1 - RX_PAD_T
FOAM_Z0 = PAD_Z0 - FOAM_T * (1 - FOAM_SQUEEZE)
PCB_Z1 = FOAM_Z0 - COMP_TOP
PCB_Z0 = PCB_Z1 - PCB_T
BX0 = -IN_X / 2 + WALL_GAP + POST_T + CRADLE_GAP   # board on the -x side, module towards -y
BX1 = BX0 + BOARD_W
BXC = (BX0 + BX1) / 2
BY1 = IN_Y / 2 - USB_PLUG_L - 1.0                  # USB end: room for a straight flashing plug
BY0 = BY1 - BOARD_L
MOD_Z1 = PCB_Z1 + MODULE[2]
USB_Z = PCB_Z1 + 1.65                              # axis of the USB-C sockets
HX1 = IN_X / 2 - 0.6                               # holder on the +x side, pushed towards +y (away from the antenna)
HX0 = HX1 - HOLDER_W
HXC = (HX0 + HX1) / 2
HY1 = IN_Y / 2 - 2.5
HY0 = HY1 - HOLDER_L
HZ0 = FOAM_Z0 - HOLDER_H                           # top of the plinth
JST_Y = BY0 + BAT_JST_Y
ANT_Y0 = -IN_Y / 2 + 0.02                          # antenna bonded to the -y end wall
ANT_FEED = (-ANT_L / 2 + 2.0, ANT_Y0 + ANT_T + 0.6, ANT_Z0 + ANT_W / 2)


def tube(points, r: float, seg: int = 16) -> M:
    """Cable along a polyline (capsules)."""
    balls = [M.sphere(r, seg).translate(p) for p in points]
    return union(*[M.batch_hull([a, b]) for a, b in zip(balls, balls[1:])])


def path_length(points) -> float:
    return sum(math.dist(a, b) for a, b in zip(points, points[1:]))


def prism_xz(points, y0: float, y1: float) -> M:
    """(x, z) profile extruded from y0 to y1 (any winding)."""
    area = sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(points, points[1:] + points[:1]))
    return profile_xz(points if area > 0 else points[::-1], y1 - y0).translate((0, (y0 + y1) / 2, 0))


def make_cradle() -> list[M]:
    """Board cradle and holder plinth, printed with the base (no supports: no overhang > 0.6 mm).

    The PCB has no mounting holes: it rests on the ledges of 4 corner pillars, is located by their
    side walls and by two end stops on the module side, and is held down by one snap arm per long
    side. The USB end stays open for the plugs.
    """
    parts = []
    for side in (-1, 1):
        edge = BX0 if side < 0 else BX1
        wall_in = edge + side * CRADLE_GAP                  # inner face of the side walls
        wall_out = wall_in + side * POST_T
        ledge_in = edge - side * LEDGE
        xs = sorted((wall_out, ledge_in))
        xw = sorted((wall_out, wall_in))
        for (y0, y1) in ((BY0, BY0 + POST_LEN), (BY1 - POST_LEN, BY1)):
            parts.append(box(xs[0], xs[1], y0, y1, FLOOR - 0.01, PCB_Z0))
            parts.append(box(xw[0], xw[1], y0, y1, FLOOR - 0.01, PCB_Z1 + 0.6))
        # end stop on the module side, outside the module footprint, up to the top of the PCB
        parts.append(box(xs[0], xs[1], BY0 - CRADLE_GAP - POST_T, BY0 - CRADLE_GAP, FLOOR - 0.01, PCB_Z1))
        # snap arm: free from the floor up, hook chamfered on top (the board clips in from above)
        ys = BY1 - SNAP_Y
        xa = sorted((wall_in, wall_in + side * SNAP_T))
        parts.append(box(xa[0], xa[1], ys - SNAP_W / 2, ys + SNAP_W / 2, FLOOR - 0.01, PCB_Z1 + 0.1))
        zh = PCB_Z1 + 0.1
        hook = [(wall_in + side * SNAP_T, zh), (wall_in - side * SNAP_HOOK, zh), (wall_in, zh + 1.2),
                (wall_in + side * SNAP_T, zh + 1.2)]
        parts.append(prism_xz(hook, ys - SNAP_W / 2, ys + SNAP_W / 2))
    # plinth under the battery holder, with a low rim on the board side (the +x wall locates the other side)
    parts.append(box(HX0 - 1.6, HX1 + 0.6, HY0 - 0.4, HY1 + 0.4, FLOOR - 0.01, HZ0))
    parts.append(box(HX0 - 1.6, HX0 - 0.4, HY0 - 0.4, HY1 + 0.4, HZ0 - 0.01, HZ0 + 1.5))
    return parts


def groove_ring(width: float) -> CS:
    """Ring centered on the groove centerline, with the given width."""
    c = GROOVE_C
    return (rr(IN_X + 2 * (c + width / 2), IN_Y + 2 * (c + width / 2), R_IN + c + width / 2)
            - rr(IN_X + 2 * (c - width / 2), IN_Y + 2 * (c - width / 2), R_IN + c - width / 2))


def screw_points():
    sx = IN_X / 2 + SCREW_OFF
    sy = IN_Y / 2 + SCREW_OFF
    return [(sx, 35.0), (sx, -35.0), (-sx, 35.0), (-sx, -35.0), (0.0, sy), (0.0, -sy)]


def make_base() -> M:
    fl_z0 = FL_Z0
    body = ext(rr(BODY_X, BODY_Y, R_BODY), 0, BASE_H)
    skirt = M.batch_hull([
        ext(rr(BODY_X, BODY_Y, R_BODY), fl_z0 - FLANGE, fl_z0 - FLANGE + 0.01),
        ext(rr(FL_X, FL_Y, R_FL), fl_z0, fl_z0 + 0.01),
    ])
    flange = ext(rr(FL_X, FL_Y, R_FL), fl_z0, BASE_H)
    solid = union(body, skirt, flange)

    cuts = [ext(rr(IN_X, IN_Y, R_IN), FLOOR, BASE_H + 1)]
    cuts.append(ext(groove_ring(GROOVE_W), BASE_H - GROOVE_D, BASE_H + 1))
    for (x, y) in screw_points():
        cuts.append(cyl(1.7, fl_z0 - 1, BASE_H + 1, x, y, 32))
        hx = hexprism(5.7, fl_z0 - FLANGE - 2, fl_z0 + 2.6, 0, 0)
        # flat of the hex facing outwards
        ang = 30.0 if abs(x) > 1 else 0.0
        cuts.append(hx.rotate((0, 0, ang)).translate((x, y, 0)))
    # vent for the adhesive membrane (rear wall -x)
    cuts.append(cyl_x(VENT_R, -BODY_X / 2 - 1, -IN_X / 2 + 1, VENT_Y, VENT_Z, 32))
    base = solid - union(*cuts)

    return check(union(base, *make_cradle()), "base")


def make_lid() -> M:
    """Lid in assembled position (z = 0 on the flange), drip lip pointing down."""
    ch = 2.0
    lid = M.batch_hull([
        ext(rr(LID_X, LID_Y, R_LID), 0, LID_T - ch),
        ext(rr(LID_X - 2 * ch, LID_Y - 2 * ch, R_LID - ch), LID_T - 0.01, LID_T),
    ])
    lip = ext(rr(LID_X, LID_Y, R_LID) - rr(FL_X + 2 * LIP_GAP, FL_Y + 2 * LIP_GAP, R_FL + LIP_GAP), -LIP_H, 0.01)
    lid = union(lid, lip)
    cuts = [ext(rr(WINDOW[0], WINDOW[1], 3), -1, WINDOW[2])]
    for (x, y) in screw_points():
        cuts.append(cyl(1.7, -1, LID_T + 1, x, y, 32))
        cuts.append(M.cylinder(1.9, 1.7, 3.6, 32).translate((x, y, LID_T - 1.9)))
        cuts.append(cyl(3.6, LID_T - 0.01, LID_T + 1, x, y, 32))
    # engraved marks outside the window: corner brackets at the coil corners and an arrow pointing forward
    g = 0.4
    wx, wy = WINDOW[0] / 2 + 3, WINDOW[1] / 2 + 3
    for sx in (-1, 1):
        for sy in (-1, 1):
            cuts.append(box(min(sx * wx, sx * (wx + 6)), max(sx * wx, sx * (wx + 6)),
                            min(sy * wy, sy * (wy + 1)), max(sy * wy, sy * (wy + 1)), LID_T - g, LID_T + 1))
            cuts.append(box(min(sx * wx, sx * (wx + 1)), max(sx * wx, sx * (wx + 1)),
                            min(sy * wy, sy * (wy + 6)), max(sy * wy, sy * (wy + 6)), LID_T - g, LID_T + 1))
    arrow = CS([[(FL_X / 2 - 6, 0), (FL_X / 2 - 12, 5), (FL_X / 2 - 12, -5)]])
    cuts.append(ext(arrow, LID_T - g, LID_T + 1))
    return check(lid - union(*cuts), "lid")


def deck_drop(y_world: float) -> float:
    """Drop of the deck at the given y relative to its top (0 for a flat deck)."""
    if DECK_R <= 0:
        return 0.0
    d = abs(y_world - DECK_CROWN_Y)
    if d >= DECK_R:
        raise SystemExit("DECK_R smaller than half the enclosure width")
    return DECK_R - math.sqrt(DECK_R ** 2 - d ** 2)


def sole_span() -> tuple[float, float]:
    """Minimum and maximum drop of the deck under the enclosure footprint."""
    y0, y1 = BOX_Y - BODY_Y / 2, BOX_Y + BODY_Y / 2
    ys = [y0, y1] + ([DECK_CROWN_Y] if y0 < DECK_CROWN_Y < y1 else [])
    drops = [deck_drop(y) for y in ys]
    return min(drops), max(drops)


def make_sole() -> M | None:
    """Shim matching the deck crown (enclosure frame, flat top at z = 0, pointing down).

    Printed flat face on the bed (curved face up, no supports), VHB-bonded between the
    enclosure bottom and the deck. No shim if the deck is flat (DECK_R = 0).
    """
    if DECK_R <= 0:
        return None
    dmin, dmax = sole_span()
    t = SOLE_MIN + (dmax - dmin)
    slab = ext(rr(BODY_X - 2, BODY_Y - 2, R_BODY - 1), -t, 0)
    # x-axis cylinder standing for the deck: its surface passes SOLE_MIN below the highest point
    axis_y = DECK_CROWN_Y - BOX_Y
    axis_z = -SOLE_MIN + dmin - DECK_R
    deck = cyl_x(DECK_R, -BODY_X, BODY_X, axis_y, axis_z, 1440)
    return check(slab - deck, "sole")


def make_cord() -> M:
    """Silicone cord squeezed in the groove (lid closed)."""
    h = GROOVE_D                                       # squeezed to the groove depth
    w = min(GROOVE_W - 0.05, math.pi * CORD_D ** 2 / 4 / h)
    return check(ext(groove_ring(w), BASE_H - h, BASE_H - 0.02), "cord")


def make_box_hardware() -> dict[str, M]:
    """Countersunk M3 screws, captive nuts and vent (enclosure frame)."""
    screws, nuts = [], []
    top = BASE_H + LID_T
    nut_top = FL_Z0 + 2.6
    for (x, y) in screw_points():
        head = M.cylinder(1.7, 1.6, 3.0, 32).translate((x, y, top - 1.7))
        shank = cyl(1.5, top - SCREW_L, top - 1.69, x, y, 24)
        screws.append(union(head, shank))
        ang = 30.0 if abs(x) > 1 else 0.0
        nuts.append(hexprism(NUT_M3_FLATS, nut_top - NUT_M3_H, nut_top - 0.05, 0, 0).rotate((0, 0, ang))
                    .translate((x, y, 0)) - cyl(1.55, nut_top - 3, nut_top + 1, x, y, 24))
    vent = cyl_x(VENT_PATCH_R, -BODY_X / 2 - 0.4, -BODY_X / 2 - 0.01, VENT_Y, VENT_Z, 32)
    return {"screw": union(*screws), "nut_m3": union(*nuts), "vent": vent}


def make_mount_body() -> M:
    """Fixed transmitter mount, flat floor of SUP_WIN mm under the coil.

    45° lead-in chamfers on the lower front and rear edges (direction the robot passes): if it
    touches despite the adjustment, the lid slides underneath instead of hitting it. Printed floor
    down on the bed, with no supports or bridges.
    """
    h, hx, lead = SUP_H, SUP_X / 2, SUP_LEAD
    prof = profile_xz([(-hx, lead), (-hx + lead, 0), (hx - lead, 0), (hx, lead), (hx, h), (-hx, h)], SUP_Y)
    outline = ext(rr(SUP_X, SUP_Y, 6), -1, h + 1)
    body = prof ^ outline
    cuts = [ext(rr(SUP_CAV, SUP_CAV, 3), SUP_WIN, h + 1)]
    for y in (-ROD_Y, ROD_Y):
        cuts.append(hexprism(10.2, h - 5.5, h + 1, 0, y))        # captive M6 nut
        cuts.append(cyl(3.2, h - 14, h + 1, 0, y, 32))           # blind hole: the rod does not stick out underneath
    # PG9 cable gland (Ø15.5) on the rear side (-x): the cable runs towards the station tower
    cuts.append(cyl_x(7.75, -hx - 1, -SUP_CAV / 2 + 1, GLAND_Y, GLAND_Z, 48))
    return check(body - union(*cuts), "mount_body")


def make_mount_cap() -> M:
    """Mount cap in assembled position (z = 0 on the body)."""
    cap = ext(rr(SUP_X, SUP_Y, 6), 0, SUP_CAP_T)
    tongue = ext(rr(SUP_CAV - 0.8, SUP_CAV - 0.8, 2.6), -2, 0.01)
    cuts = [cyl(3.3, -3, SUP_CAP_T + 1, 0, y, 32) for y in (-ROD_Y, ROD_Y)]
    cuts.append(ext(rr(SUP_CAV - 6, SUP_CAV - 6, 1), -3, -0.01))
    return check(union(cap, tongue) - union(*cuts), "mount_cap")


def make_bracket() -> M:
    """Bridge under the roof: z = 0 at the roof, part pointing down (negative z). Rods at y = ±ROD_Y."""
    top = box(-BR_X / 2, BR_X / 2, -BR_Y / 2, BR_Y / 2, -BR_PLATE, 0)
    z_bar_top = -BR_PLATE - BR_GAP
    z_bar_bot = z_bar_top - BR_BAR
    walls = [box(-BR_X / 2, BR_X / 2, s * BR_Y / 2 - (4 if s > 0 else 0), s * BR_Y / 2 + (0 if s > 0 else 4),
                 z_bar_bot, -BR_PLATE + 0.01) for s in (-1, 1)]
    bar = box(-BR_X / 2, BR_X / 2, -BR_Y / 2, BR_Y / 2, z_bar_bot, z_bar_top)
    bosses = [cyl(8.0, z_bar_bot - BR_BOSS, z_bar_bot + 0.01, x, 0) for x in (-ROD_Y, ROD_Y)]
    holes = [cyl(3.4, z_bar_bot - BR_BOSS - 1, z_bar_top + 1, x, 0, 32) for x in (-ROD_Y, ROD_Y)]
    # cut-outs in the bonding plate (leaves 2 wide strips for the VHB)
    holes.append(box(-25, 25, -10, 10, -BR_PLATE - 1, 1))
    # drawn along x then rotated: the crossbar and the rods are aligned on y
    return check((union(top, *walls, bar, *bosses) - union(*holes)).rotate((0, 0, 90)), "bracket")


# --------------------------------------------------------------------------------------
# Non-printed mock-ups (robot, station, electronics, hardware)
# --------------------------------------------------------------------------------------

def make_robot() -> dict[str, M]:
    parts: dict[str, M] = {}
    wheels = []
    for x in (-215.0, 215.0):
        for y in (-214.0, 214.0):
            wheels.append(cyl_y(120, y - 42, y + 42, x, 120))
    parts["wheels"] = union(*wheels)
    chassis = ext(rr(600, 340, 40), 60, 200).translate((0, 0, 0))
    parts["chassis"] = chassis
    shell = M.batch_hull([
        ext(rr(640, 380, 60), 185, 222).translate((-10, 0, 0)),
        ext(rr(600, 350, 60), DECK_Z - 0.01, DECK_Z).translate((-20, 0, 0)),
        ext(rr(60, 300, 20), 120, 200).translate((310, 0, 0)),
    ])
    parts["shell"] = shell
    parts["bumper"] = box(338, 358, -200, 200, 95, 150)
    cam = M.batch_hull([
        box(CAM_X0, CAM_X0 + 65, -45, 45, DECK_Z - 5, DECK_Z + 6),
        box(CAM_X0 + 7, CAM_X0 + 57, -40, 40, 266, 273),
    ])
    parts["camera"] = cam
    parts["camera_lens"] = cyl_x(9, CAM_X0 + 55, CAM_X0 + 66, 0, 258, 32)
    parts["rain_sensor"] = box(238, 262, 62, 86, DECK_Z - 1, DECK_Z + 2.5)
    parts["buttons"] = box(-260, -170, -55, 55, DECK_Z - 1, DECK_Z + 2)
    parts["stop_button"] = cyl(20, DECK_Z, DECK_Z + 12, -215, 30)
    return parts


def make_station() -> dict[str, M]:
    parts: dict[str, M] = {}
    parts["plate"] = box(-430, -60, -260, 260, 0, 12)
    parts["tower"] = box(-475, -405, -110, 110, 0, ROOF_Z + 20)
    roof = M.batch_hull([
        box(-480, 345, -340, 340, ROOF_Z, ROOF_Z + 40),
        box(-470, 300, -320, 320, ROOF_Z + 85, ROOF_Z + 95),
    ])
    parts["roof"] = roof
    return parts


def antenna_cable_path() -> list[tuple[float, float, float]]:
    """u.FL cable: from the module over its end, down to the floor, one slack loop, up to the antenna feed."""
    ux = BXC + UFL_X
    zc = FLOOR + 0.7
    p2 = (ux, BY0 - 6.0, zc)
    loop_r, loop_c = 9.0, (ux, BY0 - 15.0)
    loop = [(loop_c[0] + loop_r * math.sin(2 * math.pi * k / 24), loop_c[1] + loop_r * math.cos(2 * math.pi * k / 24), zc)
            for k in range(1, 24)]
    return ([(ux, BY0 + 2.5, MOD_Z1 + 1.4), (ux, BY0 - 2.5, MOD_Z1 + 1.4), p2] + loop + [p2,
            (ANT_FEED[0], -IN_Y / 2 + 6.0, zc), (ANT_FEED[0], -IN_Y / 2 + 2.0, ANT_FEED[2]), ANT_FEED])


def battery_wire_path() -> list[tuple[float, float, float]]:
    """Holder leads: from the -y end of the holder, above the corner pillars, to the JST socket on the +x edge."""
    zw, xw, yw = PCB_Z1 + 2.2, (BX1 + HX0) / 2 + 0.3, HY0 - 3.0
    return [(HXC, HY0 + 0.5, HZ0 + 10.0), (HXC, yw, HZ0 + 10.0), (HXC, yw, zw), (xw, yw, zw), (xw, JST_Y, zw),
            (BX1 + 1.7, JST_Y, zw)]


def make_board() -> dict[str, M]:
    """Olimex ESP32-S3-DevKit-Lipo-EA in its cradle, 18650 holder, antenna and Qi patch (enclosure frame).

    Board on the -x side, module (u.FL) towards the antenna wall (-y), USB-C towards +y; holder on
    the +x side, pushed towards +y. Envelopes with the conservative component heights.
    """
    mx = MODULE[0] / 2
    parts = {
        "pcb": box(BX0, BX1, BY0, BY1, PCB_Z0, PCB_Z1),
        "esp_module": box(BXC - mx, BXC + mx, BY0, BY0 + MODULE[1], PCB_Z1, MOD_Z1),
        "usb_c": union(*[box(BXC + d - 4.5, BXC + d + 4.5, BY1 - 7.5, BY1 + 0.5, PCB_Z1, PCB_Z1 + 3.3)
                         for d in (-USB_DX, USB_DX)]),
        "bat_jst": box(BX1 - 4.5, BX1 + 2.0, JST_Y - 3.0, JST_Y + 3.0, PCB_Z1, PCB_Z1 + 3.5),
    }
    # holder with leads: base, end plates with the contacts, low side walls; protected cell inside
    hz1 = HZ0 + HOLDER_H
    parts["holder"] = union(
        box(HX0, HX1, HY0, HY1, HZ0, HZ0 + 1.2),
        box(HX0, HX1, HY0, HY0 + 3.0, HZ0, hz1), box(HX0, HX1, HY1 - 3.0, HY1, HZ0, hz1),
        box(HX0, HX0 + 1.2, HY0, HY1, HZ0, HZ0 + 12.0), box(HX1 - 1.2, HX1, HY0, HY1, HZ0, HZ0 + 12.0))
    cy0 = (HY0 + HY1) / 2 - CELL_L / 2
    parts["battery"] = cyl_y(CELL_D / 2, cy0, cy0 + CELL_L, HXC, HZ0 + 1.2 + CELL_D / 2 + 0.01, 32)
    parts["bat_wire"] = tube(battery_wire_path(), 1.0)
    # FPC antenna on the -y end wall, as far as possible from the Qi patch and the cell
    parts["antenna"] = box(-ANT_L / 2, ANT_L / 2, ANT_Y0, ANT_Y0 + ANT_T, ANT_Z0, ANT_Z0 + ANT_W)
    parts["ant_cable"] = tube(antenna_cable_path(), 0.6)
    # receiver patch: winding on top against the window, ferrite underneath on the board side
    parts["rx_pad"] = box(-RX_PAD_W / 2, RX_PAD_W / 2, -RX_PAD_L / 2, RX_PAD_L / 2, PAD_Z0, PAD_Z1 - 0.4)
    ring = CS.circle(20, SEG) - CS.circle(9, SEG)
    parts["rx_coil"] = ext(ring, PAD_Z1 - 0.41, PAD_Z1)
    # foam, only under the patch: on the holder, and on the board between the battery socket and the
    # USB-C sockets (not over the module: the u.FL plug and its cable stay free)
    parts["foam"] = union(box(HX0 + 1.0, min(HX1 - 1.0, RX_PAD_W / 2 - 1.0), -30, 30, FOAM_Z0, PAD_Z0),
                          box(max(BX0 + COMP_EDGE, -RX_PAD_W / 2 + 1.0), BX1 - COMP_EDGE, JST_Y + 4.0, 30, FOAM_Z0, PAD_Z0))
    # receiver flex cable and its USB-C plug, in the socket on the holder side
    qx = BXC + USB_DX
    yb = BY1 + 9.5
    parts["rx_flex"] = union(
        box(qx - 6, qx + 6, RX_PAD_L / 2 - 0.5, yb, PAD_Z1 - 0.4, PAD_Z1),
        box(qx - 6, qx + 6, yb - 0.4, yb, USB_Z, PAD_Z1),
        box(qx - 4, qx + 4, BY1 + 0.5, yb - 0.4, USB_Z - 2.5, USB_Z + 2.5),
    )
    return parts


def board_envelopes() -> dict[str, M]:
    """Check-only volumes: undocumented components and the flashing plug (lid open)."""
    fx = BXC - USB_DX
    return {
        "components above the board": box(BX0 + COMP_EDGE, BX1 - COMP_EDGE, BY0, BY1, PCB_Z1, PCB_Z1 + COMP_TOP),
        "components under the board": box(BX0 + LEDGE + 0.5, BX1 - LEDGE - 0.5, BY0, BY1, PCB_Z0 - COMP_BOT, PCB_Z0),
        "u.FL plug clearance": box(BXC - MODULE[0] / 2, BXC + MODULE[0] / 2, BY0, BY0 + MODULE[1], MOD_Z1, MOD_Z1 + UFL_CLEAR),
        "flashing USB-C plug": box(fx - 6.2, fx + 6.2, BY1 + 0.5, BY1 + 0.5 + USB_PLUG_L, USB_Z - 3.5, USB_Z + 3.5),
    }


def make_tx() -> dict[str, M]:
    """Transmitter in its mount (mount frame). Winding at the BOTTOM against the floor, ferrite above."""
    ring = CS.circle(24, SEG) - CS.circle(10, SEG)
    hx = SUP_X / 2
    return {
        "tx_coil": ext(ring, SUP_WIN, SUP_WIN + 0.6),
        "tx_coil_plate": box(-TX_COIL[0] / 2, TX_COIL[0] / 2, -TX_COIL[1] / 2, TX_COIL[1] / 2,
                             SUP_WIN + 0.6, SUP_WIN + TX_COIL[2]),
        "tx_pcb": box(-TX_PCB[0] / 2, TX_PCB[0] / 2, -TX_PCB[1] / 2, TX_PCB[1] / 2, 9, 9 + TX_PCB[2]),
        "usb_plug": box(-5, 5, 9, 18, 15, 25),
        "gland": union(cyl_x(9.5, -hx - 4, -hx, GLAND_Y, GLAND_Z, 6),
                       cyl_x(7.0, -hx - 14, -hx - 4, GLAND_Y, GLAND_Z, 32)),
        "cable": union(cyl_x(2.5, -hx - 40, 0, GLAND_Y, GLAND_Z, 16), cyl(2.5, GLAND_Z, 25, 0, GLAND_Y, 16)),
    }


# --------------------------------------------------------------------------------------
# Assembly, checks and exports
# --------------------------------------------------------------------------------------

def to_trimesh(m: M) -> trimesh.Trimesh:
    mesh = m.to_mesh()
    return trimesh.Trimesh(np.asarray(mesh.vert_properties)[:, :3], np.asarray(mesh.tri_verts), process=False)


def export_stl(m: M, name: str, flip: bool = False) -> trimesh.Trimesh:
    if flip:
        m = m.rotate((180, 0, 0))
    bb = m.bounding_box()
    m = m.translate((-(bb[0] + bb[3]) / 2, -(bb[1] + bb[4]) / 2, -bb[2]))
    tm = to_trimesh(m)
    assert tm.is_watertight, name
    tm.export(ROOT / "stl" / name)
    return tm


def main() -> int:
    if HZ0 < FLOOR + 0.5:
        raise SystemExit(f"Battery holder {HOLDER_H:.0f} mm too tall for the inner height: at most {FOAM_Z0 - FLOOR - 0.5:.1f} mm")
    if PCB_Z0 - COMP_BOT < FLOOR + 0.5:
        raise SystemExit("Board components too tall for the inner height")
    base, lid = make_base(), make_lid()
    sole, cord, box_hw = make_sole(), make_cord(), make_box_hardware()
    deck_dmin, deck_dmax = sole_span()
    mount_body, mount_cap, bracket = make_mount_body(), make_mount_cap(), make_bracket()
    mount = union(mount_body, mount_cap.translate((0, 0, SUP_H)))

    # --- world positions (robot docked) ---
    box_x = CAM_X0 - CAM_GAP - SUP_X / 2
    deck_top = DECK_Z - deck_dmin                          # highest point of the deck under the enclosure
    sole_top = deck_top + VHB_T + SOLE_MIN if sole is not None else None
    box_z = (sole_top if sole is not None else deck_top) + VHB_T
    lid_top = box_z + BASE_H + LID_T
    sup_z = lid_top + AIR_GAP                              # underside of the mount
    cap_top = sup_z + SUP_H + SUP_CAP_T
    sup_top_hw = cap_top + WASHER_M6_T + NUT_M6_H          # clamping nut on the mount cap
    bar_top = ROOF_Z - BR_PLATE - BR_GAP
    bar_bot = bar_top - BR_BAR
    boss_bottom = bar_bot - BR_BOSS
    low_nut_bottom = boss_bottom - WASHER_M6_T - NUT_M6_H  # adjustment nut under the bridge
    rod_bottom = sup_z + SUP_H - 13.0                      # 14 mm blind hole, 1 mm margin
    rod_top_min = bar_top + WASHER_M6_T + 2 * NUT_M6_H + ROD_TOP_MARGIN
    rod_len = math.ceil(rod_top_min - rod_bottom)
    rod_top = rod_bottom + rod_len

    T_box = (box_x, BOX_Y, 0.0)
    robot = make_robot()
    station = make_station()
    board = make_board()
    tx = make_tx()

    def at_box(m: M, z: float) -> M:
        return m.translate((T_box[0], T_box[1], z))

    world_base = at_box(base, box_z)
    world_lid = at_box(lid, box_z + BASE_H)
    world_mount = at_box(mount, sup_z)
    world_bracket = at_box(bracket, ROOF_Z)

    # --- geometric checks ---
    errors = []
    envelopes = board_envelopes()
    board_union = union(*board.values(), *envelopes.values())
    lid_on = lid.translate((0, 0, BASE_H))
    # every electronic part, cable and check-only volume against the base (cradle included) and the lid
    for name, part in list(board.items()) + list(envelopes.items()):
        for other, solid in (("base", base), ("lid", lid_on)):
            v = (part ^ solid).volume()
            if v > 0.05:
                errors.append(f"{name} colliding with the {other}: {v:.2f} mm³")
    # parts against each other; the pairs listed are plugged into each other or pressed together
    groups = {
        "board": union(board["pcb"], board["esp_module"], board["usb_c"], board["bat_jst"],
                       *[v for k, v in envelopes.items() if k != "flashing USB-C plug"]),
        "holder": union(board["holder"], board["battery"]),
        "battery leads": board["bat_wire"], "antenna": board["antenna"], "antenna cable": board["ant_cable"],
        "foam": board["foam"], "Qi patch": union(board["rx_pad"], board["rx_coil"]), "Qi flex and plug": board["rx_flex"],
        "flashing USB-C plug": envelopes["flashing USB-C plug"],
    }
    plugged = {("board", "battery leads"), ("holder", "battery leads"), ("board", "antenna cable"),
               ("antenna", "antenna cable"), ("board", "Qi flex and plug"), ("Qi patch", "Qi flex and plug"),
               ("Qi patch", "flashing USB-C plug")}     # lid open: the patch is lifted off for flashing
    names = list(groups)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            if (a, b) in plugged:
                continue
            v = (groups[a] ^ groups[b]).volume()
            if v > 0.05:                                # parts pressed together only touch
                errors.append(f"{a} colliding with {b}: {v:.2f} mm³")
    # antenna: far from the Qi ferrite and the cell, on the flat part of the wall, clear of the seal and screws
    ant = board["antenna"]
    gap_ant_pad = ant.min_gap(board["rx_pad"], 100.0)
    gap_ant_cell = ant.min_gap(board["battery"], 100.0)
    gap_ant_screw = min(ant.min_gap(box_hw["screw"], 50.0), ant.min_gap(box_hw["nut_m3"], 50.0))
    gap_ant_cord = ant.min_gap(cord, 50.0)
    if gap_ant_pad < ANT_CLEAR_MIN or gap_ant_cell < ANT_CLEAR_MIN:
        errors.append(f"antenna too close: {gap_ant_pad:.1f} mm from the Qi patch, {gap_ant_cell:.1f} mm from the cell "
                      f"(minimum {ANT_CLEAR_MIN:.0f})")
    if ANT_L > IN_X - 2 * R_IN - 1:
        errors.append(f"antenna {ANT_L:.0f} mm longer than the flat part of the end wall ({IN_X - 2 * R_IN:.0f} mm)")
    if gap_ant_screw < 1.0 or gap_ant_cord < 1.0:
        errors.append(f"antenna touching the seal or the screws ({gap_ant_cord:.1f} / {gap_ant_screw:.1f} mm)")
    ant_path = antenna_cable_path()
    ant_route = path_length(ant_path[:3]) + math.dist(ant_path[2], ant_path[-3]) + path_length(ant_path[-3:])
    if ant_route > ANT_CABLE[0] - 10:
        errors.append(f"u.FL route {ant_route:.0f} mm: too long for a {ANT_CABLE[0]:.0f} mm antenna cable")
    # nothing in front of the vent opening
    vent_room = cyl_x(VENT_R + 1.0, -IN_X / 2 - 0.5, -IN_X / 2 + 3.0, VENT_Y, VENT_Z, 24)
    if (vent_room ^ union(board_union, *make_cradle())).volume() > 0.01:
        errors.append("the inner opening of the vent is obstructed")
    tx_union = union(*[v for k, v in tx.items() if k not in ("gland", "cable")])
    inter_tx = (tx_union ^ mount).volume()
    if inter_tx > 1.0:
        errors.append(f"transmitter colliding with its mount: {inter_tx:.1f} mm³")
    if SUP_X < TX_COIL[0] + 2 * SUP_LEAD or SUP_CAV < max(TX_COIL[0], TX_PCB[0]) + 2:
        errors.append("mount too short for the coil and its chamfers")
    # sealing: seal, lip, screws, vent
    cord_area = math.pi * CORD_D ** 2 / 4
    groove_fill = cord_area / (GROOVE_W * GROOVE_D)
    squeeze = 1 - GROOVE_D / CORD_D
    if not 0.70 <= groove_fill <= 0.90:
        errors.append(f"seal groove {round(groove_fill * 100)}% full (expected 70–90%)")
    if not 0.18 <= squeeze <= 0.32:
        errors.append(f"seal squeeze {round(squeeze * 100)}% (expected 18–32%)")
    for name, part, tol in (("lid", lid_on, 0.5), ("cord", cord, 0.5)):
        v = (part ^ base).volume()
        if v > tol:
            errors.append(f"{name} colliding with the base: {v:.1f} mm³")
    v = (cord ^ lid_on).volume()
    if v > 0.5:
        errors.append(f"cord colliding with the lid: {v:.1f} mm³")
    for k in ("screw", "nut_m3"):
        v = (box_hw[k] ^ base).volume() + (box_hw[k] ^ lid_on).volume()
        if v > 2.0:
            errors.append(f"{k} colliding with the enclosure: {v:.1f} mm³")
    if (board_union ^ cord).volume() > 0.1:
        errors.append("electronics on the seal")
    groove_out = GROOVE_C + GROOVE_W / 2
    screw_edge = SCREW_OFF - 1.7
    if screw_edge - groove_out < 0.8:
        errors.append(f"screw hole {screw_edge - groove_out:.1f} mm from the groove")
    vent_probe = cyl_x(VENT_R - 0.2, -FL_X / 2 - 2, -BODY_X / 2 + 0.5, VENT_Y, VENT_Z, 24)
    if (vent_probe ^ base).volume() > 0.1:
        errors.append("the vent hole is blocked by the flange")
    if VENT_Z + VENT_PATCH_R > FL_Z0 - FLANGE:
        errors.append("the vent membrane touches the flange chamfer")

    cam_world = robot["camera"]
    gap_cam = world_mount.min_gap(cam_world, 100.0)
    box_world = union(world_base, world_lid)
    gap_box_cam = box_world.min_gap(cam_world, 100.0)
    gap_box_rain = box_world.min_gap(robot["rain_sensor"], 100.0)
    if gap_box_cam < 5.0:
        errors.append(f"enclosure too close to the camera: {gap_box_cam:.1f} mm")
    # Robot passing under the mount: the robot backs up from +x to its docked position.
    moving = union(*robot.values(), world_base, world_lid)
    sweep_gap, sweep_at = 1e9, 0.0
    for d in range(0, 905, 5):
        g = moving.translate((float(d), 0, 0)).min_gap(world_mount, 60.0)
        if g < sweep_gap:
            sweep_gap, sweep_at = g, float(d)
    adj_up = low_nut_bottom - sup_top_hw                   # the mount can go up by this much
    adj_down = (ROOF_Z - BR_PLATE) - rod_top               # and down by this much (top of the rods / roof)
    if gap_cam < 5.0:
        errors.append(f"mount too close to the camera: {gap_cam:.1f} mm")
    if sweep_gap < AIR_GAP - 0.05:
        errors.append(f"the robot hits the mount while backing up: gap {sweep_gap:.1f} mm at {sweep_at:.0f} mm from the base")
    if adj_up < ADJ_MIN:
        errors.append(f"insufficient upward adjustment travel: {adj_up:.1f} mm")
    if adj_down < ADJ_MIN:
        errors.append(f"insufficient downward adjustment travel: {adj_down:.1f} mm")
    plastic = (LID_T - WINDOW[2]) + SUP_WIN
    coil_gap = plastic + AIR_GAP
    if not (QI_RANGE[0] <= coil_gap <= QI_RANGE[1]):
        errors.append(f"coils {coil_gap:.1f} mm apart, outside the Qi range {QI_RANGE[0]:.0f}–{QI_RANGE[1]:.0f} mm")
    h_needed = (AIR_GAP + SUP_H + SUP_CAP_T + 2 * (WASHER_M6_T + NUT_M6_H) + ADJ_MIN
                + BR_BOSS + BR_BAR + BR_GAP + BR_PLATE)

    sole_txt = (f"printed shim {SOLE_MIN:.1f}–{SOLE_MIN + deck_dmax - deck_dmin:.1f} mm (crowned deck R {DECK_R:.0f} mm)"
                if sole is not None else "flat deck (DECK_R = 0), bottom bonded directly")
    report = {
        "Enclosure (base + lid), overall size": f"{LID_X:.1f} × {LID_Y:.1f} × {BASE_H + LID_T:.1f} mm (x × y × h), VHB-bonded",
        "Inner volume": f"{IN_X:.0f} × {IN_Y:.0f} × {IN_H + WINDOW[2]:.0f} mm",
        "Lid seal": f"Ø{CORD_D:.1f} silicone cord in a {GROOVE_W:.1f} × {GROOVE_D:.1f} mm groove: {round(squeeze * 100)}% squeeze, {round(groove_fill * 100)}% fill",
        "Closure protection": f"{LIP_H:.0f} mm lip in front of the seal, 6 M3 screws outside the seal, ePTFE vent on the rear wall",
        "Deck fit": sole_txt,
        "Enclosure center (robot frame)": f"x = {box_x:.0f} mm, y = {BOX_Y:.0f} mm",
        "Top of the lid": f"{lid_top:.1f} mm above the ground (camera: 273 mm)",
        "Lid / mount gap (docked)": f"{AIR_GAP:.1f} mm of air, adjustable to the mm (1 turn of an M6 nut = 1 mm)",
        "Coil-to-coil distance": f"{coil_gap:.1f} mm = plastic {plastic:.1f} (lid 1.2 + mount 1.2) + air {AIR_GAP:.1f}; Qi range {QI_RANGE[0]:.0f}–{QI_RANGE[1]:.0f} mm",
        "Height tolerance at docking": f"robot at most {AIR_GAP:.1f} mm higher (beyond: rubbing), at most {QI_RANGE[1] - coil_gap:.1f} mm lower (beyond: out of range)",
        "Minimum gap while backing in": f"{sweep_gap:.1f} mm (the lid passes under the mount)",
        "Mount / camera gap (docked)": f"{gap_cam:.1f} mm",
        "Mount adjustment travel": f"+{adj_up:.0f} / −{adj_down:.0f} mm",
        "Required clearance lid → roof": f"≥ {h_needed:.0f} mm (model: {ROOF_Z - lid_top:.0f} mm)",
        "M6 stainless threaded rods": f"2 × {rod_len} mm",
        "Enclosure / camera, rain sensor": f"{gap_box_cam:.1f} mm / {gap_box_rain:.1f} mm",
        "Board": f"Olimex ESP32-S3-DevKit-Lipo {BOARD_W:.2f} × {BOARD_L:.2f} mm in the printed cradle, PCB top {PCB_Z1:.1f} mm above the bottom, "
                 f"components {COMP_TOP:.0f} mm above / {COMP_BOT:.0f} mm below assumed",
        "18650 holder": f"{HOLDER_L:.0f} × {HOLDER_W:.0f} × {HOLDER_H:.0f} mm on a {HZ0 - FLOOR:.1f} mm plinth, cell Ø{CELL_D:.1f} × {CELL_L:.0f} mm (protected)",
        "Flashing access (lid open)": f"{USB_PLUG_L:.0f} mm of straight plug in front of the free USB-C socket",
        "FPC antenna (−y end wall)": f"{ANT_L:.0f} × {ANT_W:.0f} mm, {gap_ant_pad:.1f} mm from the Qi patch, {gap_ant_cell:.1f} mm from the cell, "
                                     f"{gap_ant_screw:.1f} mm from the screws, {gap_ant_cord:.1f} mm from the seal",
        "u.FL cable route": f"{ant_route:.0f} mm without the slack loop (cable {ANT_CABLE[0]:.0f}–{ANT_CABLE[1]:.0f} mm)",
    }
    print("Dimension report")
    for k, v in report.items():
        print(f"  - {k}: {v}")

    # --- print STLs ---
    stl_dir = ROOT / "stl"
    stl_dir.mkdir(exist_ok=True)
    for old in stl_dir.glob("*.stl"):
        old.unlink()
    stl = {
        "01_enclosure_base.stl": export_stl(base, "01_enclosure_base.stl"),
        "02_enclosure_lid.stl": export_stl(lid, "02_enclosure_lid.stl", flip=True),
        "03_transmitter_mount.stl": export_stl(mount_body, "03_transmitter_mount.stl"),
        "04_mount_cap.stl": export_stl(mount_cap, "04_mount_cap.stl", flip=True),
        "05_roof_bridge.stl": export_stl(bracket, "05_roof_bridge.stl", flip=True),
    }
    if sole is not None:
        stl["06_deck_shim.stl"] = export_stl(sole, "06_deck_shim.stl", flip=True)
    print("STL")
    for name, tm in stl.items():
        ext_ = tm.extents
        print(f"  - {name}: {ext_[0]:.1f} × {ext_[1]:.1f} × {ext_[2]:.1f} mm, {tm.volume / 1000:.1f} cm³")

    # --- GLB scene ---
    colors = {
        "wheels": "#202224", "chassis": "#55595e", "shell": "#e4e6e8", "bumper": "#26282b",
        "camera": "#2b2f33", "camera_lens": "#2d6cdf", "rain_sensor": "#18a0c8", "buttons": "#3a3d40",
        "stop_button": "#d62828", "plate": "#4a4d50", "tower": "#5d6165", "roof": "#8a9096",
        "vhb": "#2b2b2b", "sole": "#c96f1c", "base": "#f08a24", "lid": "#f6a552", "cord": "#e8352e",
        "screw": "#b9c0c7", "nut_m3": "#d5dbe1", "vent": "#f2f2f2",
        "holder": "#151515", "battery": "#16a085", "bat_wire": "#c0392b", "bat_jst": "#f2f2f2",
        "antenna": "#e0a526", "ant_cable": "#2a2a2a",
        "pcb": "#101010", "esp_module": "#c7ccd1", "usb_c": "#b0b5ba", "rx_pad": "#3a3a3a", "rx_coil": "#c87533",
        "foam": "#e8e1c8", "rx_flex": "#d9a441", "mount_body": "#2f6fd6", "mount_cap": "#6f9de8",
        "tx_coil_plate": "#2a2a2a", "tx_coil": "#c87533", "tx_pcb": "#1d5f2a", "usb_plug": "#111111",
        "gland": "#1a1a1a", "cable": "#111111", "bracket": "#3aa655", "rod": "#a9b0b7", "nut": "#cfd5db",
    }
    scene = trimesh.Scene()
    METAL = ("rod", "nut", "esp_module", "usb_c", "screw", "nut_m3")

    def add(m: M, group: str, key: str, explode: float = 0.0, opacity: float = 1.0):
        tm = to_trimesh(m)
        hexc = colors[key]
        rgba = [int(hexc[i:i + 2], 16) / 255 for i in (1, 3, 5)] + [opacity]
        tm.visual = trimesh.visual.TextureVisuals(material=trimesh.visual.material.PBRMaterial(
            baseColorFactor=rgba, metallicFactor=0.6 if key in METAL else 0.05, roughnessFactor=0.55,
            alphaMode="BLEND" if opacity < 1 else "OPAQUE"))
        # name: group__part__exploded-view offset (mm, upwards)__index
        k = 1
        while (name := f"{group}__{key}__{round(explode)}__{k}") in scene.geometry:
            k += 1
        scene.add_geometry(tm, node_name=name, geom_name=name)

    EXP = {"vhb": -14, "sole": -8, "base": 0, "cord": 75, "lid": 95, "screw": 135, "nut_m3": -22, "vent": 0,
           "holder": 15, "battery": 15, "bat_wire": 15, "bat_jst": 15, "pcb": 15, "esp_module": 15, "usb_c": 15,
           "antenna": 15, "ant_cable": 15,
           "rx_pad": 60, "rx_coil": 60, "foam": 40, "rx_flex": 60}
    for k, m in robot.items():
        add(m, "robot", k)
    for k, m in station.items():
        add(m, "static", k)
    vhb_fp = rr(BODY_X - 2, BODY_Y - 2, R_BODY - 1)
    if sole is not None:
        add(at_box(sole, sole_top), "robot", "sole", EXP["sole"])
        add(at_box(ext(vhb_fp, -VHB_T, 0), sole_top), "robot", "vhb", EXP["sole"] + EXP["vhb"] / 2)
        add(at_box(ext(vhb_fp, -VHB_T, 0), box_z), "robot", "vhb", EXP["vhb"] / 2)
    else:
        add(at_box(ext(vhb_fp, -VHB_T, 0), box_z), "robot", "vhb", EXP["vhb"])
    add(world_base, "robot", "base")
    add(world_lid, "robot", "lid", EXP["lid"])
    add(at_box(cord, box_z), "robot", "cord", EXP["cord"])
    for k, m in box_hw.items():
        add(at_box(m, box_z), "robot", k, EXP[k])
    for k, m in board.items():
        add(at_box(m, box_z), "robot", k, EXP[k])
    add(world_bracket, "tx", "bracket", explode=60)
    add(at_box(mount_body, sup_z), "tx", "mount_body")
    add(at_box(mount_cap, sup_z + SUP_H), "tx", "mount_cap", explode=50)
    for k, m in tx.items():
        add(at_box(m, sup_z), "tx", k, explode=25 if k.startswith("tx") else 0)
    for y in (-ROD_Y, ROD_Y):
        wx, wy = box_x, BOX_Y + y
        add(cyl(3.0, rod_bottom, rod_top, wx, wy, 24), "tx", "rod")
        add(hexprism(10, sup_z + SUP_H - 5.2, sup_z + SUP_H, wx, wy), "tx", "nut")       # captive nut
        add(hexprism(10, cap_top, sup_top_hw, wx, wy), "tx", "nut")                     # clamps the mount cap
        add(hexprism(10, low_nut_bottom, boss_bottom, wx, wy), "tx", "nut")             # adjustment under the bridge
        add(hexprism(10, bar_top, bar_top + WASHER_M6_T + 2 * NUT_M6_H, wx, wy), "tx", "nut")  # lock nuts

    glb = scene.export(file_type="glb")

    # --- part cards, annotations and detail views for the viewer ---
    def fmt(v: float, d: int = 1) -> str:
        return f"{v:.{d}f}"

    def dims(*v: float) -> str:
        return " × ".join(fmt(x, 1).removesuffix(".0") for x in v) + " mm"

    P = lambda name, cat, role, size="", note="": {"name": name, "cat": cat, "role": role, "size": size, "note": note}
    parts = {
        "base": P("Enclosure base", "boitier", "Sealed body bonded to the deck, flange with a seal groove and 6 captive nuts. "
                  "Board cradle with snap arms and battery holder plinth printed in.",
                  dims(FL_X, FL_Y, BASE_H), "01_enclosure_base.stl · bottom on the bed · 4 perimeters, solid walls"),
        "lid": P("Lid", "boitier", f"Window thinned to {fmt(LID_T - WINDOW[2])} mm under the coil, {fmt(LIP_H, 0)} mm drip lip, engraved centering marks.",
                 dims(LID_X, LID_Y, LID_T + LIP_H), "02_enclosure_lid.stl · visible face on the bed"),
        "cord": P("Silicone cord seal", "quincaillerie", f"Solid Ø{fmt(CORD_D)} cord laid without stretching in the {fmt(GROOVE_W)} × {fmt(GROOVE_D)} mm groove; squeezed {round(squeeze * 100)}% when tightened.",
                  f"≈ {2 * (IN_X + IN_Y) / 1000 + 0.05:.2f} m", "Butt the two ends together"),
        "screw": P("Stainless M3 × 12 countersunk screws", "quincaillerie", "6 screws, all outside the seal: no hole goes through the sealed area.", "ISO 10642", "Tighten crosswise without crushing the seal"),
        "nut_m3": P("Captive M3 nuts", "quincaillerie", "Slid in from under the flange into hexagonal pockets: no heat-set inserts.", "M3, 5.5 across flats", ""),
        "vent": P("Adhesive ePTFE vent", "quincaillerie", f"Ø{fmt(2 * VENT_PATCH_R, 0)} breathable membrane over the Ø{fmt(2 * VENT_R, 0)} hole in the rear wall: equalizes pressure and limits condensation.", "IP67", "Optional, recommended"),
        "vhb": P("3M VHB tape", "quincaillerie", "Bonded to the deck without drilling the robot.", f"{fmt(VHB_T)} mm", "Degrease with alcohol, wait 24 h before driving"),
        "sole": P("Deck shim", "boitier", f"Takes up the deck crown (R {fmt(DECK_R, 0)} mm).", "", "06_deck_shim.stl · flat face on the bed"),
        "holder": P("1 × 18650 holder with leads and JST plug", "electronique",
                    "Taped onto the printed plinth, rim on the board side. Check the plug polarity with a multimeter before plugging it in: JST leads are not standardized.",
                    dims(HOLDER_L, HOLDER_W, HOLDER_H), "Dimensions to check against the holder bought"),
        "battery": P("Protected 18650 Li-ion cell", "electronique",
                     "3,400–3,500 mAh. Protection is mandatory: the board has none. About 22–29 h of Wi-Fi + BLE runtime (estimate).",
                     f"Ø{fmt(CELL_D)} × up to {fmt(CELL_L, 0)} mm", ""),
        "bat_wire": P("Holder leads", "electronique", "Run above the cradle pillars to the 2-pin 2.0 mm socket on the board.", "", "Socket position to check on the board"),
        "bat_jst": P("Battery socket", "electronique", "2-pin, 2.0 mm pitch, near a long edge of the board.", "", ""),
        "pcb": P("Olimex ESP32-S3-DevKit-Lipo-EA board", "electronique",
                 "ESP32-S3-WROOM-1U N8R8 (external antenna), two USB-C, Li-ion charger without battery protection. "
                 "No mounting holes: held by the printed cradle (corner ledges, end stops, two snap arms), headers not soldered.",
                 dims(BOARD_W, BOARD_L, PCB_T), f"Components ≤ {fmt(COMP_TOP, 0)} mm above and ≤ {fmt(COMP_BOT, 0)} mm below assumed"),
        "esp_module": P("ESP32-S3-WROOM-1U module", "electronique", "u.FL socket on the module (corner not documented): the module top is kept clear, the foam does not cover it.",
                        dims(*MODULE), "Rated for 65 °C ambient"),
        "usb_c": P("The two USB-C sockets", "electronique", "The native USB one takes the Qi receiver plug; the USB-UART one (CH340X: console and flashing) stays free with the lid open. Which is which: to check on the board.", "", f"{fmt(USB_PLUG_L, 0)} mm free for a straight plug"),
        "antenna": P("Flexible 2.4 GHz antenna (FPC), u.FL cable", "electronique",
                     f"Bonded inside the −y end wall, {fmt(gap_ant_pad)} mm from the Qi patch and {fmt(gap_ant_cell)} mm from the cell; nothing goes through the wall.",
                     dims(ANT_L, ANT_W), f"Cable {fmt(ANT_CABLE[0], 0)}–{fmt(ANT_CABLE[1], 0)} mm, slack coiled on the floor"),
        "ant_cable": P("Antenna u.FL cable", "electronique", "From the module over its end, a loose loop on the floor, up to the antenna.", "Ø1.13 mm", "Do not kink: bend radius ≥ 5 mm"),
        "rx_pad": P("Qi \"patch\" receiver", "electronique", "Coil against the lid window, ferrite on the board side.", dims(RX_PAD_W, RX_PAD_L, RX_PAD_T), "5 V / 1 A, USB-C plug"),
        "rx_coil": P("Receiver coil", "electronique", f"{fmt(coil_gap)} mm from the transmitter coil with the robot docked.", "", ""),
        "foam": P("3 mm EVA foam", "electronique", "Presses the patch against the window.", "", ""),
        "rx_flex": P("Receiver flex cable", "electronique", "Folded down to the board USB-C socket on the holder side.", "", ""),
        "mount_body": P("Transmitter mount", "support", f"Fixed under the roof, {fmt(SUP_WIN)} mm floor under the coil, 45° lead-in chamfers.", dims(SUP_X, SUP_Y, SUP_H), "03_transmitter_mount.stl"),
        "mount_cap": P("Mount cap", "support", "Clamped by the rod nuts.", dims(SUP_X, SUP_Y, SUP_CAP_T), "04_mount_cap.stl"),
        "bracket": P("Roof bridge", "support", "VHB-bonded under the garage roof, carries the two rods.", dims(BR_Y, BR_X, BR_PLATE + BR_GAP + BR_BAR + BR_BOSS), "05_roof_bridge.stl"),
        "tx_coil": P("Transmitter coil", "support", "Winding at the bottom, against the mount floor.", dims(*TX_COIL[:2]), ""),
        "tx_coil_plate": P("Transmitter ferrite", "support", "Above the winding.", "", ""),
        "tx_pcb": P("Adafruit 2162 transmitter", "support", "Powered over micro-USB 5 V 2 A, 2–8 mm range.", dims(*TX_PCB), ""),
        "usb_plug": P("Right-angle micro-USB plug", "support", "", "", ""),
        "gland": P("PG9 cable gland", "support", "Sealed cable exit from the mount.", "IP68", ""),
        "cable": P("Power cable", "support", "To a protected 5 V 2 A charger.", "", ""),
        "rod": P("Stainless M6 threaded rods", "support", "One turn of a nut moves the mount by 1 mm.", f"2 × {rod_len} mm", ""),
        "nut": P("M6 nuts", "support", "Height adjustment and locking.", "", ""),
        "camera": P("LUBA 2 camera", "robot", f"The lid stays {fmt(273 - lid_top)} mm below its top.", "", ""),
        "rain_sensor": P("Rain sensor", "robot", "Keep at least 30 mm clear around it.", "", ""),
    }

    bx, by = box_x, BOX_Y
    sx_, sy_ = IN_X / 2 + SCREW_OFF, 35.0
    zl = box_z + BASE_H
    A = lambda text, p, grp="robot", dz=0.0, dx=0.0, dy=-40.0: {"text": text, "p": [round(c, 2) for c in p], "grp": grp, "dz": dz, "dx": dx, "dy": dy}
    annots = {
        "window": A(f"Window {fmt(LID_T - WINDOW[2])} mm", (bx, by, zl + WINDOW[2]), dz=EXP["lid"], dx=-70, dy=-55),
        "cord": A(f"Ø{fmt(CORD_D)} cord squeezed {round(squeeze * 100)}%", (bx + IN_X / 2 + GROOVE_C, by + sy_, zl - GROOVE_D / 2), dz=EXP["cord"], dx=60, dy=-70),
        "groove": A(f"Groove {fmt(GROOVE_W)} × {fmt(GROOVE_D)}", (bx - IN_X / 2 - GROOVE_C, by + sy_, zl - GROOVE_D), dx=-70, dy=40),
        "lip": A(f"{fmt(LIP_H, 0)} mm rain lip", (bx + LID_X / 2 - LIP_T / 2, by + sy_, zl - LIP_H), dz=EXP["lid"], dx=70, dy=45),
        "screw": A("M3 screw outside the seal", (bx + sx_, by + sy_, zl + LID_T), dz=EXP["screw"], dx=55, dy=-45),
        "nut": A("Captive M3 nut", (bx + sx_, by + sy_, FL_Z0 + box_z + 1.4), dz=EXP["nut_m3"], dx=65, dy=35),
        "vent": A("ePTFE vent", (bx - BODY_X / 2 - 0.4, by + VENT_Y, box_z + VENT_Z), dx=-60, dy=30),
        "vhb": A(f"VHB {fmt(VHB_T)} mm, deck not drilled", (bx + BODY_X / 2 - 2, by - 30, box_z - VHB_T / 2), dz=EXP["vhb"], dx=60, dy=45),
        "battery": A("Protected 18650", (bx + HXC, by + (HY0 + HY1) / 2, box_z + HZ0 + HOLDER_H - 0.5), dz=EXP["battery"], dx=70, dy=30),
        "esp": A("ESP32-S3-WROOM-1U", (bx + BXC, by + BY0 + MODULE[1] / 2, box_z + MOD_Z1), dz=EXP["esp_module"], dx=-60, dy=-40),
        "usb": A("2 × USB-C: Qi + flashing", (bx + BXC - USB_DX, by + BY1, box_z + PCB_Z1 + 3.3), dz=EXP["usb_c"], dx=-60, dy=35),
        "antenna": A("FPC antenna (u.FL)", (bx, by + ANT_Y0, box_z + ANT_Z0 + ANT_W / 2), dz=EXP["antenna"], dx=-55, dy=-50),
        "cradle": A("Cradle snap arm", (bx + BX0 - CRADLE_GAP - SNAP_T / 2, by + BY1 - SNAP_Y, box_z + PCB_Z1 + 1.2), dx=-65, dy=10),
        "rx": A("Qi receiver patch", (bx + RX_PAD_W / 2, by + 10, zl + WINDOW[2] - 0.6), dz=EXP["rx_pad"], dx=60, dy=-40),
        "foam": A("3 mm foam", (bx + HX0 + 1.0, by, box_z + FOAM_Z0 + 1.5), dz=EXP["foam"], dx=60, dy=-20),
        "coilgap": A(f"{fmt(coil_gap)} mm between coils", (bx, by, lid_top + AIR_GAP / 2), dx=80, dy=-20),
        "air": A(f"Air {fmt(AIR_GAP)} mm (adjustable)", (bx + SUP_X / 2 - SUP_LEAD, by, lid_top + AIR_GAP / 2), dx=70, dy=30),
        "tx": A("Transmitter coil", (bx, by, sup_z + SUP_WIN + 2), grp="tx", dz=25, dx=-70, dy=-40),
        "chamfer": A("45° chamfer", (bx + SUP_X / 2 - SUP_LEAD / 2, by - SUP_Y / 2 + 10, sup_z + SUP_LEAD / 2), grp="tx", dx=60, dy=10),
        "rod": A("M6 rod: 1 turn = 1 mm", (bx, by + ROD_Y, (sup_top_hw + low_nut_bottom) / 2), grp="tx", dx=70, dy=0),
        "gland": A("PG9 cable gland", (bx - SUP_X / 2 - 9, by + GLAND_Y, sup_z + GLAND_Z), grp="tx", dx=-60, dy=-30),
        "camera": A(f"Camera: +{fmt(273 - lid_top, 0)} mm", (CAM_X0 + 30, 0, 270), dx=40, dy=-50),
        "rain": A("Rain sensor (kept clear)", (250, 74, DECK_Z + 2.5), dx=40, dy=40),
    }
    if sole is not None:
        annots["sole"] = A("Deck shim", (bx, by - BODY_Y / 2 + 4, sole_top - 1), dz=EXP["sole"], dx=-60, dy=40)

    D = lambda title, text, mode, notes, cam, target=(0, 0, 0), explode=0.0, section=None: {
        "title": title, "text": text, "mode": mode, "notes": notes, "cam": cam, "target": target,
        "explode": explode, "section": section}
    details = [
        D("Sealing", f"Cord seal in the flange groove, squeezed {round(squeeze * 100)}% by 6 screws placed outside the seal. "
          f"The {fmt(LIP_H, 0)} mm lip of the lid shields the closing line from rain and sprinklers.",
          "boitier", ["cord", "groove", "lip", "screw", "nut", "window"], cam=(70, 130, 40), target=(IN_X / 2, sy_, BASE_H / 2 + 6),
          section={"axis": "y", "offset": sy_}),
        D("Qi charging", f"Lid {fmt(LID_T - WINDOW[2])} mm + air {fmt(AIR_GAP)} mm + mount floor {fmt(SUP_WIN)} mm: "
          f"{fmt(coil_gap)} mm between the coils (2–8 mm range).",
          "support", ["window", "coilgap", "air", "tx", "rx", "foam"], cam=(70, 300, 70), target=(0, 0, BASE_H + 14),
          section={"axis": "y", "offset": 0}),
        D("Electronics", "Olimex ESP32-S3-DevKit-Lipo clipped into the printed cradle, protected 18650 in its holder on the plinth, "
          "Qi patch plugged into one USB-C and pressed against the window by the foam; the other USB-C stays free for flashing. "
          "The FPC antenna is bonded inside the end wall, away from the Qi ferrite and the cell.",
          "boitier", ["battery", "esp", "usb", "antenna", "cradle", "rx", "foam"], cam=(190, 150, 170), target=(0, 0, 60), explode=0.75),
        D("Closing and bonding", "Captive M3 nuts slid in from below, countersunk screws from the top, VHB under the bottom. ePTFE vent on the rear wall.",
          "boitier", ["screw", "nut", "vhb", "cord", "vent"] + (["sole"] if sole is not None else []),
          cam=(-210, 190, 150), target=(0, 0, 50), explode=1.0),
        D("Adjustable mount", "Fixed mount hung from the bridge by two M6 rods: one turn of a nut = 1 mm. 45° chamfers so the lid slides underneath.",
          "support", ["rod", "gland", "chamfer", "tx", "air"], cam=(330, 260, 120), target=(0, 0, 90)),
        D("On the LUBA 2", f"Bonded behind the camera, offset by {fmt(abs(BOX_Y), 0)} mm to keep the rain sensor clear, "
          f"lid {fmt(273 - lid_top, 0)} mm below the top of the camera.",
          "ensemble", ["camera", "rain", "vhb"], cam=(330, 330, 260), target=(30, 30, 0)),
    ]
    meta = {
        "boxX": box_x, "boxY": BOX_Y, "boxZ": box_z, "baseH": BASE_H, "lidTop": lid_top,
        "boxHalfX": LID_X / 2, "boxHalfY": LID_Y / 2, "supHalfX": SUP_X / 2,
        "airGap": AIR_GAP, "coilGap": coil_gap, "plastic": plastic, "sweepGap": sweep_gap,
        "roofZ": ROOF_Z, "explodeLift": EXPLODE_LIFT,
    }
    template = (ROOT / "cad" / "viewer_template.html").read_text(encoding="utf-8")
    import json
    html = (template.replace("__GLB_BASE64__", base64.b64encode(glb).decode("ascii"))
            .replace("__META_JSON__", json.dumps(meta))
            .replace("__REPORT_JSON__", json.dumps(report, ensure_ascii=False))
            .replace("__PARTS_JSON__", json.dumps(parts, ensure_ascii=False))
            .replace("__ANNOTS_JSON__", json.dumps(annots, ensure_ascii=False))
            .replace("__DETAILS_JSON__", json.dumps(details, ensure_ascii=False)))
    (ROOT / "viewer").mkdir(exist_ok=True)
    (ROOT / "viewer" / "luba-esp32-qi.html").write_text(html, encoding="utf-8")
    print(f"Scene: viewer/luba-esp32-qi.html ({len(html) / 1e6:.1f} MB)")

    if errors:
        print("ERRORS:")
        for e in errors:
            print(f"  ✗ {e}")
        return 1
    print("Checks: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
