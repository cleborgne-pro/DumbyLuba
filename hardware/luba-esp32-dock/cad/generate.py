#!/usr/bin/env python3
"""ESP32 enclosure for the LUBA 2 AWD, charged by contacts at the base — parametric model.

Generates:
  - stl/*.stl            parts to print, already oriented for printing;
  - viewer/luba-esp32-dock.html  self-contained 3D scene (embedded GLB) with a docking animation;
  - a report of dimensions, forces and clearances on standard output (and blocking checks).

Principle: the enclosure is VHB-bonded to the robot's deck. Two stainless plates sit flush in its
lid. Two stainless spring blades hang from a FIXED holder under the garage roof, hung by two M6
rods and adjusted to the millimeter (one turn of a nut = 1 mm). When the robot backs into the
station, an entry ramp on the lid lifts the blades, which slide onto the plates and press them
with a slight flexion: 5 V reaches the board through a USB-C male adapter. Nothing moves except
the blades bending.

World frame: x towards the front of the robot, y to the left, z up, in mm.
The LUBA 2 docks in REVERSE: the station tower is on the negative x side.
The robot, station and garage dimensions are approximate: measure them on site
and adjust the parameters marked "TO MEASURE". Design record: docs/CONTACT_CHARGING_DESIGN.md.

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
# a separate holder with leads and a JST plug, flexible 2.4 GHz antenna on a u.FL cable, USB-C
# male adapter in the native USB socket, fed by the plates.
BOARD_L, BOARD_W, PCB_T = 55.88, 27.94, 1.6       # PCB from the Rev B gerbers, no mounting holes
COMP_TOP, COMP_BOT = 4.0, 2.0                     # component height above / below the PCB: not documented, conservative
COMP_EDGE = 1.5                                   # strip along the long edges kept free of components (header rows)
MODULE = (18.0, 19.2, 3.2)                        # ESP32-S3-WROOM-1U (x, y, z), flush with the -y end of the board
UFL_X = 4.0                                       # u.FL cable exit, x from the board axis: corner not documented,
UFL_CLEAR = 3.0                                   # so the whole module top is kept clear by this much
USB_DX = 7.0                                      # the two USB-C sockets at the +y end, at ±USB_DX from the axis — TO CHECK
USB_PLUG_L = 22.0                                 # rigid length of a straight USB-C cable plug (flashing, lid open)
BAT_JST_Y = 22.0                                  # 2-pin 2.0 mm battery socket on the +x long edge, from the -y end — TO CHECK
PCB_Z1 = 15.0                                     # top of the PCB above the underside of the base (room for the plate screws)
# Board cradle printed with the base: 4 corner pillars (ledge under the PCB + side wall), end stops
# on the module side, one snap arm per long side. The headers are not soldered.
CRADLE_GAP, POST_T, POST_LEN, LEDGE, WALL_GAP = 0.25, 1.6, 6.0, 2.0, 0.8
SNAP_T, SNAP_W, SNAP_HOOK, SNAP_Y = 1.2, 5.0, 0.6, 14.0   # SNAP_Y: arm axis from the +y (USB) end
SNAP_STRAIN_MAX = 0.02                            # ASA
# 1 × 18650 holder with leads and JST plug: envelope including the cell (conservative), on a printed plinth
HOLDER_L, HOLDER_W, HOLDER_H = 78.0, 21.0, 20.0
HZ0 = 7.1                                          # top of the plinth above the underside of the base
CELL_D, CELL_L = 18.6, 70.0                        # protected cell (the board has no battery protection)
# Flexible (FPC) 2.4 GHz antenna with a u.FL cable, bonded to the inside of the -y end wall
ANT_L, ANT_W, ANT_T = 45.0, 10.0, 0.3
ANT_Z0 = 6.0                                       # bottom edge of the antenna above the underside of the base
ANT_CABLE = (100.0, 150.0)                         # cable lengths sold
ANT_CLEAR_MIN = 20.0                               # minimum distance to the cell
ANT_PLATE_MIN = 15.0                               # minimum distance to the plates and their screws — RSSI TO CHECK
# USB-C male to screw-terminal adapter (envelope, tongue in the socket) and TVS across VBUS/GND
ADAPT = (24.0, 15.0, 9.0)                          # length (y, tongue included), width (x), height (z) — TO CHECK
ADAPT_TONGUE = 6.5                                 # plug tongue inside the socket
TVS_D, TVS_L = 3.6, 7.6                            # P6KE6.2A, DO-15 body

# Enclosure (local frame: origin at the center of the underside, x = direction of travel, y = across)
IN_X, IN_Y, IN_H = 56.0, 110.0, 26.0
R_IN = 4.0
WALL = 3.0
FLOOR = 3.0                            # flat bottom, VHB-bonded to the deck
FLANGE = 8.0                           # flange overhang beyond the wall
FLANGE_T = 6.0
BASE_H = FLOOR + IN_H                  # 29
LID_T = 5.0                            # flat lid, no window: plate pockets, O-ring recesses
LID_CH = 2.0                           # top edge chamfer (not on the -x edge, where the ramp meets the lid)
# Solid silicone cord seal Ø2.4 mm in a groove of the flange: the groove must be wider and
# shallower than the cord (20–30% squeeze) without being too full (70–90% of the cross-section
# filled), otherwise the incompressible silicone prevents the lid from closing.
CORD_D = 2.4
GROOVE_C, GROOVE_W, GROOVE_D = 2.6, 3.0, 1.8   # centerline from the inner face, width, depth
SCREW_OFF = 7.0                        # screw axis from the inner face (outside the seal)
SCREW_L = 14.0                         # M3 × 14 countersunk screw
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

# Plates: stainless A4 (316L), flush in the lid, + plate on the +y side
PLATE_L, PLATE_W, PLATE_T = 54.0, 36.0, 1.0
PLATE_R = 3.0                          # corner radius
PLATE_Y = 28.0                         # plate centers at y = ±PLATE_Y (x = 0)
PLATE_GLUE = 0.3                       # neutral-cure silicone film under the plate
PLATE_CH = 0.4                         # 45° chamfer of the top edges (file)
PLATE_POCKET_GAP = 0.2                 # pocket clearance on each side
PLATE_PROUD = 0.15                     # flushness tolerance, taken as proud in the step scan
PLATE_END_MARGIN = 3.0                 # joint, countersink and chamfer at each plate end
CT_POS = ((-10.0, 36.0), (-10.0, -36.0))   # plate screws, enclosure frame (+ plate first)
CT_HEAD_D = 6.0                        # M3 countersunk head, 90°
CT_HOLE_D = 3.4
ORING = (3.0, 1.5)                     # inner diameter, cross-section
ORING_RECESS = (6.4, 1.1)              # diameter, depth in the lid underside
ORING_SQUEEZE = (0.20, 0.30)
CT_STACK_H, CT_STACK_R = 5.0, 4.0      # washer, ring terminal, toothed washer, nut and thread under the lid
LID_UNDER_PLATE_MIN = 2.0
# Blades: stainless spring strip 1.4310 (301)
BLADE_W, BLADE_T, BLADE_L, BLADE_CLAMP = 10.0, 0.30, 36.0, 14.0
BLADE_E, BLADE_RP02 = 190e3, 760.0     # MPa, TO CHECK with the supplier
BLADE_NOSE_L, BLADE_NOSE_ANG, BLADE_KNEE_R = 6.0, 25.0, 4.0
BLADE_EXIT_H = 3.0                     # slot exit above the holder's lowest point
DELTA_NOM = 4.2                        # flexion at the dock, robot at its nominal height
S_NOM, S_MIN = 3.0, 0.5                # lid top to lowest rigid point: nominal and hard-stop margin
F_MIN, F_MAX, SIGMA_FRAC = 0.4, 2.5, 0.7   # force limits (N), stress limit as fraction of BLADE_RP02
K_TOL = 0.20                           # stiffness scatter (thickness ±5 % → ±15 %, plus E)
WINDOW_MIN = 2.0                       # height window required on each side of the nominal
MU = 0.3                               # stainless on stainless, dry
EULER_MARGIN = 4.0                     # axial load at most P_cr / EULER_MARGIN
SLOT = (10.4, 0.8)                     # blade slot through the holder wall (width, thickness)
# Entry ramp bonded to the -x end of the lid
RAMP_ANGLE, RAMP_DEPTH, RAMP_FOOT, RAMP_Y = 25.0, 7.0, 1.2, 98.0
RAMP_BOND_MIN = (98.0, 7.0)            # bonded face (width, height) on the lid + lip end face
RAMP_TONGUE = (1.8, 2.0)               # tongue into the lid end (depth, height)
STEP_MAX = 0.3                         # largest step felt by the knee outside the ramp
# Stop errors for the checks
DOCK_DX, DOCK_DY = 24.0, 13.0          # longitudinal / lateral, TO MEASURE on site
OVERLAP_MIN = 2.0                      # blade on its plate, laterally

# Blade holder (local frame: origin at the center of the underside)
HOLD_X, HOLD_Y, HOLD_H, HOLD_CAP_T = 28.0, 104.0, 20.0, 3.0
HOLD_WALL, HOLD_FLOOR = 3.0, 2.0
HOLD_CAV_Y = 68.0                      # cavity length (y), between the rod bosses
HOLD_LEAD = 5.0                        # 45° lead-in chamfer on the lower +x edge
CLAMP_S = (6.5, 11.5)                  # M3 clamp screws along the blade from the exit (inner one: ring terminal)
PAN_R, PAN_H = 2.75, 2.0               # M3 pan head, in a counterbore perpendicular to the seat
CLAMP_FLOOR_MIN = 1.5                  # holder material between the screw head and the blade
PG7_D, PG7_WALL = 12.5, 3.0            # PG7 cable gland hole on the -x face, minimum wall around it
ROD_Y = 42.0                           # M6 rods at y = ±ROD_Y (outside the robot path)
ADJ_MIN = 8.0                          # minimum required adjustment travel, up and down
EXPLODE_LIFT = 170.0                   # exploded view: holder and bridge raised by this much

# Bridge bonded under the garage roof (local frame: origin under the roof, z negative downwards)
BR_X, BR_Y, BR_PLATE, BR_GAP, BR_BAR, BR_BOSS = 120.0, 56.0, 4.0, 30.0, 6.0, 10.0

# Placement on the robot and the station — TO MEASURE
DECK_Z = 228.0       # height of the top of the deck behind the camera
CAM_X0 = 255.0       # rear of the camera module (robot frame, robot center = 0)
CAM_TOP = 273.0      # top of the camera
CAM_GAP = 15.0       # gap between the rear of the camera and the front of the blade holder, robot docked
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
# Blade mechanics (linear beam theory, δ/L ≤ 0.2: about 5–10 % error)
# --------------------------------------------------------------------------------------
BLADE_D = DELTA_NOM + S_NOM + BLADE_EXIT_H          # free drop of the knee below the slot exit
THETA = math.asin(BLADE_D / BLADE_L)                # slope of the straight blade (seat angle)
BLADE_I = BLADE_W * BLADE_T ** 3 / 12
BLADE_K = 3 * BLADE_E * BLADE_I / BLADE_L ** 3      # N/mm, perpendicular to the blade
BLADE_KV = BLADE_K / math.cos(THETA) ** 2           # N/mm, vertical at the knee
# The lid pushes the knee straight up and the knee slides freely along it: a vertical flexion δ
# is a deflection u = δ/cosθ perpendicular to the blade, and only F·cosθ bends it (F·cosθ = K·u),
# hence F = K·δ/cos²θ.


def delta_at(s: float) -> float:
    """Flexion of the blade (mm) when the lid top is s mm under the holder's lowest point."""
    return BLADE_D - BLADE_EXIT_H - s


