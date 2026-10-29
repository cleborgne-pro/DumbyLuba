# Contact charging of the ESP32 enclosure (blades on plates): design

Status: design, 2026-10-27 (architect). Nothing is built or measured yet.
Decision of the user (2026-10-27), taken as is: the Qi wireless charging of the ESP32 enclosure is dropped. Two
stainless **plates** sit on the enclosure lid; two flexible **blades** hang from the fixed support under the garage
roof and touch the plates, bending slightly, when the robot backs into the station.

Legend used in every figure and claim below:

- **[E]** established: read in the repository (README, `generate.py`, firmware) or in the Olimex Rev B netlist.
- **[C]** calculated here: the formula is given; the generator must recompute it and fail if it is violated.
- **[A]** assumed, to be checked ("TO CHECK") on the parts received or on site. Nothing in this document is measured.

## 1. Result in numbers

| Item | Choice | Status |
|---|---|---|
| Plates | 2 × stainless A4 (316L / 1.4404) **54 × 36 × 1.0 mm**, flush in the lid, centres at y = ±28 mm, **20 mm clear gap** | design |
| Plate fixing | silicone film + 1 countersunk M3 × 10 per plate, **O-ring under the nut**, ring terminal inside | design |
| Blades | 2 × stainless spring strip (1.4310 / AISI 301) **10 × 0.30 mm**, free length **36 mm**, 16.5° slope, 6 mm nose at 25° | design |
| Blade stiffness | K = 3EI/L³ = 0.275 N/mm, vertical K/cos²θ = 0.299 N/mm | [C], E = 190 GPa [A] |
| Contact force | **0.50 N** (robot 2.5 mm low) / **1.26 N** nominal / **2.00 N** (robot 2.5 mm high) / 2.15 N at the hard stop, ±20 % on the stiffness | [C] |
| Blade stress | 289 MPa nominal, 461 MPa at the upper limit, **495 MPa at the hard stop** = 0.65 of 760 MPa yield | [C], yield [A] |
| Height window | **+2.5 / −2.5 mm = 5.0 mm** (Qi: +2.5 / −3.1 = 5.6 mm [E]); softer failure (force fades, no cliff) | [C] |
| Longitudinal stop error | usable **±24 mm** (plate half-length 27 mm minus 3 mm) | [C]; actual scatter [A] ±10 mm, TO MEASURE |
| Lateral stop error | **±13 mm** with the blade fully on the plate, **±21 mm** with 2 mm of overlap; a wrong plate or a short needs more than 33 mm | [C] |
| Lid | 3.2 → **5.0 mm**, no window, 2 plate pockets; top at 263.1 mm (was 261.3), camera top 273 mm | [C] |
| Entry ramp | separate printed wedge on the −x end of the lid, 25°, 15.0 mm long, foot 7.0 mm under the plate plane | [C] |
| Support | holder 28 × 104 × 20 mm + cap; **M6 rods and bridge kept** (STL 05 identical); lowest rigid point 3.0 mm above the lid (hard stop at 0.5) | design |
| Supply | 5 V USB charger → **PTC** (garage side) → 3 m 2 × 0.75 mm² → blades → plates → **TVS P6KE6.2A** (box side) → USB-C male plug → board | design |
| Cost | about **€95–125**, instead of €105–135 (Adafruit transmitter and Qi patch gone) | rough |

## 2. What exists today (facts used)

- Enclosure 82.2 × 136.2 × 32.2 mm (base 29 + lid 3.2), VHB-bonded on the deck behind the camera; lid top 261.3 mm above
  ground, camera top 273 mm [E, `generate.py`]. The robot docks in reverse: the camera stays in front of the support
  and only the enclosure goes under it [E].
- Olimex ESP32-S3-DevKit-Lipo-EA clipped into a printed cradle, 18650 holder, FPC antenna on the −y end wall [E].
  The Qi patch plugged into the native USB-C socket; the USB-UART socket stays free for flashing [E].
- Support: transmitter mount hung by two M6 rods (y = ±42) from a bridge VHB-bonded under the roof; one turn of a nut =
  1 mm; STL 03 (mount), 04 (cap), 05 (bridge) [E].
