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
# Electronics (LILYGO T-Energy S3 + 18650, USB-C Qi receiver of the "phone patch" type)
BOARD_L, BOARD_W, BOARD_H = 91.5, 32.0, 24.0      # board + holder + battery (conservative value)
RX_PAD_L, RX_PAD_W, RX_PAD_T = 70.0, 43.0, 1.2    # receiver coil area
# Adafruit 2162 "Universal Qi Wireless Charging Transmitter"
TX_COIL = (53.0, 53.0, 4.0)
TX_PCB = (55.0, 37.0, 6.0)

# Enclosure (local frame: origin at the center of the underside, x = direction of travel, y = across)
IN_X, IN_Y, IN_H = 50.0, 110.0, 26.0   # usable inner volume of the base
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

    # locating rails for the 18650 holder
    rails = [box(-13.5 - 1, -13.5 + 1, -50, 30, FLOOR, FLOOR + 3),
             box(13.5 - 1, 13.5 + 1, -50, 30, FLOOR, FLOOR + 3)]
    return check(union(base, *rails), "base")


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


def make_board() -> dict[str, M]:
    """T-Energy S3 sitting in the enclosure (enclosure frame). Antenna on the -y side, USB-C on the +y side."""
    y0 = -IN_Y / 2 + 1.0
    y1 = y0 + BOARD_L
    z0 = FLOOR
    holder_top = z0 + BOARD_H - 4.8
    parts = {
        "holder": box(-11, 11, y0 + 6, y1 - 6, z0, z0 + 2) + box(-11, 11, y0 + 6, y0 + 9, z0, holder_top)
        + box(-11, 11, y1 - 9, y1 - 6, z0, holder_top),
        "battery": cyl_y(9.2, y0 + 10, y0 + 75, 0, z0 + 9.6, 32),
        "pcb": box(-BOARD_W / 2, BOARD_W / 2, y0, y1, holder_top, holder_top + 1.6),
        "esp_module": box(-9, 9, y0 + 1, y0 + 26.5, holder_top + 1.6, holder_top + 4.8),
        "usb_c": box(-4.5, 4.5, y1 - 7, y1 + 0.5, holder_top + 1.6, holder_top + 4.8),
    }
    pad_z1 = BASE_H + WINDOW[2]                          # under the thinned window
    pad_z0 = pad_z1 - RX_PAD_T
    # receiver patch: winding on top against the window, ferrite underneath on the board side
    parts["rx_pad"] = box(-RX_PAD_W / 2, RX_PAD_W / 2, -RX_PAD_L / 2, RX_PAD_L / 2, pad_z0, pad_z1 - 0.4)
    ring = CS.circle(20, SEG) - CS.circle(9, SEG)
    parts["rx_coil"] = ext(ring, pad_z1 - 0.41, pad_z1)
    parts["foam"] = box(-18, 18, -30, 30, holder_top + 4.8, pad_z0)
    # receiver flex cable: from the patch to the board's USB-C socket
    parts["rx_flex"] = union(
        box(-6, 6, RX_PAD_L / 2 - 0.5, y1 + 7, pad_z1 - 0.4, pad_z1),
        box(-6, 6, y1 + 6.6, y1 + 7, holder_top + 2, pad_z1),
        box(-4, 4, y1 + 0.5, y1 + 7, holder_top + 1.8, holder_top + 4.6),
    )
    return parts


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
    board_union = union(*board.values())
    inter = (board_union ^ base).volume()
    if inter > 1.0:
        errors.append(f"electronics colliding with the base: {inter:.1f} mm³")
    inter_lid = (board_union ^ lid.translate((0, 0, BASE_H))).volume()
    if inter_lid > 1.0:
        errors.append(f"electronics colliding with the lid: {inter_lid:.1f} mm³")
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
    lid_on = lid.translate((0, 0, BASE_H))
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
        "holder": "#151515", "battery": "#16a085",
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
           "holder": 15, "battery": 15, "pcb": 15, "esp_module": 15, "usb_c": 15,
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
        "base": P("Enclosure base", "boitier", "Sealed body bonded to the deck, flange with a seal groove and 6 captive nuts.",
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
        "holder": P("T-Energy S3 18650 holder", "electronique", "Held between the two rails on the floor.", "", ""),
        "battery": P("18650 Li-ion battery", "electronique", "3,400–3,500 mAh, flat top, unprotected: about 25–35 h of Wi-Fi + BLE runtime.", "Ø18 × 65 mm", ""),
        "pcb": P("LILYGO T-Energy S3 board", "electronique", "ESP32-S3, charger and battery protection on a single board.", dims(BOARD_L, BOARD_W), "Switch to ON before closing"),
        "esp_module": P("ESP32-S3 module", "electronique", "Antenna on the −y side, away from the Qi patch ferrite.", "", ""),
        "usb_c": P("Board USB-C socket", "electronique", "Takes the Qi receiver plug: no soldering.", "", ""),
        "rx_pad": P("Qi \"patch\" receiver", "electronique", "Coil against the lid window, ferrite on the board side.", dims(RX_PAD_W, RX_PAD_L, RX_PAD_T), "5 V / 1 A, USB-C plug"),
        "rx_coil": P("Receiver coil", "electronique", f"{fmt(coil_gap)} mm from the transmitter coil with the robot docked.", "", ""),
        "foam": P("3 mm EVA foam", "electronique", "Presses the patch against the window.", "", ""),
        "rx_flex": P("Receiver flex cable", "electronique", "Folded upwards to the USB-C socket.", "", ""),
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
        "battery": A("18650 battery", (bx, by - IN_Y / 2 + 45, box_z + FLOOR + 9.6), dz=EXP["battery"], dx=-70, dy=30),
        "esp": A("ESP32-S3 (antenna)", (bx, by - IN_Y / 2 + 14, box_z + FLOOR + BOARD_H - 0.5), dz=EXP["esp_module"], dx=-60, dy=-40),
        "usb": A("USB-C, no soldering", (bx, by - IN_Y / 2 + BOARD_L - 3, box_z + FLOOR + BOARD_H - 0.5), dz=EXP["usb_c"], dx=60, dy=-30),
        "rx": A("Qi receiver patch", (bx + RX_PAD_W / 2, by + 10, zl + WINDOW[2] - 0.6), dz=EXP["rx_pad"], dx=60, dy=-40),
        "foam": A("3 mm foam", (bx - 18, by, box_z + FLOOR + BOARD_H + 1.5), dz=EXP["foam"], dx=-60, dy=-20),
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
        D("Electronics", "T-Energy S3 + 18650 battery on the floor rails, Qi patch plugged into USB-C and pressed against the window by the foam. "
          "The ESP32 antenna stays clear of the ferrite.",
          "boitier", ["battery", "esp", "usb", "rx", "foam"], cam=(190, 150, 170), target=(0, 0, 60), explode=0.75),
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