def force(delta: float, k: float = 1.0) -> float:
    return max(0.0, BLADE_KV * k * delta)


def stress(delta: float) -> float:
    """Bending stress at the clamp (MPa), from the perpendicular deflection u = δ/cosθ."""
    return 3 * BLADE_E * BLADE_T * max(0.0, delta) / math.cos(THETA) / (2 * BLADE_L ** 2)


# Knee position when docked at the nominal height: the tip moves perpendicular to the blade,
# so it also moves δ·tanθ towards -x. The plate centers (x = 0) sit under it.
BLADE_EXIT_X = BLADE_L * math.cos(THETA) + DELTA_NOM * math.tan(THETA)   # enclosure frame
HOLD_CX = BLADE_EXIT_X + HOLD_X / 2                                      # holder center, enclosure frame

# --------------------------------------------------------------------------------------
# Printed parts
# --------------------------------------------------------------------------------------
BODY_X, BODY_Y = IN_X + 2 * WALL, IN_Y + 2 * WALL
FL_X, FL_Y = BODY_X + 2 * FLANGE, BODY_Y + 2 * FLANGE
R_BODY, R_FL = R_IN + WALL, R_IN + WALL + FLANGE
LID_X, LID_Y = FL_X + 2 * (LIP_GAP + LIP_T), FL_Y + 2 * (LIP_GAP + LIP_T)
R_LID = R_FL + LIP_GAP + LIP_T
FL_Z0 = BASE_H - FLANGE_T              # underside of the flange
RAMP_LEN = RAMP_DEPTH / math.tan(math.radians(RAMP_ANGLE))
RAMP_X0 = -LID_X / 2                   # ramp against the -x end face of the lid

# Layout of the electronics (enclosure frame). The board sits low so that the plate screws,
# their ring terminals and the wires have 5 mm under the lid.
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
JST_Y = BY0 + BAT_JST_Y
ANT_Y0 = -IN_Y / 2 + 0.02                          # antenna bonded to the -y end wall
ANT_FEED = (-ANT_L / 2 + 2.0, ANT_Y0 + ANT_T + 0.6, ANT_Z0 + ANT_W / 2)
QX = BXC + USB_DX                                  # native USB-C socket (contact adapter), holder side
AD_Y0 = BY1 + 0.5 - ADAPT_TONGUE                   # adapter: tongue in the socket, body outside
AD_Y1 = AD_Y0 + ADAPT[0]
AD_Z1 = USB_Z + ADAPT[2] / 2
TERM_Y = AD_Y1 - 3.0                               # screw terminals at the far end of the adapter


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


def plate_cs(grow: float = 0.0) -> CS:
    return rr(PLATE_L + 2 * grow, PLATE_W + 2 * grow, PLATE_R + grow)


def plate_centers():
    return [(0.0, PLATE_Y), (0.0, -PLATE_Y)]          # + plate first


def countersink(x: float, y: float, top: float) -> M:
    """90° countersink for the M3 head, from the top surface down to the Ø3.4 hole."""
    r0, r1 = CT_HOLE_D / 2, CT_HEAD_D / 2 + 0.2
    return union(M.cylinder(r1 - r0, r0, r1, 32).translate((x, y, top - (r1 - r0))), cyl(r1, top - 0.01, top + 1, x, y, 32))


def glyph(sign: str, x: float, y: float, size: float = 5.0, stroke: float = 0.8) -> CS:
    """Engraved + or − (in the xy plane)."""
    bar = CS.square((size, stroke), True)
    g = bar if sign == "-" else bar + CS.square((stroke, size), True)
    return g.translate((x, y))


def make_lid() -> M:
    """Lid in assembled position (z = 0 on the flange), drip lip pointing down.

    Flat 5 mm lid with two plate pockets, the plate screw holes with their countersink and an
    O-ring recess underneath, + / − marks and the forward arrow. Its -x top edge is square so the
    entry ramp meets it; a groove in that end face takes the tongue of the ramp.
    """
    ch = LID_CH
    lid = M.batch_hull([
        ext(rr(LID_X, LID_Y, R_LID), 0, LID_T - ch),
        ext(rr(LID_X - 2 * ch, LID_Y - 2 * ch, R_LID - ch), LID_T - 0.01, LID_T),
    ])
    square_end = ext(rr(LID_X, LID_Y, R_LID), 0, LID_T) ^ box(-LID_X / 2 - 1, -LID_X / 2 + 3 * ch,
                                                              -RAMP_Y / 2 - 1, RAMP_Y / 2 + 1, -1, LID_T + 1)
    lip = ext(rr(LID_X, LID_Y, R_LID) - rr(FL_X + 2 * LIP_GAP, FL_Y + 2 * LIP_GAP, R_FL + LIP_GAP), -LIP_H, 0.01)
    lid = union(lid, square_end, lip)
    cuts = []
    for (x, y) in screw_points():
        cuts.append(cyl(1.7, -1, LID_T + 1, x, y, 32))
        cuts.append(M.cylinder(1.9, 1.7, 3.6, 32).translate((x, y, LID_T - 1.9)))
        cuts.append(cyl(3.6, LID_T - 0.01, LID_T + 1, x, y, 32))
    pocket = PLATE_T + PLATE_GLUE
    for (px, py) in plate_centers():
        cuts.append(ext(plate_cs(PLATE_POCKET_GAP).translate((px, py)), LID_T - pocket, LID_T + 1))
    for (x, y) in CT_POS:
        cuts.append(cyl(CT_HOLE_D / 2, -1, LID_T + 1, x, y, 32))
        cuts.append(countersink(x, y, LID_T))
        cuts.append(cyl(ORING_RECESS[0] / 2, -1, ORING_RECESS[1], x, y, 32))
    # groove for the ramp tongue in the -x end face
    tz = LID_T / 2 - RAMP_TONGUE[1] / 2
    cuts.append(box(-LID_X / 2 - 1, -LID_X / 2 + RAMP_TONGUE[0] + 0.2, -RAMP_Y / 2 + 4, RAMP_Y / 2 - 4,
                    tz - 0.15, tz + RAMP_TONGUE[1] + 0.15))
    # engraved marks: + / − beside each plate, arrow pointing forward
    g = 0.4
    marks = glyph("+", 0.0, PLATE_Y + PLATE_W / 2 + 6.0) + glyph("-", 0.0, -PLATE_Y - PLATE_W / 2 - 6.0)
    marks = marks + CS([[(FL_X / 2 - 6, 0), (FL_X / 2 - 12, 5), (FL_X / 2 - 12, -5)]])
    cuts.append(ext(marks, LID_T - g, LID_T + 1))
    return check(lid - union(*cuts), "lid")


def make_ramp() -> M:
    """Entry ramp, lid frame (z = 0 on the flange): wedge bonded to the -x end face of the lid and lip.

    Top level with the lid top, 30° down to a foot RAMP_DEPTH under the plate plane; a tongue goes
    into the groove of the lid end for the shear. Printed on its flat bottom, ramp up.
    """
    x0 = RAMP_X0
    z_top, z_foot = LID_T, LID_T - RAMP_DEPTH
    z_bot = z_foot - RAMP_FOOT
    wedge = prism_xz([(x0, z_top), (x0 - RAMP_LEN, z_foot), (x0 - RAMP_LEN, z_bot), (x0, z_bot)],
                     -RAMP_Y / 2, RAMP_Y / 2)
    tz = LID_T / 2 - RAMP_TONGUE[1] / 2
    tongue = box(x0 - 0.01, x0 + RAMP_TONGUE[0], -RAMP_Y / 2 + 4.3, RAMP_Y / 2 - 4.3, tz, tz + RAMP_TONGUE[1])
    return check(union(wedge, tongue), "ramp")


def make_plates() -> tuple[M, M]:
    """Stainless plates in the lid pockets (lid frame), top flush with the lid; chamfered top edges, countersunk."""
    top = LID_T
    plates = []
    for (px, py) in plate_centers():
        p = M.batch_hull([ext(plate_cs().translate((px, py)), top - PLATE_T, top - PLATE_CH),
                          ext(plate_cs(-PLATE_CH).translate((px, py)), top - 0.01, top)])
        plates.append(p)
    plate = union(*plates) - union(*[countersink(x, y, top) for (x, y) in CT_POS],
                                   *[cyl(CT_HOLE_D / 2, top - 2, top + 1, x, y, 32) for (x, y) in CT_POS])
    glue = union(*[ext(plate_cs().translate((px, py)), top - PLATE_T - PLATE_GLUE, top - PLATE_T - 0.01)
                   for (px, py) in plate_centers()]) - union(*[cyl(ORING_RECESS[0] / 2, -5, top + 1, x, y, 32) for (x, y) in CT_POS])
    return check(plate, "plates"), check(glue, "plate film")


def make_plate_hardware() -> dict[str, M]:
    """Countersunk M3 screw, O-ring, washer, ring terminal, toothed washer and nut per plate (lid frame)."""
    top = LID_T
    r_head = CT_HEAD_D / 2
    screws, orings, terms, nuts = [], [], [], []
    for i, (x, y) in enumerate(CT_POS):
        screws.append(union(M.cylinder(r_head - 1.5, 1.5, r_head, 32).translate((x, y, top - (r_head - 1.5))),
                            cyl(1.5, top + 0.0 - 10.0, top - (r_head - 1.5) + 0.01, x, y, 24)))
        orings.append(cyl(ORING[0] / 2 + ORING[1], 0.0, ORING_RECESS[1], x, y, 32) - cyl(ORING[0] / 2 + 0.05, -1, 2, x, y, 32))
        z = 0.0
        washer = cyl(3.5, z - 0.5, z, x, y, 32)
        # ring terminal: ring + tab pointing to +y (towards the adapter) for both plates
        term = union(cyl(3.2, z - 1.0, z - 0.5, x, y, 32), box(x - 1.6, x + 1.6, y, y + 7.0, z - 1.0, z - 0.5))
        tooth = cyl(3.0, z - 1.5, z - 1.0, x, y, 32)
        nut = hexprism(NUT_M3_FLATS, z - 3.9, z - 1.5, x, y)
        terms.append(term - cyl(1.6, -5, 1, x, y, 24))
        nuts.append(union(washer, tooth, nut) - cyl(1.55, -5, 1, x, y, 24))
    return {"ct_screw": union(*screws), "oring": union(*orings), "ring_term": union(*terms), "ct_nut": union(*nuts)}


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


# Blade holder frame: origin at the center of the underside (lowest rigid point), blades leave
# through the -x wall at y = ±PLATE_Y.
HX_EXIT = -HOLD_X / 2


def seat_z(x: float) -> float:
    """Height of the blade seat (blade lower face) at x, holder frame."""
    return BLADE_EXIT_H + (x - HX_EXIT) * math.tan(THETA)


def along_blade(s: float, h: float = 0.0) -> tuple[float, float]:
    """Point at distance s along the clamped blade from the exit and h above its lower face (holder frame, x z)."""
    return (HX_EXIT + s * math.cos(THETA) - h * math.sin(THETA), BLADE_EXIT_H + s * math.sin(THETA) + h * math.cos(THETA))


def seat_normal_cyl(r: float, s: float, h0: float, h1: float, y: float, seg: int = 32) -> M:
    """Cylinder perpendicular to the seat, at s along the blade, from h0 to h1 above the blade lower face."""
    x, z = along_blade(s, h0)
    return M.cylinder(h1 - h0, r, r, seg).rotate((0, -math.degrees(THETA), 0)).translate((x, y, z))