- Olimex rail (+5V): BL4054B at 100 mA, fed by both USB-C sockets through a 1N5819 each (anti-reverse), EXT1 pin 21
  unprotected, 6.3 V capacitors, no TVS [E, user's netlist reading]. BL4054B datasheet not read: VCC 4.25–6.5 V,
  absolute maximum 7 V are the 4054 family values [A].
- Firmware 0.5.0 on the Olimex board reads PWR_SENS (GPIO 5, rail through 220 k / 470 k) and BAT_SENS (GPIO 6)
  [E, `board.cpp`]. `power.vin_present` is in `/status`; `Health.charging = vin` is sent to the app every 5 s, which
  shows "Battery · plugged in" [E, `main.cpp:44-55`, `GatewayHealthUi.kt`].

## 3. Concept

```
 roof ─────────────────────────────────────────────────────────  z 375
        │ bridge + 2 × M6 rods (1 turn = 1 mm, unchanged)
   ┌────┴─────────────┐
   │ holder (cavity:  │ cable gland (PG7) on the rear face
   │ terminals)       │
   └──────┬───────────┘  lowest rigid point  z 266.1  = lid top + S (S nominal 3.0, hard stop 0.5)
          │ slot, blade exits 3.0 above that point, slopes down 16.5°
           ╲____ blade 36 mm (stainless spring strip)
                 ╲___○ knee (contact line, radius 4)  ‾ nose 6 mm at 25°
 ─────────────────────●──────── plate, flush ────────────────╲  lid top z 263.1
   lid 5.0 mm                                                 ╲  ramp 25°, 15.0 mm long
 +x: root side                                                 ╲___  foot z 256.1   −x: tip side
 robot moves in −x when reversing in ──►  (blade "trails": root at +x, tip at −x; the ramp leads, at the −x end)
```

Why the blade trails (root on the +x side, tip pointing to the station): on the way in, the lid surface moves from the
root towards the tip and friction pulls the blade straight (stable, like a caster); on the way out friction pushes it,
but the blade is nearly horizontal and far from buckling (section 5). A blade whose tip leads would dig into the
lid edge on the way in. The contact is the convex **knee** of the bent tip, so it slides in both directions without
turning over; the nose only matters when the plate edge meets it on the way out.

Why a ramp: a soft blade hangs lower than the lid top by the flexion δ it will have once docked (up to 6.7 mm).
Without a ramp it would hit the vertical end of the lid. The lid and its lip give only 3.2 + 3 mm of face, and a
bevel cut into the lid is limited by the flange under it (the flange top is the lid underside). So the ramp is a
separate wedge hanging beyond the lid end (section 8).

## 4. Plates (on the lid)

- **Material**: stainless A4 (1.4404 / 316L), 1.0 mm. Passive, no verdigris, no galvanic couple with the stainless blade.
  Gold-plated brass would be better electrically but is expensive and fails at the first scratch under 5 V DC.
  Contact resistance of a stainless-on-stainless sliding contact at about 1 N: constriction R ≈ ρ/(2a), with
  a = √(F/(πH)), H ≈ 2 GPa, ρ = 0.72 µΩ·m: **≈ 30 mΩ** clean [C]; with an oxide or dirt film assume 100 mΩ [A], TO MEASURE
  (section 9).
- **Shape and size**: rectangle **54 (x, travel) × 36 (y) mm**, corner radius 3, long in the direction of travel to
  absorb the stop error. The lid is 82 mm long: the plate keeps 14 mm to each lid end and 8 mm to the flange screws at
  x = ±35 (4.4 mm to the edge of their head hole).
- **Tolerances** (blade 10 mm wide, knee at the plate centre when docked at nominal):
  longitudinal ±24 mm usable (±27 mm to the plate end minus 3 mm for the lid-plate joint, the countersink and the
  chamfer) [C]; lateral ±13 mm fully on the plate (36 − 10)/2, ±21 mm with 2 mm of overlap (36 + 10)/2 − 2 [C].
  Expected scatter ±10 mm longitudinal and ±15 mm lateral [A]: **measure 5 dockings before printing**.
  Roll of the robot: 1° over the 56 mm pitch is 1.0 mm of height difference between the two blades, inside the window [C].
- **Flush and without catching**: pocket in the lid 54.4 × 36.4, depth = plate 1.0 + silicone film 0.3 so that the plate
  top is level with the lid within ±0.15 mm [A]; top edges chamfered 0.4 × 45° (file), countersink 90° so that the
  M3 head is flush; the knee has a radius of 4 mm.
- **Separation**: pitch 56 mm, clear gap **20 mm**: a 10 mm blade can never touch both plates, and each blade stays on
  its own plate unless the lateral error exceeds 33 mm [C]. Distance to the flange screw heads 4.4 mm (generator
  check: ≥ 4 mm); creepage between plates 20 mm over the lid, water film discussed in section 10.
- **Fixing and watertightness** (no soldering):
  1. neutral-cure silicone film 0.3 mm under the plate plus a bead on the edge (also stops water under the plate);
  2. one **M3 × 10 countersunk A4 screw per plate**, flush; the lid hole is Ø3.4;
  3. on the underside of the lid a **recess Ø6.4 × 1.1** takes a **3 × 1.5 mm O-ring** (EPDM or NBR) around the shank, squeezed
     27 % by a metal M3 washer (OD 7) that lies flat on the lid underside: water running down the screw stops there;
  4. then ring terminal (0.5–1 mm² wire, M3), toothed washer, nut. Stack below the lid underside: 0.5 + 0.5 + 0.5 + 2.4
     + 1.1 of thread = **5.0 mm** [C]; lid under the plate: 5.0 − 1.3 pocket − 1.1 recess = **2.6 mm** left [C].
  A single screw per plate is enough because the plate is bonded over its whole area.
- **Screw positions** (enclosure frame, x along travel): (−10, +36) and (−10, −36), each on its plate and over volume
  that is free once the board is lowered (section 8) [C]. The generator checks a 5.0 mm envelope under each screw.

## 5. Blades (on the support)

- **Material**: stainless spring steel **1.4310 (301)**, ¾ to full hard (Rm ≥ 1000 MPa). E = 190 GPa [A, typical],
  yield Rp0.2 ≥ 760 MPa [A: ¾-hard 301 minimum, confirm with the supplier]. Corrosion: same alloy family as the plates,
  no verdigris. **Phosphor bronze** (E 110 GPa, yield 500–600 MPa) would also work (0.5 mm thick, same stress ratio
  0.67) but greens under 5 V and water, so it is kept as a plan B. **Beryllium copper**: not needed (stress and
  cycles are low), toxic dust when machined, expensive: rejected.
- **Dimensions**: width w = 10 mm, thickness t = 0.30 mm, free length L = 36 mm (slot exit to knee), nose 6 mm
  turned up 25° around a Ø8 pin (knee radius 4 mm), clamped part 14 mm: cut length 14 + 36 + 6 = **56 mm (cut 60)**.
  I = w t³/12 = 0.0225 mm⁴, EI = 4275 N·mm².
- **Shape**: straight strip lying on a seat of the holder tilted by **16.5°** (no hand-bent slope); only the nose is
  bent. Free drop D = L sin 16.5° = **10.2 mm** below the slot exit [C]. Optional printed bending jig for the nose.
- **Geometry chain** (vertical, from the lid top): holder lowest rigid point at S above the lid; the blade leaves the
  slot **3.0 mm above that point** (floor of the cavity), so the flexion at the dock is
  **δ = D − 3.0 − S = 7.2 − S mm** [C].

| Robot height vs nominal | S (lid to holder) | δ | Force | Stress |
|---|---|---|---|---|
| +3.0 mm: lid touches the holder (hard stop) | 0 | 7.2 mm | 2.15 N | 495 MPa |
| **+2.5 mm: upper limit of the window** | 0.5 | 6.7 mm | 2.00 N | 461 MPa |
| nominal | **3.0** | **4.2 mm** | **1.26 N** | 289 MPa |
| **−2.5 mm: lower limit of the window** | 5.5 | 1.7 mm | 0.50 N | 115 MPa |
| −4.2 mm: contact lost | 7.2 | 0 | 0 | 0 |

  Formulas [C]: K = E w t³/(4 L³) = 0.2749 N/mm perpendicular to the blade. The lid pushes the knee vertically and the
  knee slides freely along it: a vertical flexion δ is a deflection u = δ/cos θ perpendicular to the blade, and only
  F cos θ bends it (F cos θ = K u), so the vertical stiffness is K/cos²θ = 0.2989 N/mm (θ = 16.46°); force F = 0.2989 δ;
  stress σ = 3 E t (δ/cos θ)/(2 L²) = 68.8 MPa per mm of δ. Linear beam theory, δ/L up to 0.2: error about 5–10 %.
  Stiffness varies with t³: ±5 % on the thickness gives ±15 %, so **expect ±20 % on every force** and measure the
  blades (bench test: pressing 4.2 mm on a kitchen scale must show 128 ± 26 g).
- **Permanent set**: the worst case is the hard stop, 495 MPa = 0.65 of the yield, so a factor 1.5. The stop is
  geometric: the lid cannot go higher than the holder allows, so no deflection beyond 7.2 mm exists. Above
  +3 mm the lid rubs the holder (chamfered lead-in at its +x lower edge): adjust the rods, nothing is bent.
  Fatigue: about 2 dockings a day, 7,300 cycles in 10 years at ≤ 289 MPa, far below the endurance of this steel [C].
- **No jam, both directions**: Euler load of the blade as a column, P = π² EI/(4 L²) = **8.1 N**; the axial load at the
  hard stop with the stiffest blade (+20 %, 2.58 N), from friction (μ = 0.3 × 2.58 N = 0.77 N) and from climbing the 25°
  ramp (2.58 N × tan 25° = 1.20 N), is 1.98 N, P_cr / 4.1 (the generator requires at most P_cr / 4) [C]. The ramp was
  30° in the first draft: 2.27 N, above P_cr / 4, once the vertical stiffness was corrected to K/cos²θ. Natural
  frequency about 170 Hz (k = 299 N/m, effective mass 0.25 g): no chatter from the robot's
  vibration [C].
- **Contact force target**: 0.5–2 N as asked: 0.50 N at the lower limit and 1.26 N nominal [C]. The sliding wipes
  the plate at every docking and every exit.
- **What if the robot arrives higher**: up to +2.5 mm the force simply rises to 2.00 N; between +2.5 and +3.0 mm the knee
  is still above the foot of the ramp (6.7 and 7.2 mm of flexion against 7.0 mm of ramp depth: margin 0.3 mm at the
  window limit, none at the stop); beyond +3 mm the lid rubs the holder. **What if lower**: contact is lost below
  −2.5 mm (0.50 N) and absent at −4.2 mm: turn the rods.
- **To widen the window** if the docking height scatter turns out larger than ±2 mm: free length 40 mm, same 0.30 mm
  strip (K about 0.20 N/mm, vertical about 0.21 N/mm), ramp depth 9 mm (wedge hanging lower than the lip) gives about
  6 mm for 0.5–1.8 N at about 490 MPa (0.64 of the yield), but its axial load (about 1.74 N) exceeds P_cr / 4 (1.65 N)
  with the 25° ramp: a gentler ramp is needed [C, rough, to redo in the generator].

## 6. Height window compared with Qi

| | Qi (fixed transmitter) | Blades |
|---|---|---|
| Upper limit | +2.5 mm (lid rubs under the mount) | +2.5 mm (hard stop at S = 0.5) |
| Lower limit | −3.1 mm (out of the 8 mm Qi range) | −2.5 mm (0.50 N) |
| Window | 5.6 mm | **5.0 mm** |
| Behaviour at the limit | cliff: charging simply stops | force fades from 0.50 N to 0 over 1.7 mm more |
| Longitudinal / lateral tolerance | about ±10 mm (single-winding coil) | **±24 mm / ±13 mm (±21 mm electrical)** |
| Cleaning | none | the blade wipes the plate at every docking |

The window is not better than Qi's, but the longitudinal and lateral tolerances are about twice as large and the
failure is gradual. The height remains the weak point: **measure the docking height scatter first** (README,
Installation) and stay within ±2 mm.

## 7. Support under the roof

- **Kept**: two M6 rods at y = ±42 clamped between nuts on the VHB-bonded bridge, one turn = 1 mm, ±8 mm minimum travel,
  STL 05 identical. The 1 mm adjustment is still what sets S and the nominal force on site.
- **Replaced**: mount body and cap (STL 03, 04) by a smaller **holder** (03) and **cap** (04). No more coil cavity,
  PG9 gland, micro-USB cable or Adafruit board.
- **Holder 28 × 104 × 20 mm** (x × y × z) [A, the generator decides], printed floor down:
  - cavity closed by the cap, which the rod nuts clamp as today; captive M6 nuts and 14 mm blind holes unchanged;
  - at the −x end of the lower face, a **blade seat inclined 16.5°** and a slot 10.4 × 0.8 mm through the −x wall, one
    per blade, at y = ±28; slots sealed with a dab of silicone after assembly;
  - each blade root (14 mm, two Ø3.2 holes) is clamped on the seat by **two M3 A4 screws coming from below** (heads in
    counterbores, no rigid part below the lowest point), nut and **ring terminal** on the inner screw inside the cavity:
    one bolted joint carries the clamp and the electrical connection, no soldering;
  - **PG7 gland** (Ø12.5 hole, cable 3–6.5 mm) on the −x face as the PG9 was, towards the tower; Ø3 weep hole at the lowest
    point of the cavity; 5 mm 45° lead-in on the lower +x edge (a slightly high lid slides under);
  - engraved **+ / −** at each blade and a red/black (or brown/blue, marked) pair.
- **World positions** (flat deck) [C]: lid top 263.1, holder lowest point 266.1 (S = 3.0), blade exit 269.1, free knee
  258.9 (4.2 below the lid top). The holder hangs 28 mm behind the blade exit (x = box_x + 34.5 to + 62.5) and must stay
  ≥ 5 mm from the camera (checked), so the enclosure centre moves from x = 197 to **176 mm** with `CAM_GAP` = 15 mm; the
  enclosure then ends about 41 mm behind the camera and the gap to the rain sensor grows. TO MEASURE: free deck length behind
  the camera.
- **Clearance to the roof**: needs S 3.0 + holder 20 + cap 3 + 2 × 6.8 + 8 + bridge 50 = **97.6 mm**, model 111.9 mm
  (was 107.1 needed, 113.7 available) [C]. Rods **82 mm** (was 75), travel +22 / −16 mm [C].
- **Support setting**: with the robot docked, a 3.0 mm gauge (drill bit or hex key) between the lid top and the holder's
  lowest point, tighten, lock; remove it. Then check the force on the bench-measured blade (README).

## 8. Enclosure

- **Removed**: Qi receiver, 3 mm foam, window, engraved window corners, the `RX_*`, `FOAM_*`, `WINDOW`, `TX_*` parameters.
- **Lid**: `LID_T` 3.2 → **5.0 mm** (flat, no window): needed for the plate pocket (1.3), the O-ring recess (1.1) with 2.6 mm
  left, and the ramp face. The −x top edge is square (no 2 mm chamfer) so the ramp meets it. The flange screws become
  **M3 × 14** (lid 5.0, head top 34.0, nut bottom 23.2: 3.2 mm of thread past the nut) [C]. Engrave **+** and **−**
  at the plates and keep the forward arrow. Printing orientation unchanged (visible face on the bed, lip up).
  Weight: up to about 20 g more [A]. The blades pass over the two flush flange screw heads at x = −35 (y = ±35): countersunk, same
  stainless, accepted; if they catch, move the four side screws to y = ±52 (TO CHECK in the step scan).
- **Entry ramp (new STL 07)**: right-angled wedge bonded (MS polymer or epoxy, with a 2 mm tongue-and-groove printed in
  the lid end for the shear) to the −x end face of the lid and lip, width 98 mm (|y| ≤ 49), top level with the lid top,
  descending at **25° over 15.0 mm** to a 1.2 mm foot whose top is 7.0 mm under the plate plane (z 256.1), bottom at lid
  bottom − 3.2. Printed flat on its bottom face, ramp up, no supports. The enclosure is 15.0 mm longer on the −x side
  only; nothing on the deck is nearby. The ramp is never crossed at the +x end: the robot cannot go beyond the dock.
- **Lowered electronics** (STL 01 changes, outside shape unchanged): the screws and ring terminals need 5.0 mm under the
  lid, and the board was held high only for the Qi foam. `PCB_Z1` goes from 23.1 to **15.0 mm**; the cradle pillars, end
  stops and snap arms shorten with it (arms 12 mm instead of 20: hook strain ε = 3tδ/(2L²) = 3 × 1.2 × 0.6/(2 × 144) =
  0.75 %, below the 2 % of ASA [C], to be re-checked by the generator); the 18650 holder keeps `HZ0` = 7.1 (top at 27.1, under
  no plate screw). `IN_H`, flange, seal, the six screw positions (±35, ±35) and (0, ±62), vent and VHB are unchanged.
- **Wiring inside**: plate screws → ring terminals → two flexible 0.5–0.75 mm² wires, about 150 mm → **USB-C male** in
  the native USB socket (same socket as the Qi plug; the USB-UART socket stays free for flashing, 22 mm of straight
  plug room unchanged). Two ways, the generator carries the envelope: a **USB-C male pigtail** (VBUS and GND wired,
  24 AWG pair, over-moulded plug) or a **USB-C male to screw-terminal board**; the latter must fit the check envelope
  24 × 15 × 9 mm [A], otherwise use the pigtail. The **TVS** leads are clamped in the same terminals as the wires
  (with the pigtail: a small lever connector). Wires strain-relieved with a cable tie on a printed tab.
- **Lid open for flashing**: the plates stay on the lid, linked by 150 mm of wire; unscrew the adapter terminals to free it.
- **Antenna and watertightness checks**: the nearest plate edge (−y plate, y = −46, z = 34) is **19 mm** from the top
  of the FPC antenna (y = −55, z = 16) [C], against 24 mm for the Qi patch before. A thin stainless plate disturbs
  the antenna less than the ferrite sheet did, so the generator minimum for plates is **15 mm** (parameter), and
  the Wi-Fi RSSI is to be compared before and after (TO CHECK). Lowering the antenna 2 mm or narrowing the plate to 34 mm
  adds 1–2 mm if needed. Watertightness: seal, lip, screws and vent unchanged; two new passages sealed by silicone
  film and O-ring; the paper-towel shower test stays mandatory, with the plates mounted.
- **Heat**: no more 1–2 W dissipated by Qi coils in the box; only the linear charger (≈ (4.6 − 3.7) × 0.1 = 0.1 W) [C].
  The 65 °C module limit and light-coloured ASA remain.
- **Polarity**: mechanical keying is not possible (the pair is symmetrical on purpose); the pair is fixed on the robot
  and in the support, so polarity is set once at assembly: **+ on the +y plate and blade** (left when facing forward),
  red wire, multimeter check at the USB-C plug (VBUS, then GND) before plugging it in. The board's 1N5819 blocks a
  reversed supply, the unidirectional TVS then short-circuits it through its forward diode and the PTC trips: no damage,
  no charge, `vin_present` false.

## 9. Electrical design and protections

- **Supply**: the 5 V USB charger of the former Qi transmitter is reused (protected outdoor socket). Do **not** tap the
  Mammotion station (voltage unknown, may be 12 V or about 25 V, and its charge detection must not see a load) [E, user].
  Enter the board through the **USB-C plug** (1N5819 protected) and never through EXT1 pin 21 (no protection) [E].
- **PTC at the garage end** (radial, **0.75 A hold, 1.5 A trip**, ≥ 16 V, R ≈ 0.2–0.4 Ω [A, datasheet]), on the + wire
  next to the charger, in an IP65 junction box with a USB-A female screw-terminal breakout and lever connectors (leads of
  the PTC clamped in the connector, no soldering). Reason: a short on the plates or blades (conductive object, water
  film) is *downstream* of this PTC; a PTC inside the enclosure would not protect the 3 m cable and the blades. The
  charger's own limit is typically 2 A or more, so the PTC is the part that actually limits the fault.
- **TVS at the box**: **P6KE6.2A** unidirectional (stand-off 5.0 V, breakdown 5.9–6.5 V, clamp about 10 V at the peak
  current) across VBUS/GND at the board's USB-C plug [A, datasheet values to confirm on the part]. Reason: the board has
  no protection, the capacitors are 6.3 V, and the plates, blades and 3 m of cable form an antenna for ESD (the robot
  and its body charge up) and for inductive spikes at contact bounce. It sits at the point to protect, not at the
  exposed end. If the charger measures above 5.25 V, take the P6KE6.8A (leakage). It cannot hold the rail under 7 V for a
  very strong surge; the realistic threats here are far smaller.
- **Cable**: 2 × 0.75 mm² outdoor flexible (H05RN-F or silicone), 3 m, PG7 gland on the holder.
- **Voltage budget on the +5V rail** [C], charger 5.1 V [A]:

| | Peak 0.5 A (Wi-Fi TX) | Average 0.2 A |
|---|---|---|
| Cable 2 × 3 m × 0.75 mm² (0.14 Ω) | −0.07 V | −0.03 V |
| PTC (0.2 Ω) | −0.10 V | −0.04 V |
| Two contacts (2 × 0.1 Ω) | −0.10 V | −0.04 V |
| Internal wiring (0.05 Ω) | −0.03 V | −0.01 V |
| At the board's USB-C | 4.80 V | 4.98 V |
| 1N5819 (0.4 V at 0.5 A, 0.33 V at 0.2 A) | −0.40 V | −0.33 V |
| **+5V rail** | **4.40 V** | **4.65 V** |
| BL4054B minimum VCC | 4.25 V | 4.25 V |

  The margin is **0.15 V at peak, 0.4 V on average**: acceptable, but to be measured (TO CHECK) at the rail with Wi-Fi
  transmitting. If it is short: charger at 5.25 V, 1 mm² cable, PTC 1.1 A hold (R about 0.1 Ω), clean contacts. PWR_SENS reads a
  rail above about 3.6 V as high (0.75 × 3.3 V × 690/470), so presence detection works down to the BL4054B limit [C].
- **Current**: the charger takes 100 mA from the rail; the board adds the ESP32 (up to about 0.5 A in transmit peaks)
  [A]. Blades and plates are sized for 1 A; the PTC (hold 0.75 A) covers it.

## 10. Corrosion and safety

Question: 5 V permanently on bare blades under rain or sprinklers (electrolysis), and short circuits. Simplest
acceptable solution, with the reasons:

1. **Same stainless alloys for blades and plates, no galvanic couple, large gap (20 mm)**, a PTC at the source and a
   charger limited to 5 V. No switch and no electronics are added.
2. **The water bridge stays below 1 mA** [C]: a film 1 mm thick, 36 mm wide, over the 20 mm gap, of tap water
   (0.05 S/m): R = L/(σ w t) = 0.02/(0.05 × 0.036 × 0.001) = 11 kΩ, i.e. **0.45 mA** at 5 V; rainwater (0.003 S/m) gives
   0.03 mA. Wet 1 % of the year (88 h) at 0.45 mA, the charge is 140 C, so about 0.04 g of metal in a year at the +
   electrode (Faraday, 2 electrons per ion, 56 g/mol). Against 1.3 g for a blade and 15 g for a plate: negligible, and
   stainless is passive. Chlorides (coastal, de-icing salt) are the only real risk: TO CHECK.
3. **Where water bridges**: only the plates are on the robot; the blades are in the air under the roof (garage), away
   from standing water. The blades can be wetted by wind-driven rain; a film on the blades alone closes no circuit.
4. **Short circuit**: a blade (10 mm) cannot touch both plates (20 mm gap); a foreign conductive object across
   plates or blades is current-limited by the PTC (SELV, 5 V: no shock risk, no arcing).
5. **Not retained, but ready**: a charger on a timer or smart socket (windows when the robot is home), a nickel + gold
   flash on the plates, or phosphor bronze blades. Decide after **4 weeks of use**: inspect for black or green deposits,
   pitting, contact resistance (README check); count the faults in `/status`.
6. **Vibration, dirt**: the blades wipe every docking; clean the plates with alcohol and a soft cloth once a month.

## 11. Firmware and app

- **What PWR_SENS already gives**: `power.vin_present` (digital read, high when the rail is above about 3.6 V). With
  contacts it is true exactly when the blades touch the plates and the supply is live (or a USB cable is plugged). The
  gateway sends it as `charging` to the pilot every 5 s; the app already shows "Battery · plugged in"; the gateway's
  cutting rule (battery ≤ 4 % without power) already treats 5 V as power [E].
  So "robot docked, contact made" is **already derivable, no change is needed for the mechanical delivery**.
- Known limits [E]: while 5 V is present BAT_SENS reads the charger, the level is unknown (−1) until the robot leaves;
  the charger state is not readable (CHRG not wired); the source (USB or contacts) is not distinguished.
- **Comment and text changes only** (no version bump, `FW_VERSION` untouched): `board.h:27`, `board.cpp:88`, `main.cpp:656`
  and the READMEs say "Qi receiver": write "dock contacts".
- **Optional, later, small** (only if sessions show flicker): sample PWR_SENS at 10 Hz in the existing power poll and
  expose `power.vin_changes` (0↔1 transitions since boot) and `power.vin_since_s` in `/status` and the 30 s console line;
  about 30 lines plus one native test. It quantifies contact interruptions without any new hardware.

## 12. Changes in the generator (`cad/generate.py`)

### Naming

**Recommendation: rename the folder** `hardware/luba-esp32-qi` → **`hardware/luba-esp32-dock`** and the viewer to
`luba-esp32-dock.html`, in a **first, separate commit made only of `git mv` and link updates** (history is kept, the
diff stays readable). Keeping "qi" would leave a false name in every link. Links to update (grep done on 2026-10-27):
`README.md` (lines 464, 543, 595), `docs/README.md:85`, `docs/features/esp32-hardware.md` (lines 9, 169, 177, 178, 181),
`firmware/esp32-diag/README.md:3`, `THIRD_PARTY.md:24`, the generator (docstring, output path of the viewer), the viewer
`<title>` and the references inside the folder's README (its links to the firmware README stay as they are).
`CHANGELOG.md` lines 1224–1232 are history: leave the text, fix only the two links so they do not break. No script,
workflow or publication list refers to the folder (`tools/` checked).

### Parameters

Removed: `RX_PAD_*`, `FOAM_*`, `PAD_*`, `TX_*`, `WINDOW`, `SUP_WIN`, `SUP_CAV`, `QI_RANGE`, `AIR_GAP`, `GLAND_*` (PG9).
Changed: `LID_T = 5.0`, `SCREW_L = 14`, `PCB_Z1 = 15.0`, `HZ0` explicit (7.1), `CAM_GAP`, `SUP_*` → `HOLD_X, HOLD_Y, HOLD_H = 28, 104, 18`,
`CAM_TOP = 273` (replaces the literal).
New:

```
# Plates
PLATE_L, PLATE_W, PLATE_T = 54.0, 36.0, 1.0;  PLATE_Y = 28.0;  PLATE_GLUE = 0.3;  PLATE_CH = 0.4
CT_POS = ((-10.0, 36.0), (-10.0, -36.0))      # plate screws, enclosure frame (+ plate first)
ORING = (3.0, 1.5);  ORING_RECESS = (6.4, 1.1);  CT_STACK_H = 5.0;  CT_STACK_R = 4.0
# Blades
BLADE_W, BLADE_T, BLADE_L, BLADE_CLAMP = 10.0, 0.30, 36.0, 14.0
BLADE_E, BLADE_RP02 = 190e3, 760.0            # MPa, TO CHECK with the supplier
BLADE_NOSE_L, BLADE_NOSE_ANG, BLADE_KNEE_R = 6.0, 25.0, 4.0
BLADE_EXIT_H = 3.0                            # slot exit above the holder's lowest point
S_NOM, S_MIN = 3.0, 0.5                       # lid top to lowest rigid point: nominal and hard-stop margin
F_MIN, F_MAX, SIGMA_FRAC = 0.4, 2.5, 0.7      # force limits (N), stress limit as fraction of RP02
# Ramp
RAMP_ANGLE, RAMP_DEPTH, RAMP_FOOT, RAMP_Y = 25.0, 7.0, 1.2, 98.0
# Stop errors for the checks
DOCK_DX, DOCK_DY = 24.0, 13.0                 # longitudinal / lateral, TO MEASURE on site
ANT_PLATE_MIN = 15.0
ADAPT = (24.0, 15.0, 9.0)                     # USB-C male adapter envelope, TO CHECK
```

The blade slope is **derived**: θ = asin((δ_nom + S_NOM + BLADE_EXIT_H)/BLADE_L) with δ_nom = 4.2 (16.5°). Derived
values (K, forces, stress, window) go in the report and in the viewer metadata.

### Parts (STL)

| File | Status |
|---|---|
| `01_enclosure_base.stl` | modified: cradle, pillars and snap arms lowered (outer shape identical) |
| `02_enclosure_lid.stl` | modified: 5.0 mm, no window, 2 plate pockets, screw holes, O-ring recesses, +/− marks, square −x edge |
| `03_blade_holder.stl` | new (replaces `03_transmitter_mount`): seat, slots, cavity, captive nuts, PG7 |
| `04_holder_cap.stl` | modified (replaces `04_mount_cap`) |
| `05_roof_bridge.stl` | **identical** (no change to `BR_*`, `ROD_Y`) |
| `06_deck_shim.stl` | unchanged, only if `DECK_R` > 0 |
| `07_entry_ramp.stl` | new: wedge, printed on its bottom face |
| `08_blade_bending_jig.stl` | new, optional (delivery 2): nose bend (Ø8 pin, 25°) |

Not printed but modelled in the viewer: plates, blades, screws and O-rings, ring terminals, USB-C adapter, TVS, wires
(`contact_wire_path`, as `battery_wire_path`), cable, PG7 gland. Delivery 2: a 1:1 SVG cutting drawing of plates and blades
in `cad/` so the plates can be ordered laser-cut.

### New checks (the generation must fail)

| Check | Failure condition |
|---|---|
| Blade forces | F(δ_min) < `F_MIN` or F(δ at `S_MIN`) > `F_MAX` |
| Blade stress | σ(δ at the hard stop) > `SIGMA_FRAC` × `BLADE_RP02` |
| Flexion window | δ_nom − δ(F_MIN) < 2.0 mm or `S_MIN` side < 2.0 mm; report the window next to the Qi 5.6 mm |
| Euler | axial load (μ F + F tan `RAMP_ANGLE`) > P_cr / 4, F at the hard stop with +20 % stiffness |
| Plate tolerance sweep | for dx ∈ [−`DOCK_DX`, +`DOCK_DX`] and dy ∈ [−`DOCK_DY`, +`DOCK_DY`], each blade knee (3-D, with the 10 mm width) must lie on **its** plate with ≥ 2 mm of overlap and on no part of the other plate |
| No bridging | plate gap < `BLADE_W` + 6 mm; wrong-plate threshold (33 mm) < 2 × `DOCK_DY` |
| Plate layout | plate inside the lid flat top with ≥ 2 mm; distance to flange screw heads < 4 mm; to seal or vent < 3 mm |
| Passage without catching | step scan of the top surface along the knee lanes (y = ±28 ± `DOCK_DY`) over the whole approach: any step > 0.3 mm or slope > `RAMP_ANGLE` + 0.5° outside the ramp; the chamfer and countersinks counted |
| Ramp depth | `RAMP_DEPTH` < δ(`S_MIN`) + 0.3 mm |
| Ramp attachment | attachment face < 98 × 7 mm, or not lying on the lid + lip end face |
| Sweep | robot, enclosure and ramp against the **rigid holder**: gap < `S_MIN` − 0.05 along the whole path (replaces the `AIR_GAP` check; blades excluded on purpose, they are meant to be flexed by the lid) |
| Board and stack | any collision of a part, cable, adapter envelope, plate stack (r 4.0, 5.0 high) or wire with the base, the lid or each other (existing mechanism) |
| Antenna | < 15 mm to a plate (`ANT_PLATE_MIN`), < 20 mm to the cell, and the seal and screws checks as now |
| Lid | lid under a plate < 2.0 mm; O-ring squeeze outside 20–30 % |
| Holder | wall around the PG7 hole < 3 mm; blade slot not 10.4 × 0.8; rods, nuts and camera gap (< 5 mm) as now |
| Heights | lid top > `CAM_TOP` − 3 mm; roof clearance < 97.6 mm; rod travel < 8 mm up or down |
| Snap arms | hook strain > 2 % with the shorter arms |

### Viewer (`cad/viewer_template.html` and the generated metadata)

- Mode "+ Qi mount" → "+ Blade mount" (the `support` category is kept as an identifier); title, labels.
- New part cards: plate, blade, entry ramp, holder, USB-C adapter, TVS, O-ring, ring terminal, cable (the PTC box as text in
  the card); removed: `rx_*`, `foam`, `tx_*`, `usb_plug`, `mount_*`. New annotations: "stainless plate 54 × 36", "blade 10 × 0.3,
  δ = 4.2 mm, 1.26 N", "ramp 25°", "S = 3.0 mm", "O-ring under the nut", "TVS", "PTC at the charger".
- Details: replace "Qi charging" by **"Contact charging"** (section through a blade and a plate at the dock, flexion
  annotated) and "Adjustable mount" by "Blade holder"; "Electronics" shows the adapter and the wires.
- Metadata: replace `airGap, coilGap, plastic` by `sNom, deltaNom, forceNom, windowUp, windowDown`.
- **Docking animation** (delivery 2): bake about 30 blade poses (analytical elastic curve for the flexion computed at
  the surface height under the knee), switch the mesh by position; the status line shows "δ = x mm, F = y N" and
  "contact lost" outside the window. Delivery 1 shows the nominal pose only.
- Images 01–06 are screenshots of the viewer, but **no script in the repository produces them** (the commits only say
  "regenerated"): the developer re-takes them with a headless browser from the viewer's URL parameters (`#mode`,
  `#detail`, `#explode`) and, if possible, adds a small `cad/render_images.py` so that it is repeatable.

