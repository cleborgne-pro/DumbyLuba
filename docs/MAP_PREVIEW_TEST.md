# Map and route preview — 0.3.0

> [!NOTE]
> **Historical procedure** (versions 0.3.0 – 0.4.x), kept as a record of the first validation of the map and of the
> preview. Several labels and screens have changed since; the obsolete steps are marked below. Current pages:
> [Mammotion account](features/mammotion-account.md), [map](features/map.md),
> [preparing a mowing session](features/mowing-preparation.md), [interface](features/interface.md).
>
> | Then (0.3.0 – 0.4.x) | Now |
> |---|---|
> | **Account** tab, **Connect my account**, **Read the map of Luba-…** | Me → **Mammotion account** → **Sign in**, then **Read the map** |
> | **Map & route**, source **Cloud** / **BLE** | the map (Device tab → map thumbnail), **Map** button → *Map source*: **Read the robot map**, **Mammotion account map**, **Try the demo** |
> | **COMPUTE ROUTE**, **Route settings** | **Mow** → zones in mowing order → **Next** → **Task settings** → **Route preview** |
> | **Lines** mode | **Zigzag** (also **Optimized** and **Checkerboard**) |
> | **Outer borders and no-go zones** | *Advanced* settings: **Perimeter loops** and **Loops around no-go zones** (0 to 4) |
> | **Select all** / **Clear**, zone chips | **Add all** / **Clear** in the zone choice |
> | **Recenter** | **Recenter the map** side button |
> | **Debug panel** and its STOP | **Service** tab; STOP in the global bar |
> | blue passes, orange borders | white passes and border laps, the laps on a lighter edging (0.64.0) |

## Remotely

1. The robot must be switched on and connected to the Internet. Phone with Internet; no BLE proximity is needed for the cloud read.
2. **Account** tab (now Me → **Mammotion account**) → enter the Mammotion e-mail/password → **Connect my account** (now **Sign in**). The password is cleared from the form after submission and not kept. The session is encrypted with Keystore.
3. Choose **Read the map of Luba-…** (now **Read the map**). Wait for "Cloud map complete" (now "Cloud map and base received"). If HTTP/auth/MQTT refuses, note the code and the step; do not share your password or token.
4. Go back to **Map & route**, source **Cloud**, tap a green zone. Adjust spacing and margin, then **COMPUTE ROUTE**. *(Obsolete: see the table above for the current flow.)*
5. The blue route (white since 0.64.0) is a coverage preview. Distance, orientation and separate sections are shown. No motion command is sent.

## BLE or without a robot

- Source **BLE** → Debug/Robot → connect → back to the map → **Read the BLE map**. *(Now: Service → **Bluetooth connection**, then on the map **Map** → **Read the robot map**.)*
- Demo: button at the bottom (now **Map** → **Try the demo**), fictitious map explicitly labeled as such. Test the computation, the central obstacle and different margins. Do not mistake it for your garden.

## Limitations

- Real/cloud map and firmware still to be validated: the upstream port guarantees neither server acceptance nor support for every firmware. Mammotion MQTT TLS only, no legacy Aliyun transport. Expired session: log out then log back in. No cloud command for cutting/moving.
- Outlines of types 0/1, paths of type 2. Zone names from nameTime when present, no dock/SVG. Unknown type/partial map: computation forbidden. A complete cache is shown with its date and may be out of date.
- Sweeps computed by JTS polygon intersections, borders in a free space eroded by the margin + 2 cm. Lines or checkerboard (two perpendicular sweeps). Orientations, orders and directions are compared, with a penalty for separate sections. No global optimum guaranteed, turns not smoothed. Sections that cannot be linked are kept separate.
- Margin measured from the center of the route: it must account for the robot's footprint and for uncertainties before any future real tracking.
- BLE position if available; live cloud position/battery not shown yet. The protections of the existing POC remain in the Debug panel, STOP accessible.

The user account is not connected until the user has entered their credentials and the login has succeeded. Never share a ZIP or screenshot containing a password/token.

Real validation on 2026-10-06: account connected after user input, one LUBA listed, 43/43 elements downloaded from the cloud and kept in cache. Map and blue coverage route observed on the Fold. This validates the display/preview for this map, not physical tracking. Computed previews are logged (`planned_path_preview`) with orientation, distance, sections and points; they are included in the Debug ZIP.


## Gestures and new modes

1. On the real map or the demo, pinch to zoom, twist two fingers to rotate and drag to pan. "Recenter" restores the framing. Selecting a zone after a transformation must select the same geometric zone.
2. Compute in **Lines** (now **Zigzag**) with **Outer borders and no-go zones** (now *Advanced* → **Perimeter loops** and **Loops around no-go zones**): blue rows as solid lines, borders (contour lap and no-go zones) as **orange dotted lines**, now drawn separately so they can no longer be mistaken for rows. Visually check the exclusions and the margin.
3. Switch to **Checkerboard** and recompute: two perpendicular sweeps, longer distance and duration. Disable borders to compare.
4. Vary the margin and spacing. A zone that is too narrow must refuse the computation; sections without a safe link stay separate.
5. Open the **Debug panel**: the big STOP is still available there; it has been removed from Map and Account. *(Obsolete: STOP is now in the global bar on every screen, see [interface](features/interface.md#global-stop-bar).)* The computation does not start any motor test.

Gestures only change the GPU graphic transformation; metric coordinates and routes are not recomputed during gestures. Exports include the mode and the outline counts.


## 0.4.0 — several zones and new layout

- Tap several zones: each tap adds or removes the zone. Tapping empty space keeps the selection. Named chips allow the same selection without touching a small polygon. **Select all** / **Clear** available (now **Add all** / **Clear**; the tap order is the mowing order).
- Choose Lines or Checkerboard, then compute: animated circular indicator, name of the zone being processed and real count of finished zones. No artificial per-orientation percentage.
- Each zone is computed independently; distance and outlines are aggregated. No inter-zone link is invented. An impossible zone refuses the whole computation and shows its name.
- Larger map, contrasting selection colors, floating legend and Recenter button, rounded preparation panel. Spacing and margin in **Route settings**.
- References consulted: [Mammotion, new hierarchy of map actions](https://eu.mammotion.com/blogs/news/mammotion-app-new-features), [MOVA, multi-zone management](https://www.mova.tech/products/mova-600-and-1000-robot-lawn-mower), [Dreame, zones and mowing modes](https://global.dreametech.com/products/a2). Original Compose layout; no visuals copied from these apps.

### Preview readability when zoomed

The white stripes represent the cutting width in meters (40 cm by default),
which follows the map zoom. The center line stays thin and constant on screen.
Recorded passes are blue, zone outlines green and planned borders
orange: a pass visible in an unselected zone is not
a mowing pass. The legend in the dialog recalls this distinction. *(Obsolete colors: since 0.64.0, passes and
border laps are white, the laps on a lighter edging; see [preparing a mowing session](features/mowing-preparation.md).)*

Visual check: zoom in/out on the same exclusion; the relative
positions of passes and outlines must stay identical. Only the thickness
of the center line and outlines is compensated to stay readable.

Centre-line joins are rounded: tight U-turns must not
produce white spikes (miter joins). Mowing stripes and lines
are clipped to the union of the selected zones, after subtracting exclusions.
This clipping only concerns the display; the reference map tests check
the geometry of routes and chassis separately, before any display.

Borders also show a transparent white stripe at the actual cutting
width, clipped to the zones and outside exclusions; their orange center line stays
visible in the preview. The start is a purple dot labeled "Start".