def head_depth(s: float) -> float:
    """Depth of the clamp screw head under the blade (along the seat normal), its lowest edge 0.3 mm above the underside."""
    z = along_blade(s)[1]
    return (z - 0.3 - PAN_R * math.sin(THETA)) / math.cos(THETA)


def make_holder_body() -> M:
    """Blade holder, printed floor down.

    Cavity closed by the cap; captive M6 nuts and blind holes for the rods; at each blade a seat
    inclined by THETA and a slot through the -x wall; two M3 clamp screws per blade from below
    (heads in counterbores); PG7 gland on the -x face; weep hole; 45° lead-in on the lower +x edge.
    """
    h, hx, lead = HOLD_H, HOLD_X / 2, HOLD_LEAD
    prof = profile_xz([(-hx, 0), (hx - lead, 0), (hx, lead), (hx, h), (-hx, h)], HOLD_Y)
    body = prof ^ ext(rr(HOLD_X, HOLD_Y, 4), -1, h + 1)
    cav_x0, cav_x1 = -hx + HOLD_WALL, hx - HOLD_WALL
    cuts = [ext(rr(cav_x1 - cav_x0, HOLD_CAV_Y, 2).translate(((cav_x0 + cav_x1) / 2, 0)), HOLD_FLOOR, h + 1)]
    for y in (-ROD_Y, ROD_Y):
        cuts.append(hexprism(10.2, h - 5.5, h + 1, 0, y))        # captive M6 nut
        cuts.append(cyl(3.2, h - 14, h + 1, 0, y, 32))           # blind hole: the rod does not stick out underneath
    body = body - union(*cuts)
    # blade seats: solid under the inclined seat, from the -x wall to the end of the clamp
    s_end = BLADE_CLAMP + 1.0
    x_end = along_blade(s_end)[0]
    seats, cuts = [], []
    for py in (PLATE_Y, -PLATE_Y):
        seats.append(prism_xz([(cav_x0 - 0.01, 0.01), (x_end, 0.01), (x_end, seat_z(x_end)), (cav_x0 - 0.01, seat_z(cav_x0 - 0.01))],
                              py - 6.5, py + 6.5))
    body = union(body, *seats)
    for py in (PLATE_Y, -PLATE_Y):
        # slot through the -x wall along the blade (blade lower face 0.25 above the slot floor)
        sx0, sz0 = along_blade(-3.0, -0.25)
        slot = box(0, HOLD_WALL / math.cos(THETA) + 6.0, -SLOT[0] / 2, SLOT[0] / 2, 0, SLOT[1])
        cuts.append(slot.rotate((0, -math.degrees(THETA), 0)).translate((sx0, py, sz0)))
        for s in CLAMP_S:
            cuts.append(seat_normal_cyl(1.65, s, -12.0, 3.0, py, 24))              # Ø3.3 hole
            cuts.append(seat_normal_cyl(PAN_R + 0.25, s, -12.0, -(head_depth(s) - PAN_H), py))   # pan head counterbore
    # PG7 gland on the -x face, weep hole at the lowest point of the cavity
    cuts.append(cyl_x(PG7_D / 2, -hx - 1, cav_x0 + 1, 0.0, h / 2, 48))
    cuts.append(cyl(1.5, -1, HOLD_FLOOR + 1, cav_x1 - 3.0, 0.0, 24))
    # engraved + / − on the -x face above each slot
    for sign, py in (("+", PLATE_Y), ("-", -PLATE_Y)):
        g = glyph(sign, 0.0, 0.0, 4.0, 0.8)
        cuts.append(M.extrude(g, 1.6).rotate((0, 90, 0)).translate((-hx - 1.0, py, h - 4.5)))
    return check(body - union(*cuts), "holder_body")


def make_holder_cap() -> M:
    """Holder cap in assembled position (z = 0 on the body)."""
    cav_x = HOLD_X - 2 * HOLD_WALL
    cap = ext(rr(HOLD_X, HOLD_Y, 4), 0, HOLD_CAP_T)
    tongue = ext(rr(cav_x - 0.8, HOLD_CAV_Y - 0.8, 1.6), -2, 0.01)
    cuts = [cyl(3.3, -3, HOLD_CAP_T + 1, 0, y, 32) for y in (-ROD_Y, ROD_Y)]
    cuts.append(ext(rr(cav_x - 6, HOLD_CAV_Y - 6, 1), -3, -0.01))
    return check(union(cap, tongue) - union(*cuts), "holder_cap")


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


def blade_centerline(delta: float) -> list[tuple[float, float]]:
    """Blade mid-plane in the holder frame (x, z), from the clamp end to the nose tip, flexed by delta.

    Clamped part straight on the seat; free part as a cantilever loaded at its tip (deflection
    u (3 s² L − s³) / (2 L³) perpendicular to the blade, u = delta / cos θ); knee arc of radius
    BLADE_KNEE_R; nose BLADE_NOSE_L turned up by BLADE_NOSE_ANG.
    """
    h = BLADE_T / 2
    pts = [along_blade(s, h) for s in np.linspace(BLADE_CLAMP, 0.0, 8)]
    u = delta / math.cos(THETA)
    L = BLADE_L
    d = np.array([-math.cos(THETA), -math.sin(THETA)])     # along the free blade, towards -x
    n = np.array([-math.sin(THETA), math.cos(THETA)])      # upward normal
    p0 = np.array(along_blade(0.0, h))
    for s in np.linspace(0.0, L, 25)[1:]:
        w = u * (3 * s ** 2 * L - s ** 3) / (2 * L ** 3)
        pts.append(tuple(p0 + s * d + w * n))
    # knee: arc tangent to the tip of the deflected blade (tip slope reduced by 3u/(2L)), turning up into the nose
    phi = THETA - 1.5 * u / L                              # tip slope below horizontal, towards -x
    tip = np.array(pts[-1])
    up = np.array([-math.sin(phi), math.cos(phi)])         # towards the centre of the knee
    rk = BLADE_KNEE_R + h                                  # bent around a pin of radius BLADE_KNEE_R (upper face on the pin)
    centre = tip + rk * up
    a0 = math.atan2(tip[1] - centre[1], tip[0] - centre[0])
    nose = math.radians(BLADE_NOSE_ANG)
    a1 = math.atan2(-math.cos(nose), -math.sin(nose))      # radius vector where the tangent turns into the nose
    if a1 > a0:
        a1 -= 2 * math.pi
    for a in np.linspace(a0, a1, 10)[1:]:
        pts.append(tuple(centre + rk * np.array([math.cos(a), math.sin(a)])))
    end = np.array(pts[-1])
    pts.append(tuple(end + BLADE_NOSE_L * np.array([-math.cos(nose), math.sin(nose)])))
    return [(float(x), float(z)) for x, z in pts]


def strip(points, y: float, w: float, t: float) -> M:
    """Thin strip of width w (y) and thickness t along a polyline in the xz plane."""
    cubes = [M.cube((t, w, t), True).translate((x, y, z)) for x, z in points]
    return union(*[M.batch_hull([a, b]) for a, b in zip(cubes, cubes[1:])])


def make_blades(delta: float) -> dict[str, M]:
    """Blades, clamp screws, nuts and ring terminals (holder frame)."""
    blades, screws, terms = [], [], []
    line = blade_centerline(delta)
    for py in (PLATE_Y, -PLATE_Y):
        blades.append(strip(line, py, BLADE_W, BLADE_T))
        for s in CLAMP_S:
            d = head_depth(s)
            head = seat_normal_cyl(PAN_R, s, -d, -d + PAN_H, py)
            shank = seat_normal_cyl(1.5, s, -d + PAN_H - 0.01, 7.5, py, 24)
            nut = M.cylinder(2.4, 3.17, 3.17, 6).rotate((0, -math.degrees(THETA), 0))
            x, z = along_blade(s, BLADE_T + (1.3 if s == CLAMP_S[-1] else 0.5))
            screws.append(union(head, shank, nut.translate((x, py, z))))
            wx, wz = along_blade(s, BLADE_T)
            screws.append(M.cylinder(0.5, 3.5, 3.5, 32).rotate((0, -math.degrees(THETA), 0)).translate((wx, py, wz)))
            if s == CLAMP_S[-1]:
                tx, tz = along_blade(s, BLADE_T + 0.5)
                ring = M.cylinder(0.8, 3.2, 3.2, 32) - M.cylinder(3, 1.6, 1.6, 24).translate((0, 0, -1))
                tab = box(-1.6, 1.6, 0, 7.0, 0, 0.8).rotate((0, 0, 180 if py < 0 else 0))
                terms.append(union(ring, tab).rotate((0, -math.degrees(THETA), 0)).translate((tx, py, tz)))
    return {"blade": union(*blades), "clamp_screw": union(*screws), "blade_term": union(*terms)}


def make_gland() -> dict[str, M]:
    """PG7 gland and the 2 × 0.75 mm² cable (holder frame), towards the station tower (-x)."""
    hx, zc = HOLD_X / 2, HOLD_H / 2
    return {
        "gland": union(cyl_x(8.5, -hx - 4, -hx, 0.0, zc, 6), cyl_x(6.0, -hx - 12, -hx - 4, 0.0, zc, 32)),
        "cable": union(cyl_x(3.0, -hx - 60, -hx + 4, 0.0, zc, 16)),
        "cable_in": union(tube([(-hx + 4, 0.0, zc), (-4.0, 0.0, zc), (-4.0, 12.0, zc), (along_blade(CLAMP_S[-1], 3)[0], PLATE_Y, along_blade(CLAMP_S[-1], 3)[1])], 0.9),
                          tube([(-hx + 4, 0.0, zc), (-4.0, 0.0, zc), (-4.0, -12.0, zc), (along_blade(CLAMP_S[-1], 3)[0], -PLATE_Y, along_blade(CLAMP_S[-1], 3)[1])], 0.9)),
    }


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
        box(CAM_X0 + 7, CAM_X0 + 57, -40, 40, CAM_TOP - 7, CAM_TOP),
    ])
    parts["camera"] = cam
    parts["camera_lens"] = cyl_x(9, CAM_X0 + 55, CAM_X0 + 66, 0, CAM_TOP - 15, 32)
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


def contact_wire_path(i: int) -> list[tuple[float, float, float]]:
    """Wire from the ring terminal of plate screw i to its screw terminal on the adapter (enclosure frame).

    Runs 1 mm under the stack, above the board and the u.FL clearance, then down onto the terminal.
    """
    x, y = CT_POS[i]
    z0 = BASE_H - 1.5
    zr = BASE_H - CT_STACK_H - 1.2
    tx = QX + (-3.0 if i == 0 else 3.0)
    return [(x, y + 6.0, z0), (x, y + 6.0, zr), (x, TERM_Y if y < TERM_Y else y + 6.0, zr), (tx, TERM_Y, zr),
            (tx, TERM_Y, AD_Z1 + 0.5)]