### README of the folder (new content)

Title "ESP32 on board the LUBA 2 AWD, charged by contacts at the base"; same structure as now with: shopping list
(section 13), parts to print (07 ramp, 03/04 holder), parts to cut (plates, blades, with drawings), assembly (plates, O-rings,
wires, adapter, TVS; holder, blades, ring terminals, gland), **bench tests** (blade force on a kitchen scale; plate
flushness with a ruler; continuity and polarity; leak test), **setting** (3.0 mm gauge, rods), **check with the
multimeter** (voltage at the USB-C plug with the robot docked ≥ 4.8 V under load, contact drop
V(blade) − V(plate) ≤ 0.1 V at 0.3 A, `vin_present` true at each of 5 dockings, rail ≥ 4.3 V with Wi-Fi on), points to
watch (docking height, corrosion after 4 weeks, cleaning, blade replacement, antenna RSSI, wet bridging), power budget
(charging at 100 mA unchanged, same 34 h), sources. Remove every Qi mention.

## 13. Shopping list (replaces the Qi lines; prices are rough, to check at purchase)

| Qty | Item | ≈ Price |
|---:|---|---:|
| 2 | Stainless **A4 (316L) sheet 1.0 mm**, cut 54 × 36 (100 × 200 mm sheet and snips, or laser-cut) | €8–15 |
| 1 | Stainless **spring strip 1.4310 (301) 10 × 0.30 mm**, 1 m (two 60 mm blades, spare) | €8–15 |
| 2 + 4 | A4 **M3 × 10 countersunk** + nuts + washers + toothed washers (plates); 4 × M3 × 12 pan head (blade clamps) | €3 |
| 2 | **O-ring 3 × 1.5 mm** EPDM or NBR | €1 |
| 4 | Ring terminals M3 for 0.5–1 mm² | €1 |
| 1 | **USB-C male pigtail** (VBUS + GND) or **USB-C male to screw-terminal board** | €2–5 |
| 2 | **TVS P6KE6.2A** (axial, one spare) | €1 |
| 2 | **PTC radial 0.75 A hold** (one spare) + 2 lever connectors | €3–5 |
| 1 | USB-A female screw-terminal breakout + IP65 junction box (garage end) | €5–8 |
| 3 m | Cable 2 × 0.75 mm² outdoor, flexible | €5–8 |
| 1 | **PG7** cable gland IP68 (replaces PG9) | €1 |
| 1 | Neutral-cure silicone (tube) | €5 |
| — | Lines kept: Olimex board, holder, protected cell, antenna, rods, nuts, silicone cord, VHB, vent, ASA (+ about 20 g), USB charger (reused); the flange screws become M3 × 14 | €60 |
| | **Removed**: Adafruit 2162 (€30–38), Qi patch (€6–10), right-angle micro-USB cable, 3 mm foam, PG9 | |