def make_board() -> dict[str, M]:
    """Olimex ESP32-S3-DevKit-Lipo-EA in its cradle, 18650 holder, antenna, contact adapter, TVS and wires (enclosure frame).

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
    # FPC antenna on the -y end wall, as far as possible from the plates and the cell
    parts["antenna"] = box(-ANT_L / 2, ANT_L / 2, ANT_Y0, ANT_Y0 + ANT_T, ANT_Z0, ANT_Z0 + ANT_W)
    parts["ant_cable"] = tube(antenna_cable_path(), 0.6)
    # USB-C male to screw-terminal adapter in the native socket: tongue, plug shell, terminal board
    aw = ADAPT[1] / 2
    parts["adapter"] = union(
        box(QX - 4.1, QX + 4.1, AD_Y0, BY1 + 0.5, USB_Z - 1.2, USB_Z + 1.2),
        box(QX - 5.0, QX + 5.0, BY1 + 0.5, BY1 + 8.0, USB_Z - 2.5, USB_Z + 2.5),
        box(QX - aw, QX + aw, BY1 + 8.0, AD_Y1, USB_Z - ADAPT[2] / 2, USB_Z - ADAPT[2] / 2 + 1.6),
        box(QX - aw, QX + aw, AD_Y1 - 7.0, AD_Y1, USB_Z - ADAPT[2] / 2 + 1.6, AD_Z1))
    # TVS across the two terminals, lying on the terminal block
    parts["tvs"] = union(cyl_x(TVS_D / 2, QX - TVS_L / 2, QX + TVS_L / 2, TERM_Y - 5.5, AD_Z1 - 5.5 + TVS_D / 2, 24),
                         cyl_x(0.4, QX - 4.0, QX + 4.0, TERM_Y - 5.5, AD_Z1 - 5.5 + TVS_D / 2, 12))
    parts["wire_pos"] = tube(contact_wire_path(0), 1.0)
    parts["wire_neg"] = tube(contact_wire_path(1), 1.0)
    return parts


def board_envelopes() -> dict[str, M]:
    """Check-only volumes: undocumented components, the flashing plug (lid open), plate screw stacks."""
    fx = BXC - USB_DX
    return {
        "components above the board": box(BX0 + COMP_EDGE, BX1 - COMP_EDGE, BY0, BY1, PCB_Z1, PCB_Z1 + COMP_TOP),
        "components under the board": box(BX0 + LEDGE + 0.5, BX1 - LEDGE - 0.5, BY0, BY1, PCB_Z0 - COMP_BOT, PCB_Z0),
        "u.FL plug clearance": box(BXC - MODULE[0] / 2, BXC + MODULE[0] / 2, BY0, BY0 + MODULE[1], MOD_Z1, MOD_Z1 + UFL_CLEAR),
        "flashing USB-C plug": box(fx - 6.2, fx + 6.2, BY1 + 0.5, BY1 + 0.5 + USB_PLUG_L, USB_Z - 3.5, USB_Z + 3.5),
        "plate screw stacks": union(*[cyl(CT_STACK_R, BASE_H - CT_STACK_H, BASE_H - 0.02, x, y, 32) for (x, y) in CT_POS]),
    }


# --------------------------------------------------------------------------------------
# Surface scan under the knee
# --------------------------------------------------------------------------------------

def top_heights(m: M, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
    """Highest z of the solid at each (x, y) of the grid (-inf where there is nothing)."""
    tm = to_trimesh(m)
    tri = tm.triangles
    nz = tm.face_normals[:, 2]
    H = np.full((len(xs), len(ys)), -np.inf)
    for t in tri[nz > 1e-6]:
        x0, x1 = t[:, 0].min(), t[:, 0].max()
        y0, y1 = t[:, 1].min(), t[:, 1].max()
        i0, i1 = np.searchsorted(xs, x0 - 1e-9), np.searchsorted(xs, x1 + 1e-9)
        j0, j1 = np.searchsorted(ys, y0 - 1e-9), np.searchsorted(ys, y1 + 1e-9)
        if i0 >= i1 or j0 >= j1:
            continue
        X, Y = np.meshgrid(xs[i0:i1], ys[j0:j1], indexing="ij")
        (ax, ay, az), (bx, by, bz), (cx, cy, cz) = t
        det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(det) < 1e-12:
            continue
        l1 = ((by - cy) * (X - cx) + (cx - bx) * (Y - cy)) / det
        l2 = ((cy - ay) * (X - cx) + (ax - cx) * (Y - cy)) / det
        l3 = 1 - l1 - l2
        inside = (l1 >= -1e-9) & (l2 >= -1e-9) & (l3 >= -1e-9)
        Z = np.where(inside, l1 * az + l2 * bz + l3 * cz, -np.inf)
        H[i0:i1, j0:j1] = np.maximum(H[i0:i1, j0:j1], Z)
    return H


def knee_track(h: np.ndarray, dx: float, r: float) -> np.ndarray:
    """Height of the lowest point of a rigid circle of radius r rolled over the profile h (step dx)."""
    n = int(round(r / dx))
    k = np.full_like(h, -np.inf)
    for j in range(-n, n + 1):
        lift = math.sqrt(max(0.0, r * r - (j * dx) ** 2)) - r
        shifted = np.full_like(h, -np.inf)
        if j >= 0:
            shifted[: len(h) - j] = h[j:]
        else:
            shifted[-j:] = h[: len(h) + j]
        k = np.maximum(k, shifted + lift)
    return k


def rr_distance(px: float, py: float, cx: float, cy: float, hx: float, hy: float, r: float) -> float:
    """Signed distance from a point to a rounded rectangle (negative inside)."""
    qx, qy = abs(px - cx) - (hx - r), abs(py - cy) - (hy - r)
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r


def chord_y(x: float, cy: float, hx: float, hy: float, r: float) -> tuple[float, float] | None:
    """y interval of a rounded rectangle (centered on x = 0, y = cy) along the line x = const."""
    ax = abs(x)
    if ax > hx:
        return None
    if ax <= hx - r:
        return (cy - hy, cy + hy)
    d = hy - r + math.sqrt(max(0.0, r * r - (ax - (hx - r)) ** 2))
    return (cy - d, cy + d)


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
    if HZ0 < FLOOR + 0.5 or HZ0 + HOLDER_H > BASE_H - 0.5:
        raise SystemExit(f"Battery holder {HOLDER_H:.0f} mm does not fit between the floor and the lid (plinth top {HZ0:.1f} mm)")
    if PCB_Z0 - COMP_BOT < FLOOR + 0.5:
        raise SystemExit("Board components too tall for the inner height")
    base, lid = make_base(), make_lid()
    ramp = make_ramp()
    plates, plate_film = make_plates()
    plate_hw = make_plate_hardware()
    sole, cord, box_hw = make_sole(), make_cord(), make_box_hardware()
    deck_dmin, deck_dmax = sole_span()
    holder_body, holder_cap, bracket = make_holder_body(), make_holder_cap(), make_bracket()
    holder = union(holder_body, holder_cap.translate((0, 0, HOLD_H)))
    blades = make_blades(DELTA_NOM)
    gland = make_gland()

    # --- world positions (robot docked at its nominal height) ---
    box_x = CAM_X0 - CAM_GAP - (HOLD_CX + HOLD_X / 2)
    deck_top = DECK_Z - deck_dmin                          # highest point of the deck under the enclosure
    sole_top = deck_top + VHB_T + SOLE_MIN if sole is not None else None
    box_z = (sole_top if sole is not None else deck_top) + VHB_T
    lid_top = box_z + BASE_H + LID_T
    sup_z = lid_top + S_NOM                                # underside of the holder (lowest rigid point)
    cap_top = sup_z + HOLD_H + HOLD_CAP_T
    sup_top_hw = cap_top + WASHER_M6_T + NUT_M6_H          # clamping nut on the holder cap
    bar_top = ROOF_Z - BR_PLATE - BR_GAP
    bar_bot = bar_top - BR_BAR
    boss_bottom = bar_bot - BR_BOSS
    low_nut_bottom = boss_bottom - WASHER_M6_T - NUT_M6_H  # adjustment nut under the bridge
    rod_bottom = sup_z + HOLD_H - 13.0                     # 14 mm blind hole, 1 mm margin
    rod_top_min = bar_top + WASHER_M6_T + 2 * NUT_M6_H + ROD_TOP_MARGIN
    rod_len = math.ceil(rod_top_min - rod_bottom)
    rod_top = rod_bottom + rod_len
    hold_wx = box_x + HOLD_CX                              # holder center (world)

    robot = make_robot()
    station = make_station()
    board = make_board()

    def at_box(m: M, z: float) -> M:
        return m.translate((box_x, BOX_Y, z))

    def at_hold(m: M, z: float = 0.0) -> M:
        return m.translate((hold_wx, BOX_Y, sup_z + z))

    world_base = at_box(base, box_z)
    world_lid = at_box(lid, box_z + BASE_H)
    world_ramp = at_box(ramp, box_z + BASE_H)
    rigid_support = union(holder, blades["clamp_screw"], gland["gland"], gland["cable"])
    world_rigid = at_hold(rigid_support)
    world_bracket = bracket.translate((hold_wx, BOX_Y, ROOF_Z))

    # --- blade mechanics ---
    errors = []
    d_nom, d_up, d_stop = DELTA_NOM, delta_at(S_MIN), delta_at(0.0)
    d_low = F_MIN / (BLADE_KV * (1 - K_TOL))               # softest blade still pressing F_MIN
    win_up, win_down = S_NOM - S_MIN, d_nom - d_low
    f_low_req = force(d_nom - WINDOW_MIN, 1 - K_TOL)       # softest blade at the required lower limit
    f_up_max = force(d_up, 1 + K_TOL)                      # stiffest blade at the upper limit
    sigma_stop = stress(d_stop)
    sigma_lim = SIGMA_FRAC * BLADE_RP02
    p_cr = math.pi ** 2 * BLADE_E * BLADE_I / (4 * BLADE_L ** 2)
    f_stop_hi = force(d_stop, 1 + K_TOL)
    axial = MU * f_stop_hi + f_stop_hi * math.tan(math.radians(RAMP_ANGLE))
    if f_low_req < F_MIN - 1e-9 or f_up_max > F_MAX + 1e-9:
        errors.append(f"blade force out of {F_MIN}–{F_MAX} N: {f_low_req:.2f} N at {WINDOW_MIN:.1f} mm under the nominal "
                      f"(−{K_TOL:.0%} stiffness), {f_up_max:.2f} N at the upper limit (+{K_TOL:.0%})")
    if sigma_stop > sigma_lim + 1e-9:
        errors.append(f"blade stress {sigma_stop:.0f} MPa at the hard stop, above {SIGMA_FRAC:.2f} × {BLADE_RP02:.0f} = {sigma_lim:.0f} MPa")
    if win_down < WINDOW_MIN - 1e-9 or win_up < WINDOW_MIN - 1e-9:
        errors.append(f"height window +{win_up:.1f} / −{win_down:.1f} mm, less than {WINDOW_MIN:.1f} mm on one side")
    if axial > p_cr / EULER_MARGIN + 1e-9:
        errors.append(f"blade axial load {axial:.2f} N above P_cr / {EULER_MARGIN:.0f} = {p_cr / EULER_MARGIN:.2f} N (buckling)")
    if RAMP_DEPTH < d_up + 0.3 - 1e-6:
        errors.append(f"ramp {RAMP_DEPTH:.1f} mm deep: less than the flexion at the upper limit {d_up:.1f} + 0.3 mm (the knee would hit its foot)")

    # --- plates: stop tolerances, no bridging, layout ---
    p_hx, p_hy = PLATE_L / 2, PLATE_W / 2
    worst_overlap, wrong_touch = 1e9, []
    for dx in np.linspace(-DOCK_DX, DOCK_DX, 49):
        for dy in np.linspace(-DOCK_DY, DOCK_DY, 53):
            kx = -dx                                        # knee in the enclosure frame
            for own, other in ((PLATE_Y, -PLATE_Y), (-PLATE_Y, PLATE_Y)):
                ky = own + dy
                b0, b1 = ky - BLADE_W / 2, ky + BLADE_W / 2
                usable = chord_y(kx, own, p_hx - PLATE_END_MARGIN, p_hy - PLATE_CH, PLATE_R)
                ov = -1.0 if usable is None else min(b1, usable[1]) - max(b0, usable[0])
                worst_overlap = min(worst_overlap, ov)
                ch = chord_y(kx, other, p_hx + PLATE_POCKET_GAP, p_hy + PLATE_POCKET_GAP, PLATE_R)
                if ch is not None and min(b1, ch[1]) - max(b0, ch[0]) > -1.0:
                    wrong_touch.append((dx, dy))
    if worst_overlap < OVERLAP_MIN - 1e-9:
        errors.append(f"blade on its plate by {worst_overlap:.1f} mm only (minimum {OVERLAP_MIN:.0f}) "
                      f"within ±{DOCK_DX:.0f} mm along and ±{DOCK_DY:.0f} mm across")
    if wrong_touch:
        errors.append(f"a blade comes within 1 mm of the other plate ({len(wrong_touch)} stop positions)")
    plate_gap = 2 * PLATE_Y - PLATE_W
    wrong_plate = 2 * PLATE_Y - PLATE_W / 2 - BLADE_W / 2
    if plate_gap < BLADE_W + 6.0 - 1e-9:
        errors.append(f"plate gap {plate_gap:.1f} mm: less than the blade width + 6 = {BLADE_W + 6:.0f} mm")
    if wrong_plate < 2 * DOCK_DY - 1e-9:
        errors.append(f"wrong-plate threshold {wrong_plate:.1f} mm: less than 2 × the lateral stop error ({2 * DOCK_DY:.0f} mm)")
    flat_hx, flat_hy = LID_X / 2 - LID_CH, LID_Y / 2 - LID_CH
    plate_margin = min(min(flat_hx - p_hx, flat_hy - (abs(py) + p_hy)) for (_, py) in plate_centers())
    head_gap = min(rr_distance(sx, sy, 0.0, py, p_hx, p_hy, PLATE_R) - 3.6
                   for (sx, sy) in screw_points() for (_, py) in plate_centers())
    plate_metal = union(at_box(plates, BASE_H), at_box(plate_hw["ct_screw"], BASE_H)).translate((-box_x, -BOX_Y, 0))
    plate_zone = union(plate_metal, board_envelopes()["plate screw stacks"])
    gap_plate_cord = plate_zone.min_gap(cord, 20.0)
    gap_plate_vent = plate_zone.min_gap(box_hw["vent"], 40.0)
    if plate_margin < 2.0:
        errors.append(f"plate {plate_margin:.1f} mm from the edge of the flat lid top (minimum 2)")
    if head_gap < 4.0:
        errors.append(f"plate {head_gap:.1f} mm from a flange screw head (minimum 4)")
    if gap_plate_cord < 3.0 or gap_plate_vent < 3.0:
        errors.append(f"plate or its screw stack too close to the seal ({gap_plate_cord:.1f} mm) or the vent ({gap_plate_vent:.1f} mm), minimum 3")
    lid_under_plate = LID_T - (PLATE_T + PLATE_GLUE) - ORING_RECESS[1]
    oring_squeeze = 1 - ORING_RECESS[1] / ORING[1]
    if lid_under_plate < LID_UNDER_PLATE_MIN - 1e-9:
        errors.append(f"lid {lid_under_plate:.1f} mm thick under the plates (minimum {LID_UNDER_PLATE_MIN:.1f})")
    if not ORING_SQUEEZE[0] - 1e-9 <= oring_squeeze <= ORING_SQUEEZE[1] + 1e-9:
        errors.append(f"O-ring squeeze {oring_squeeze:.0%} outside {ORING_SQUEEZE[0]:.0%}–{ORING_SQUEEZE[1]:.0%}")

    # --- ramp bond ---
    bond_h = LID_T - max(-LIP_H, LID_T - RAMP_DEPTH - RAMP_FOOT)
    flat_end = LID_Y / 2 - R_LID
    if RAMP_Y < RAMP_BOND_MIN[0] - 1e-9 or bond_h < RAMP_BOND_MIN[1] - 1e-9 or RAMP_Y / 2 > flat_end + 1e-9:
        errors.append(f"ramp bond {RAMP_Y:.0f} × {bond_h:.1f} mm (minimum {RAMP_BOND_MIN[0]:.0f} × {RAMP_BOND_MIN[1]:.0f}), "
                      f"flat end face ±{flat_end:.0f} mm")

    # --- step scan under the knee lanes (lid, ramp, plates proud by PLATE_PROUD, all screw heads) ---
    lift = (0, 0, PLATE_PROUD)
    scan_solid = union(lid, ramp, plates.translate(lift), plate_hw["ct_screw"].translate(lift),
                       box_hw["screw"].translate((0, 0, -BASE_H)))
    dxs = 0.1
    x_lo, x_hi = RAMP_X0 - RAMP_LEN - BLADE_KNEE_R - 2.0, DOCK_DX + BLADE_KNEE_R + 1.0
    xs = np.arange(x_lo, x_hi, dxs)
    h_free = -delta_at(S_MIN)                              # knee at the upper limit: lowest relative to the lid
    worst_step, worst_slope, worst_at = 0.0, 0.0, (0.0, 0.0)
    ramp_slope = 0.0
    for sgn in (1, -1):
        y_lo, y_hi = sgn * PLATE_Y - DOCK_DY - BLADE_W / 2, sgn * PLATE_Y + DOCK_DY + BLADE_W / 2
        ys = np.arange(y_lo, y_hi + 0.25, 0.5)
        lane = scan_solid ^ box(x_lo - 1, x_hi + 1, y_lo - 1, y_hi + 1, -10, LID_T + 5)
        H = top_heights(lane, xs, ys) - LID_T
        for dy in np.arange(-DOCK_DY, DOCK_DY + 0.01, 1.0):
            yc = sgn * PLATE_Y + dy
            cols = (ys >= yc - BLADE_W / 2 - 1e-9) & (ys <= yc + BLADE_W / 2 + 1e-9)
            prof = np.maximum(H[:, cols].max(axis=1), h_free)
            k = knee_track(prof, dxs, BLADE_KNEE_R)
            ang = np.degrees(np.arctan(np.abs(np.gradient(k, dxs))))
            on_ramp = (xs >= RAMP_X0 - RAMP_LEN - BLADE_KNEE_R) & (xs <= RAMP_X0 + BLADE_KNEE_R)
            ramp_slope = max(ramp_slope, float(ang[on_ramp].max()))
            off = ~on_ramp
            i = int(np.argmax(ang[off]))
            step = BLADE_KNEE_R * (1 - math.cos(math.radians(ang[off][i])))
            if step > worst_step:
                worst_step, worst_at = step, (float(xs[off][i]), float(yc))
            worst_slope = max(worst_slope, float(ang[off].max()))
    if worst_step > STEP_MAX + 1e-9:
        errors.append(f"the knee meets a {worst_step:.2f} mm step (maximum {STEP_MAX}) at x = {worst_at[0]:.1f}, y = {worst_at[1]:.0f} mm")
    if max(worst_slope, ramp_slope) > RAMP_ANGLE + 0.5:
        errors.append(f"the knee meets a {max(worst_slope, ramp_slope):.1f}° slope (maximum {RAMP_ANGLE + 0.5:.1f}°)")

    # --- enclosure: electronics, cables and envelopes against the base, the lid and each other ---
    envelopes = board_envelopes()
    board_union = union(*board.values(), *envelopes.values())
    lid_on = lid.translate((0, 0, BASE_H))
    plates_on = union(plates, plate_film, *plate_hw.values()).translate((0, 0, BASE_H))
    for name, part in list(board.items()) + list(envelopes.items()):
        for other, solid in (("base", base), ("lid", lid_on)):
            v = (part ^ solid).volume()
            if v > 0.05:
                errors.append(f"{name} colliding with the {other}: {v:.2f} mm³")
    for name, part in (("plates", plates), ("plate film", plate_film), *plate_hw.items()):
        v = (part.translate((0, 0, BASE_H)) ^ lid_on).volume()
        if v > 0.5:
            errors.append(f"{name} colliding with the lid: {v:.1f} mm³")
        v = (part.translate((0, 0, BASE_H)) ^ base).volume()
        if v > 0.05:
            errors.append(f"{name} colliding with the base: {v:.2f} mm³")
    # parts against each other; the pairs listed are plugged into each other or pressed together
    groups = {
        "board": union(board["pcb"], board["esp_module"], board["usb_c"], board["bat_jst"],
                       *[v for k, v in envelopes.items() if k not in ("flashing USB-C plug", "plate screw stacks")]),
        "holder": union(board["holder"], board["battery"]),
        "battery leads": board["bat_wire"], "antenna": board["antenna"], "antenna cable": board["ant_cable"],
        "contact adapter": board["adapter"], "TVS": board["tvs"], "contact wires": union(board["wire_pos"], board["wire_neg"]),
        "plate screw stacks": union(envelopes["plate screw stacks"], plate_hw["ct_nut"].translate((0, 0, BASE_H))),
        "flashing USB-C plug": envelopes["flashing USB-C plug"],
    }
    plugged = {("board", "battery leads"), ("holder", "battery leads"), ("board", "antenna cable"),
               ("antenna", "antenna cable"), ("board", "contact adapter"), ("contact adapter", "TVS"),
               ("contact adapter", "contact wires"), ("TVS", "contact wires"), ("contact wires", "plate screw stacks")}
    names = list(groups)
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            if (a, b) in plugged:
                continue
            v = (groups[a] ^ groups[b]).volume()
            if v > 0.05:                                # parts pressed together only touch
                errors.append(f"{a} colliding with {b}: {v:.2f} mm³")
    wire_len = [path_length(contact_wire_path(i)) for i in range(2)]
    # antenna: far from the plates and the cell, on the flat part of the wall, clear of the seal and screws
    ant = board["antenna"]
    gap_ant_plate = ant.min_gap(plate_metal, 100.0)
    gap_ant_cell = ant.min_gap(board["battery"], 100.0)
    gap_ant_screw = min(ant.min_gap(box_hw["screw"], 50.0), ant.min_gap(box_hw["nut_m3"], 50.0))
    gap_ant_cord = ant.min_gap(cord, 50.0)
    if gap_ant_plate < ANT_PLATE_MIN or gap_ant_cell < ANT_CLEAR_MIN:
        errors.append(f"antenna too close: {gap_ant_plate:.1f} mm from a plate (minimum {ANT_PLATE_MIN:.0f}), "
                      f"{gap_ant_cell:.1f} mm from the cell (minimum {ANT_CLEAR_MIN:.0f})")
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
    # snap arms of the cradle, shorter with the board lowered
    arm_l = PCB_Z1 + 0.1 - FLOOR
    snap_strain = 3 * SNAP_T * SNAP_HOOK / (2 * arm_l ** 2)
    if snap_strain > SNAP_STRAIN_MAX:
        errors.append(f"snap arm strain {snap_strain:.1%} above {SNAP_STRAIN_MAX:.0%} ({arm_l:.1f} mm arms)")
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
    if (ramp ^ lid).volume() > 0.5 or (ramp.translate((0, 0, BASE_H)) ^ base).volume() > 0.05:
        errors.append("ramp colliding with the lid or the base")
    groove_out = GROOVE_C + GROOVE_W / 2
    screw_edge = SCREW_OFF - 1.7
    if screw_edge - groove_out < 0.8:
        errors.append(f"screw hole {screw_edge - groove_out:.1f} mm from the groove")
    vent_probe = cyl_x(VENT_R - 0.2, -FL_X / 2 - 2, -BODY_X / 2 + 0.5, VENT_Y, VENT_Z, 24)
    if (vent_probe ^ base).volume() > 0.1:
        errors.append("the vent hole is blocked by the flange")
    if VENT_Z + VENT_PATCH_R > FL_Z0 - FLANGE:
        errors.append("the vent membrane touches the flange chamfer")
    screw_thread = BASE_H + LID_T - SCREW_L                # tip of the flange screws
    nut_bottom = FL_Z0 + 2.6 - NUT_M3_H
    if nut_bottom - screw_thread < 1.0:
        errors.append(f"flange screws {SCREW_L:.0f} mm: only {nut_bottom - screw_thread:.1f} mm of thread past the nut")

    # --- holder ---
    pg7_wall = min(HOLD_H / 2 - PG7_D / 2, HOLD_H - (HOLD_H / 2 + PG7_D / 2))
    if pg7_wall < PG7_WALL - 1e-9:
        errors.append(f"wall around the PG7 hole {pg7_wall:.2f} mm (minimum {PG7_WALL:.0f})")
    if not (0.2 <= SLOT[0] - BLADE_W <= 0.8 and 0.3 <= SLOT[1] - BLADE_T <= 0.8):
        errors.append(f"blade slot {SLOT[0]} × {SLOT[1]} mm does not fit a {BLADE_W} × {BLADE_T} blade (clearance 0.2–0.8 / 0.3–0.8 mm)")
    if PLATE_Y + BLADE_W / 2 + 0.5 > HOLD_CAV_Y / 2 or HOLD_CAV_Y / 2 + 2.0 > ROD_Y - 10.2 / 2:
        errors.append("blade seats, cavity and rod nuts overlap in the holder")
    if along_blade(BLADE_CLAMP + 1.0)[0] > HOLD_X / 2 - HOLD_WALL:
        errors.append("the clamped part of the blade is longer than the holder cavity")
    clamp_floor = min(head_depth(s) - PAN_H for s in CLAMP_S)
    if clamp_floor < CLAMP_FLOOR_MIN - 1e-9:
        errors.append(f"only {clamp_floor:.1f} mm of holder under the blade at a clamp screw (minimum {CLAMP_FLOOR_MIN})")
    low_rigid = min(holder.bounding_box()[2], blades["clamp_screw"].bounding_box()[2])
    if low_rigid < -1e-6:
        errors.append(f"a rigid part of the holder is {-low_rigid:.2f} mm below its underside")
    seat_top = seat_z(along_blade(BLADE_CLAMP + 1.0)[0]) + BLADE_T + 1.3 + 2.4
    if seat_top > HOLD_H - 2.0:
        errors.append(f"clamp nuts {seat_top:.1f} mm high: they hit the cap")

    cam_world = robot["camera"]
    gap_cam = world_rigid.min_gap(cam_world, 100.0)
    box_world = union(world_base, world_lid, world_ramp)
    gap_box_cam = box_world.min_gap(cam_world, 100.0)
    gap_box_rain = box_world.min_gap(robot["rain_sensor"], 100.0)
    if gap_box_cam < 5.0:
        errors.append(f"enclosure too close to the camera: {gap_box_cam:.1f} mm")
    if gap_cam < 5.0:
        errors.append(f"blade holder too close to the camera: {gap_cam:.1f} mm")
    # Robot passing under the holder: the robot backs up from +x to its docked position, here raised
    # to the upper limit of the window. The blades are left out on purpose: the lid bends them.
    raise_up = S_NOM - S_MIN
    moving = union(*robot.values(), world_base, world_lid, world_ramp).translate((0, 0, raise_up))
    sweep_gap, sweep_at = 1e9, 0.0
    for d in range(0, 905, 5):
        g = moving.translate((float(d), 0, 0)).min_gap(world_rigid, 60.0)
        if g < sweep_gap:
            sweep_gap, sweep_at = g, float(d)
    if sweep_gap < S_MIN - 0.05:
        errors.append(f"the robot, {raise_up:.1f} mm high, hits the blade holder while backing up: gap {sweep_gap:.1f} mm "
                      f"at {sweep_at:.0f} mm from the base")
    adj_up = low_nut_bottom - sup_top_hw                   # the holder can go up by this much
    adj_down = (ROOF_Z - BR_PLATE) - rod_top               # and down by this much (top of the rods / roof)
    if adj_up < ADJ_MIN:
        errors.append(f"insufficient upward adjustment travel: {adj_up:.1f} mm")
    if adj_down < ADJ_MIN:
        errors.append(f"insufficient downward adjustment travel: {adj_down:.1f} mm")
    h_needed = (S_NOM + HOLD_H + HOLD_CAP_T + 2 * (WASHER_M6_T + NUT_M6_H) + ADJ_MIN
                + BR_BOSS + BR_BAR + BR_GAP + BR_PLATE)
    if ROOF_Z - lid_top < h_needed:
        errors.append(f"roof {ROOF_Z - lid_top:.1f} mm above the lid: {h_needed:.1f} mm needed")
    if lid_top > CAM_TOP - 3.0:
        errors.append(f"lid top {lid_top:.1f} mm: less than 3 mm under the camera top ({CAM_TOP:.0f} mm)")

    sole_txt = (f"printed shim {SOLE_MIN:.1f}–{SOLE_MIN + deck_dmax - deck_dmin:.1f} mm (crowned deck R {DECK_R:.0f} mm)"
                if sole is not None else "flat deck (DECK_R = 0), bottom bonded directly")
    f_nom = force(d_nom)
    report = {
        "Enclosure (base + lid), overall size": f"{LID_X:.1f} × {LID_Y:.1f} × {BASE_H + LID_T:.1f} mm (x × y × h), "
                                                 f"{LID_X + RAMP_LEN:.1f} mm long with the ramp, VHB-bonded",
        "Inner volume": f"{IN_X:.0f} × {IN_Y:.0f} × {IN_H:.0f} mm",
        "Lid": f"{LID_T:.1f} mm flat, plate pockets {PLATE_T + PLATE_GLUE:.1f} mm deep, O-ring recesses {ORING_RECESS[0]:.1f} × {ORING_RECESS[1]:.1f} mm, "
               f"{lid_under_plate:.1f} mm left under the plates; flange screws M3 × {SCREW_L:.0f}",
        "Lid seal": f"Ø{CORD_D:.1f} silicone cord in a {GROOVE_W:.1f} × {GROOVE_D:.1f} mm groove: {round(squeeze * 100)}% squeeze, {round(groove_fill * 100)}% fill",
        "Closure protection": f"{LIP_H:.0f} mm lip in front of the seal, 6 M3 screws outside the seal, ePTFE vent on the rear wall",
        "Plates": f"2 × stainless A4 {PLATE_L:.0f} × {PLATE_W:.0f} × {PLATE_T:.1f} mm, flush, centers y = ±{PLATE_Y:.0f} mm, {plate_gap:.0f} mm gap, "
                  f"1 countersunk M3 each + O-ring {ORING[0]:.0f} × {ORING[1]:.1f} ({oring_squeeze:.0%} squeeze); {head_gap:.1f} mm to the flange screw heads",
        "Blades": f"2 × stainless spring strip {BLADE_W:.0f} × {BLADE_T:.2f} mm, free length {BLADE_L:.0f} mm at {math.degrees(THETA):.1f}°, "
                  f"cut {BLADE_CLAMP + BLADE_L + BLADE_NOSE_L:.0f} mm; K = {BLADE_K:.3f} N/mm, vertical {BLADE_KV:.3f} N/mm",
        "Contact force": f"{force(d_nom - win_down):.2f} N at −{win_down:.1f} mm / {f_nom:.2f} N nominal (δ = {d_nom:.1f} mm) / "
                         f"{force(d_up):.2f} N at +{win_up:.1f} mm / {force(d_stop):.2f} N at the hard stop; ±{K_TOL:.0%} on the stiffness",
        "Blade stress": f"{stress(d_nom):.0f} MPa nominal, {stress(d_up):.0f} MPa at the upper limit, {sigma_stop:.0f} MPa at the hard stop "
                        f"= {sigma_stop / BLADE_RP02:.2f} of the {BLADE_RP02:.0f} MPa yield (limit {SIGMA_FRAC:.2f})",
        "Height window at docking": f"+{win_up:.1f} / −{win_down:.1f} mm = {win_up + win_down:.1f} mm (fixed transmitter design before: 5.6 mm); "
                                    f"no contact at all {d_nom:.1f} mm under the nominal",
        "Buckling": f"P_cr = {p_cr:.1f} N, axial load {axial:.2f} N (friction {MU} and the {RAMP_ANGLE:.0f}° ramp, +{K_TOL:.0%} stiffness)",
        "Stop tolerances": f"±{DOCK_DX:.0f} mm along and ±{DOCK_DY:.0f} mm across checked: blade at least {worst_overlap:.1f} mm on its plate; "
                           f"a blade reaches the other plate beyond {wrong_plate:.0f} mm across",
        "Surface under the knee": f"largest step felt {worst_step:.2f} mm (maximum {STEP_MAX}), largest slope {worst_slope:.1f}° off the ramp, "
                                  f"plates {PLATE_PROUD} mm proud",
        "Entry ramp": f"{RAMP_ANGLE:.0f}°, {RAMP_LEN:.1f} mm long, foot {RAMP_DEPTH:.1f} mm under the plates "
                      f"({RAMP_DEPTH - d_up:.1f} mm below the knee at the upper limit), bonded on {RAMP_Y:.0f} × {bond_h:.1f} mm",
        "Deck fit": sole_txt,
        "Enclosure center (robot frame)": f"x = {box_x:.0f} mm, y = {BOX_Y:.0f} mm",
        "Top of the lid": f"{lid_top:.1f} mm above the ground (camera: {CAM_TOP:.0f} mm)",
        "Lid / blade holder gap (docked)": f"S = {S_NOM:.1f} mm, set with a gauge, adjustable to the mm (1 turn of an M6 nut = 1 mm); hard stop at {S_MIN:.1f}",
        "Minimum gap while backing in": f"{sweep_gap:.1f} mm with the robot {raise_up:.1f} mm high (the lid passes under the holder)",
        "Blade holder": f"{HOLD_X:.0f} × {HOLD_Y:.0f} × {HOLD_H:.0f} mm + {HOLD_CAP_T:.0f} mm cap, {gap_cam:.1f} mm from the camera, PG7 wall {pg7_wall:.2f} mm, "
                        f"{clamp_floor:.1f} mm under the blade at the clamp screws",
        "Holder adjustment travel": f"+{adj_up:.0f} / −{adj_down:.0f} mm",
        "Required clearance lid → roof": f"≥ {h_needed:.1f} mm (model: {ROOF_Z - lid_top:.1f} mm)",
        "M6 stainless threaded rods": f"2 × {rod_len} mm",
        "Enclosure / camera, rain sensor": f"{gap_box_cam:.1f} mm / {gap_box_rain:.1f} mm",
        "Board": f"Olimex ESP32-S3-DevKit-Lipo {BOARD_W:.2f} × {BOARD_L:.2f} mm in the printed cradle, PCB top {PCB_Z1:.1f} mm above the bottom, "
                 f"components {COMP_TOP:.0f} mm above / {COMP_BOT:.0f} mm below assumed; snap arms {arm_l:.1f} mm, strain {snap_strain:.2%}",
        "18650 holder": f"{HOLDER_L:.0f} × {HOLDER_W:.0f} × {HOLDER_H:.0f} mm on a {HZ0 - FLOOR:.1f} mm plinth, cell Ø{CELL_D:.1f} × {CELL_L:.0f} mm (protected)",
        "Contact wiring": f"USB-C male adapter {ADAPT[0]:.0f} × {ADAPT[1]:.0f} × {ADAPT[2]:.0f} mm in the native socket, TVS on its terminals, "
                          f"wire routes {wire_len[0]:.0f} / {wire_len[1]:.0f} mm (+ / −), cut 150 mm so the lid opens",
        "Flashing access (lid open)": f"{USB_PLUG_L:.0f} mm of straight plug in front of the free USB-C socket",
        "FPC antenna (−y end wall)": f"{ANT_L:.0f} × {ANT_W:.0f} mm, {gap_ant_plate:.1f} mm from the plates, {gap_ant_cell:.1f} mm from the cell, "
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
        "03_blade_holder.stl": export_stl(holder_body, "03_blade_holder.stl"),
        "04_holder_cap.stl": export_stl(holder_cap, "04_holder_cap.stl", flip=True),
        "05_roof_bridge.stl": export_stl(bracket, "05_roof_bridge.stl", flip=True),
    }
    if sole is not None:
        stl["06_deck_shim.stl"] = export_stl(sole, "06_deck_shim.stl", flip=True)
    stl["07_entry_ramp.stl"] = export_stl(ramp, "07_entry_ramp.stl")
    print("STL")
    for name, tm in stl.items():
        ext_ = tm.extents
        print(f"  - {name}: {ext_[0]:.1f} × {ext_[1]:.1f} × {ext_[2]:.1f} mm, {tm.volume / 1000:.1f} cm³")

    # --- GLB scene ---
    colors = {
        "wheels": "#202224", "chassis": "#55595e", "shell": "#e4e6e8", "bumper": "#26282b",
        "camera": "#2b2f33", "camera_lens": "#2d6cdf", "rain_sensor": "#18a0c8", "buttons": "#3a3d40",
        "stop_button": "#d62828", "plate": "#4a4d50", "tower": "#5d6165", "roof": "#8a9096",
        "vhb": "#2b2b2b", "sole": "#c96f1c", "base": "#f08a24", "lid": "#f6a552", "ramp": "#f4b46a", "cord": "#e8352e",
        "screw": "#b9c0c7", "nut_m3": "#d5dbe1", "vent": "#f2f2f2",
        "holder": "#151515", "battery": "#16a085", "bat_wire": "#c0392b", "bat_jst": "#f2f2f2",
        "antenna": "#e0a526", "ant_cable": "#2a2a2a",
        "pcb": "#101010", "esp_module": "#c7ccd1", "usb_c": "#b0b5ba",
        "ct_plate": "#9aa3ab", "plate_film": "#f0f0ea", "ct_screw": "#c4cad0", "oring": "#202020", "ring_term": "#c98a3c",
        "ct_nut": "#d5dbe1", "adapter": "#1d5f2a", "tvs": "#141414", "wire_pos": "#d62828", "wire_neg": "#1b1b1b",
        "blade_holder": "#2f6fd6", "holder_cap": "#6f9de8", "blade": "#8e98a2", "clamp_screw": "#c4cad0", "blade_term": "#c98a3c",
        "gland": "#1a1a1a", "cable": "#111111", "cable_in": "#8b2a2a", "bracket": "#3aa655", "rod": "#a9b0b7", "nut": "#cfd5db",
    }
    scene = trimesh.Scene()
    METAL = ("rod", "nut", "esp_module", "usb_c", "screw", "nut_m3", "ct_plate", "ct_screw", "ct_nut", "blade", "clamp_screw",
             "ring_term", "blade_term")

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

    EXP = {"vhb": -14, "sole": -8, "base": 0, "cord": 75, "lid": 95, "ramp": 95, "screw": 135, "nut_m3": -22, "vent": 0,
           "holder": 15, "battery": 15, "bat_wire": 15, "bat_jst": 15, "pcb": 15, "esp_module": 15, "usb_c": 15,
           "antenna": 15, "ant_cable": 15, "adapter": 30, "tvs": 40, "wire_pos": 50, "wire_neg": 50,
           "ct_plate": 115, "plate_film": 105, "ct_screw": 150, "oring": 80, "ring_term": 65, "ct_nut": 60}
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
    add(world_ramp, "robot", "ramp", EXP["ramp"])
    add(at_box(cord, box_z), "robot", "cord", EXP["cord"])
    add(at_box(plates, box_z + BASE_H), "robot", "ct_plate", EXP["ct_plate"])
    add(at_box(plate_film, box_z + BASE_H), "robot", "plate_film", EXP["plate_film"])
    for k, m in plate_hw.items():
        add(at_box(m, box_z + BASE_H), "robot", k, EXP[k])
    for k, m in box_hw.items():
        add(at_box(m, box_z), "robot", k, EXP[k])
    for k, m in board.items():
        add(at_box(m, box_z), "robot", k, EXP[k])
    add(world_bracket, "tx", "bracket", explode=60)
    add(at_hold(holder_body), "tx", "blade_holder")
    add(at_hold(holder_cap, HOLD_H), "tx", "holder_cap", explode=50)
    for k, m in blades.items():
        add(at_hold(m), "tx", k)
    for k, m in gland.items():
        add(at_hold(m), "tx", k)
    for y in (-ROD_Y, ROD_Y):
        wx, wy = hold_wx, BOX_Y + y
        add(cyl(3.0, rod_bottom, rod_top, wx, wy, 24), "tx", "rod")
        add(hexprism(10, sup_z + HOLD_H - 5.2, sup_z + HOLD_H, wx, wy), "tx", "nut")    # captive nut
        add(hexprism(10, cap_top, sup_top_hw, wx, wy), "tx", "nut")                     # clamps the holder cap
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
        "lid": P("Lid", "boitier", f"Flat {fmt(LID_T)} mm lid with the two plate pockets, O-ring recesses underneath, {fmt(LIP_H, 0)} mm drip lip, "
                 "engraved + / − and forward arrow. Square −x edge for the ramp.",
                 dims(LID_X, LID_Y, LID_T + LIP_H), "02_enclosure_lid.stl · visible face on the bed"),
        "ramp": P("Entry ramp", "boitier", f"{fmt(RAMP_ANGLE, 0)}° wedge bonded (MS polymer or epoxy) to the −x end of the lid: it lifts the blades "
                  f"onto the lid while the robot backs in. Foot {fmt(RAMP_DEPTH)} mm under the plates.",
                  dims(RAMP_LEN, RAMP_Y, RAMP_DEPTH + RAMP_FOOT), "07_entry_ramp.stl · flat bottom on the bed, no supports"),
        "ct_plate": P("Stainless contact plates", "quincaillerie", f"A4 (316L), flush in the lid, {fmt(plate_gap, 0)} mm apart: a blade can never touch both. "
                      "+ on the +y plate. Top edges filed 0.4 mm at 45°.",
                      dims(PLATE_L, PLATE_W, PLATE_T), f"Bonded on a {fmt(PLATE_GLUE)} mm neutral-cure silicone film + bead on the edge"),
        "plate_film": P("Silicone film", "quincaillerie", "Neutral-cure silicone under each plate and along its edge: bonds the plate and stops water.", f"{fmt(PLATE_GLUE)} mm", ""),
        "ct_screw": P("Countersunk M3 × 10 A4 screws", "quincaillerie", "One per plate, head flush in the countersunk plate.", "ISO 10642", ""),
        "oring": P("O-rings 3 × 1.5", "quincaillerie", f"EPDM or NBR, in the Ø{fmt(ORING_RECESS[0])} recess under the lid, squeezed {oring_squeeze:.0%} by the washer: water running down the screw stops there.", "", ""),
        "ring_term": P("Ring terminals M3", "quincaillerie", "Crimped on the 0.5–0.75 mm² wires, clamped under the nut: no soldering.", "0.5–1 mm²", ""),
        "ct_nut": P("Washer, toothed washer and nut", "quincaillerie", "Stack under each plate screw, 5 mm under the lid.", "M3 A4", ""),
        "cord": P("Silicone cord seal", "quincaillerie", f"Solid Ø{fmt(CORD_D)} cord laid without stretching in the {fmt(GROOVE_W)} × {fmt(GROOVE_D)} mm groove; squeezed {round(squeeze * 100)}% when tightened.",
                  f"≈ {2 * (IN_X + IN_Y) / 1000 + 0.05:.2f} m", "Butt the two ends together"),
        "screw": P(f"Stainless M3 × {fmt(SCREW_L, 0)} countersunk screws", "quincaillerie", "6 screws, all outside the seal: no hole goes through the sealed area.", "ISO 10642", "Tighten crosswise without crushing the seal"),
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
        "esp_module": P("ESP32-S3-WROOM-1U module", "electronique", "u.FL socket on the module (corner not documented): the module top is kept clear.",
                        dims(*MODULE), "Rated for 65 °C ambient"),
        "usb_c": P("The two USB-C sockets", "electronique", "The native USB one takes the contact adapter (5 V from the plates, through the board's 1N5819); the USB-UART one (CH340X: console and flashing) stays free with the lid open. Which is which: to check on the board.", "", f"{fmt(USB_PLUG_L, 0)} mm free for a straight plug"),
        "adapter": P("USB-C male adapter", "electronique", "USB-C male to screw-terminal board (or a VBUS/GND pigtail with a lever connector) in the native socket. The plate wires and the TVS leads are clamped in its terminals.",
                     dims(*ADAPT), "Envelope to check against the part bought"),
        "tvs": P("TVS P6KE6.2A", "electronique", "Unidirectional, across VBUS and GND at the board: clamps ESD and contact spikes (the board has 6.3 V capacitors and no protection). Band (cathode) on VBUS.", "DO-15", "P6KE6.8A if the charger gives more than 5.25 V"),
        "wire_pos": P("+ wire (red)", "electronique", "From the +y plate's ring terminal to VBUS on the adapter.", "0.5–0.75 mm²", "Strain-relieved with a cable tie"),
        "wire_neg": P("− wire (black)", "electronique", "From the −y plate's ring terminal to GND on the adapter.", "0.5–0.75 mm²", ""),
        "antenna": P("Flexible 2.4 GHz antenna (FPC), u.FL cable", "electronique",
                     f"Bonded inside the −y end wall, {fmt(gap_ant_plate)} mm from the nearest plate and {fmt(gap_ant_cell)} mm from the cell; nothing goes through the wall.",
                     dims(ANT_L, ANT_W), f"Cable {fmt(ANT_CABLE[0], 0)}–{fmt(ANT_CABLE[1], 0)} mm, slack coiled on the floor"),
        "ant_cable": P("Antenna u.FL cable", "electronique", "From the module over its end, a loose loop on the floor, up to the antenna.", "Ø1.13 mm", "Do not kink: bend radius ≥ 5 mm"),
        "blade_holder": P("Blade holder", "support", f"Fixed under the roof, {fmt(S_NOM)} mm above the lid when docked. Seats inclined {fmt(math.degrees(THETA))}°, "
                          "slots through the −x wall, 45° lead-in on the +x lower edge, PG7 gland, weep hole.",
                          dims(HOLD_X, HOLD_Y, HOLD_H), "03_blade_holder.stl · floor on the bed"),
        "holder_cap": P("Holder cap", "support", "Clamped by the rod nuts.", dims(HOLD_X, HOLD_Y, HOLD_CAP_T), "04_holder_cap.stl"),
        "blade": P("Stainless spring blades", "support", f"1.4310 (301) strip, {fmt(BLADE_L, 0)} mm free, knee R{fmt(BLADE_KNEE_R, 0)} and {fmt(BLADE_NOSE_L, 0)} mm nose at {fmt(BLADE_NOSE_ANG, 0)}°. "
                   f"Docked: δ = {fmt(d_nom)} mm, {fmt(f_nom, 2)} N each (window +{fmt(win_up)} / −{fmt(win_down)} mm).",
                   dims(BLADE_W, BLADE_T), f"Cut {fmt(BLADE_CLAMP + BLADE_L + BLADE_NOSE_L, 0)} mm (60 mm with margin), drawn in the docked pose"),
        "clamp_screw": P("Blade clamp screws", "support", "2 × M3 × 12 A4 pan head per blade, from below, heads in counterbores; nut and washer on top.", "M3 × 12", ""),
        "blade_term": P("Ring terminals on the blades", "support", "Under the nut of the inner clamp screw: one bolted joint carries the clamp and the current.", "M3", ""),
        "gland": P("PG7 cable gland", "support", "Sealed cable exit from the holder, towards the station tower.", "IP68, cable 3–6.5 mm", ""),
        "cable": P("Supply cable", "support", "2 × 0.75 mm² outdoor flexible, 3 m, to a 5 V USB charger in a protected socket. "
                   "PTC 0.75 A hold at the charger end (IP65 box with a USB-A screw-terminal breakout).", "", "Do not tap the Mammotion station"),
        "cable_in": P("Wires inside the holder", "support", "From the gland to the ring terminals of the blades: + on the +y blade.", "", ""),
        "bracket": P("Roof bridge", "support", "VHB-bonded under the garage roof, carries the two rods.", dims(BR_Y, BR_X, BR_PLATE + BR_GAP + BR_BAR + BR_BOSS), "05_roof_bridge.stl"),
        "rod": P("Stainless M6 threaded rods", "support", "One turn of a nut moves the holder by 1 mm.", f"2 × {rod_len} mm", ""),
        "nut": P("M6 nuts", "support", "Height adjustment and locking.", "", ""),
        "camera": P("LUBA 2 camera", "robot", f"The lid stays {fmt(CAM_TOP - lid_top)} mm below its top.", "", ""),
        "rain_sensor": P("Rain sensor", "robot", "Keep at least 30 mm clear around it.", "", ""),
    }

    bx, by = box_x, BOX_Y
    sx_, sy_ = IN_X / 2 + SCREW_OFF, 35.0
    zl = box_z + BASE_H
    hz = sup_z
    blade_mid = blade_centerline(d_nom)[20]
    A = lambda text, p, grp="robot", dz=0.0, dx=0.0, dy=-40.0: {"text": text, "p": [round(c, 2) for c in p], "grp": grp, "dz": dz, "dx": dx, "dy": dy}
    ct = CT_POS[0]
    annots = {
        "plate": A(f"Stainless plate {fmt(PLATE_L, 0)} × {fmt(PLATE_W, 0)}", (bx - 15, by + PLATE_Y, lid_top), dz=EXP["ct_plate"], dx=-75, dy=-50),
        "ramp": A(f"Ramp {fmt(RAMP_ANGLE, 0)}°", (bx + RAMP_X0 - RAMP_LEN / 2, by - 20, zl + LID_T - RAMP_DEPTH / 2), dz=EXP["ramp"], dx=-70, dy=30),
        "oring": A("O-ring under the nut", (bx + ct[0], by + ct[1], zl + 0.5), dz=EXP["oring"], dx=-70, dy=40),
        "cord": A(f"Ø{fmt(CORD_D)} cord squeezed {round(squeeze * 100)}%", (bx + IN_X / 2 + GROOVE_C, by + sy_, zl - GROOVE_D / 2), dz=EXP["cord"], dx=60, dy=-70),
        "groove": A(f"Groove {fmt(GROOVE_W)} × {fmt(GROOVE_D)}", (bx - IN_X / 2 - GROOVE_C, by + sy_, zl - GROOVE_D), dx=-70, dy=40),
        "lip": A(f"{fmt(LIP_H, 0)} mm rain lip", (bx + LID_X / 2 - LIP_T / 2, by + sy_, zl - LIP_H), dz=EXP["lid"], dx=70, dy=45),
        "screw": A("M3 screw outside the seal", (bx + sx_, by + sy_, zl + LID_T), dz=EXP["screw"], dx=55, dy=-45),
        "nut": A("Captive M3 nut", (bx + sx_, by + sy_, FL_Z0 + box_z + 1.4), dz=EXP["nut_m3"], dx=65, dy=35),
        "vent": A("ePTFE vent", (bx - BODY_X / 2 - 0.4, by + VENT_Y, box_z + VENT_Z), dx=-60, dy=30),
        "vhb": A(f"VHB {fmt(VHB_T)} mm, deck not drilled", (bx + BODY_X / 2 - 2, by - 30, box_z - VHB_T / 2), dz=EXP["vhb"], dx=60, dy=45),
        "battery": A("Protected 18650", (bx + HXC, by + (HY0 + HY1) / 2, box_z + HZ0 + HOLDER_H - 0.5), dz=EXP["battery"], dx=70, dy=30),
        "esp": A("ESP32-S3-WROOM-1U", (bx + BXC, by + BY0 + MODULE[1] / 2, box_z + MOD_Z1), dz=EXP["esp_module"], dx=-60, dy=-40),
        "usb": A("2 × USB-C: contacts + flashing", (bx + BXC - USB_DX, by + BY1, box_z + PCB_Z1 + 3.3), dz=EXP["usb_c"], dx=-60, dy=35),
        "adapter": A("USB-C male adapter", (bx + QX, by + AD_Y1 - 4, box_z + AD_Z1), dz=EXP["adapter"], dx=60, dy=-30),
        "tvs": A("TVS P6KE6.2A", (bx + QX, by + TERM_Y - 5.5, box_z + AD_Z1 - 1.0), dz=EXP["tvs"], dx=70, dy=10),
        "antenna": A("FPC antenna (u.FL)", (bx, by + ANT_Y0, box_z + ANT_Z0 + ANT_W / 2), dz=EXP["antenna"], dx=-55, dy=-50),
        "cradle": A("Cradle snap arm", (bx + BX0 - CRADLE_GAP - SNAP_T / 2, by + BY1 - SNAP_Y, box_z + PCB_Z1 + 1.2), dx=-65, dy=10),
        "blade": A(f"Blade {fmt(BLADE_W, 0)} × {fmt(BLADE_T)}, δ = {fmt(d_nom)} mm, {fmt(f_nom, 2)} N",
                   (hold_wx + blade_mid[0], by + PLATE_Y, hz + blade_mid[1]), grp="tx", dx=-80, dy=-45),
        "sgap": A(f"S = {fmt(S_NOM)} mm (gauge)", (hold_wx - HOLD_X / 2 + 6, by - PLATE_Y - 12, lid_top + S_NOM / 2), dx=75, dy=30),
        "chamfer": A("45° lead-in", (hold_wx + HOLD_X / 2 - HOLD_LEAD / 2, by - HOLD_Y / 2 + 10, hz + HOLD_LEAD / 2), grp="tx", dx=60, dy=10),
        "rod": A("M6 rod: 1 turn = 1 mm", (hold_wx, by + ROD_Y, (sup_top_hw + low_nut_bottom) / 2), grp="tx", dx=70, dy=0),
        "gland": A("PG7 cable gland", (hold_wx - HOLD_X / 2 - 8, by, hz + HOLD_H / 2), grp="tx", dx=-60, dy=-30),
        "ptc": A("PTC 0.75 A at the charger", (hold_wx - HOLD_X / 2 - 60, by, hz + HOLD_H / 2), grp="tx", dx=-50, dy=-60),
        "camera": A(f"Camera: +{fmt(CAM_TOP - lid_top, 0)} mm", (CAM_X0 + 30, 0, CAM_TOP - 3), dx=40, dy=-50),
        "rain": A("Rain sensor (kept clear)", (250, 74, DECK_Z + 2.5), dx=40, dy=40),
    }
    if sole is not None:
        annots["sole"] = A("Deck shim", (bx, by - BODY_Y / 2 + 4, sole_top - 1), dz=EXP["sole"], dx=-60, dy=40)

    D = lambda title, text, mode, notes, cam, target=(0, 0, 0), explode=0.0, section=None: {
        "title": title, "text": text, "mode": mode, "notes": notes, "cam": cam, "target": target,
        "explode": explode, "section": section}
    details = [
        D("Sealing", f"Cord seal in the flange groove, squeezed {round(squeeze * 100)}% by 6 screws placed outside the seal. "
          f"The {fmt(LIP_H, 0)} mm lip of the lid shields the closing line from rain and sprinklers. "
          "The plate screws go through the lid inside an O-ring squeezed under the nut.",
          "boitier", ["cord", "groove", "lip", "screw", "nut", "oring"], cam=(70, 130, 40), target=(IN_X / 2, sy_, BASE_H / 2 + 6),
          section={"axis": "y", "offset": sy_}),
        D("Contact charging", f"Docked, the knee of each blade presses its plate: δ = {fmt(d_nom)} mm, {fmt(f_nom, 2)} N "
          f"(window +{fmt(win_up)} / −{fmt(win_down)} mm, holder {fmt(S_NOM)} mm above the lid). "
          f"The {fmt(RAMP_ANGLE, 0)}° ramp lifts the blades onto the lid while backing in.",
          "support", ["plate", "blade", "sgap", "ramp", "oring"], cam=(0, 150, 12), target=(18, PLATE_Y, BASE_H + 9),
          section={"axis": "y", "offset": PLATE_Y}),
        D("Electronics", "Olimex ESP32-S3-DevKit-Lipo clipped into the printed cradle, protected 18650 in its holder on the plinth. "
          "The plate wires end in a USB-C male adapter in the native socket, with the TVS on its terminals; the other USB-C stays free for flashing. "
          "The FPC antenna is bonded inside the end wall, away from the plates and the cell.",
          "boitier", ["battery", "esp", "usb", "antenna", "cradle", "adapter", "tvs"], cam=(190, 150, 170), target=(0, 0, 60), explode=0.75),
        D("Closing and bonding", "Captive M3 nuts slid in from below, countersunk screws from the top, VHB under the bottom. ePTFE vent on the rear wall. "
          "Plates bonded on a silicone film, one countersunk screw each; the ramp is bonded to the lid end.",
          "boitier", ["screw", "nut", "vhb", "cord", "vent", "plate", "ramp"] + (["sole"] if sole is not None else []),
          cam=(-210, 190, 150), target=(0, 0, 50), explode=1.0),
        D("Blade holder", "Fixed holder hung from the bridge by two M6 rods: one turn of a nut = 1 mm. Set with a 3 mm gauge between the lid and the holder. "
          "45° lead-in so a high lid slides under; PG7 gland towards the tower; PTC at the charger end of the cable.",
          "support", ["rod", "gland", "chamfer", "blade", "sgap", "ptc"], cam=(-260, 260, 120), target=(HOLD_CX - 10, 0, 70)),
        D("On the LUBA 2", f"Bonded behind the camera, offset by {fmt(abs(BOX_Y), 0)} mm to keep the rain sensor clear, "
          f"lid {fmt(CAM_TOP - lid_top, 0)} mm below the top of the camera.",
          "ensemble", ["camera", "rain", "vhb"], cam=(330, 330, 260), target=(30, 30, 0)),
    ]
    meta = {
        "boxX": box_x, "boxY": BOX_Y, "boxZ": box_z, "baseH": BASE_H, "lidTop": lid_top,
        "underMax": HOLD_CX + HOLD_X / 2 - (RAMP_X0 - RAMP_LEN),
        "sNom": S_NOM, "deltaNom": d_nom, "forceNom": f_nom, "windowUp": win_up, "windowDown": win_down,
        "sweepGap": sweep_gap, "roofZ": ROOF_Z, "explodeLift": EXPLODE_LIFT,
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
    (ROOT / "viewer" / "luba-esp32-dock.html").write_text(html, encoding="utf-8")
    print(f"Scene: viewer/luba-esp32-dock.html ({len(html) / 1e6:.1f} MB)")

    if errors:
        print("ERRORS:")
        for e in errors:
            print(f"  ✗ {e}")
        return 1
    print("Checks: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