Total about **€95–125** [rough].

## 14. Work plan for the developer (two deliveries at most)

Work on the branch `session/boitier-lamelles`.

**Delivery 1: mechanics, generation, STL, docs (one PR)**

1. Commit 1, rename: `git mv hardware/luba-esp32-qi hardware/luba-esp32-dock` and the viewer, update the links of section 12.
   Acceptance: `git status` shows renames only; `grep -rn luba-esp32-qi` finds only CHANGELOG history text and no broken link.
2. Commit 2, generator: parameters, lid, plates, ramp, lowered board, holder and cap, blades (analytic), wiring and adapter
   envelopes, new checks, report, viewer data (cards, annotations, details, nominal pose), STL.
   Acceptance: `python3 -I cad/generate.py` ends with `Checks: OK`; STL 01–05 and 07 present (06 only if the deck is
   crowned), 05 byte-identical to the current one; each check of section 12 fails when its parameter is pushed past the limit
   (try `BLADE_T = 0.45`, `S_MIN = 3`, `DOCK_DY = 30`, `PLATE_Y = 20`, `RAMP_DEPTH = 5`); the report lists forces, stress, window,
   tolerances, rod length and the roof clearance.
3. Commit 3, docs: folder README (all of section 12), `docs/features/esp32-hardware.md` (title, and the section "Inductively
   charged enclosure" → "Contact-charged enclosure", lines 1–4, 9, 20, 26–27, 146–181), `README.md` (45, 331, 464, 543, 595),
   `docs/README.md:85`, `docs/FEATURES.md:68`, `docs/ROBOT_TRIAL_PLAN.md:157` ("induction pad" → "dock contacts"),
   `firmware/esp32-diag/README.md` (3, 59, 69), `firmware/esp32-gateway/README.md` (40, 66, 77, 92), comments in
   `board.h:27`, `board.cpp:88`, `main.cpp:656`, `THIRD_PARTY.md:24`, and a new `Unreleased:` entry at the end of
   `CHANGELOG.md` (the section does not exist since the 1.16.0 release; no version bump).
   Acceptance: `grep -rIn -i -w "qi\|induct\|wireless charg\|induction" hardware docs README.md firmware THIRD_PARTY.md ROADMAP.md`
   returns only the CHANGELOG, plus this design record if it is kept; `pio test -e native` and the Android build unaffected
   (comment-only changes); `tools/check.sh` if it covers docs.
4. Regenerate the viewer and the six images (static) and commit with the STL.

**Delivery 2: viewer animation and exports**

5. Blade poses baked in the docking animation with the δ/F status line; images 04 and 05 showing the flexion; `render_images.py`.
6. `08_blade_bending_jig.stl` and the SVG cutting drawings.
   Acceptance: the viewer opens without console error, the slider shows forces within the window and "contact lost" outside;
   images regenerated; the README images list matches.
7. Optional, only if requested: the `vin_changes` counter (section 11) with a native test.

Then, **on site (user)**: measure the docking scatter (5 dockings: height, along, across), print, bench-test blade
forces, assemble, run the leak and continuity tests, set S, check `vin_present` at each docking.

## 15. Alternatives considered

| Option | Why not |
|---|---|
| Thin lid 3.2 mm, 45° bevel cut in the lid | the flange under the lid limits the ramp to 3.2 mm: window about 2 mm |
| Frustum lid (bevel on all sides) | the flange screws fall on the slope, heads cannot be flush; the lid is also heavy |
| Blade tip leading (root at −x) | stubs into the lid end and jams on the way in |
| Pogo pins | no wiping, spring and guide cost, water in the barrel |
| Phosphor bronze / BeCu blades | green under 5 V and water / toxic dust and cost; bronze kept as plan B |
| Gold-plated plates | cost, scratched at the first docking, small gain at 100 mA |
| Switched supply (dead until docked) | needs electronics at the support; the numbers of section 10 do not justify it |
| Taking power from the Mammotion station | unknown voltage, can disturb its charge detection (user's rule) |
| EXT1 pin 21 as input | no protection on the board |

## 16. Open points (TO CHECK)

1. Docking scatter in height, along and across (assumed ±2 mm / ±10 mm / ±15 mm); height decides if 5.0 mm is enough.
2. Supplier, temper and yield of the spring strip (assumed 760 MPa, E 190 GPa); real blade force on a scale.
3. Contact resistance at the dock (assumed ≤ 0.1 Ω per contact) and rail voltage with Wi-Fi transmitting (≥ 4.3 V).
4. Datasheet values: BL4054B (VCC range), PTC resistance, P6KE6.2A leakage; real output voltage of the charger.
5. Size and height of the chosen USB-C male adapter (24 × 15 × 9 assumed); socket positions on the Olimex board (open
   point already in the README).
6. Plate-to-antenna distance 19 mm and Wi-Fi RSSI before/after; free deck length behind the camera.
7. Silicone film thickness and flushness of the plates (±0.15 mm); bonding strength of the ramp (hand test 20 N);
   blade crossing the flush flange screw heads at x = −35.
8. Corrosion after 4 weeks (chlorides, sprinkler water); surface condition of the plates.
9. Real-world validation is missing as for the rest of the enclosure: nothing here has been tried on the robot.
