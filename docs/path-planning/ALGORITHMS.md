# Comparison of algorithms

Sources consulted on 2026-10-09. The costs below are analyses of the implementations under consideration, not Android duration forecasts. The existing engine already has discrete cells, JTS, a visibility graph with A* and pivot validations.

## Formulation

Separate the cutting footprint, the chassis and the navigable domain. A shorter route that leaves more grass or violates a constraint is not better. Safety and feasibility are blocking. At comparable coverage, compare mission time, total distance, distance without mowing, maneuvers and repetitions; keep the metrics separate.

Notation: n = vertices after preprocessing; k = events/intersections; m = stripes; c = cells; s = tasks; K = variants per task; a = orientations; p = lateral phases; V/E = graph vertices/edges; Q = grid or lattice states; b = primitives per state; Cg = cost of the geometric check; I = iterations. JTS booleans and the computation of link matrices are counted separately.

## Decomposition, orientation and number of turns

| Approach | Indicative cost / property | Application and limitations | Effort / risk |
|---|---|---|---|
| Exact Boustrophedon Cellular Decomposition | Event-based connectivity sweep; order of magnitude `O((n+k) log n)` with suitable structures, excluding booleans and stripes | Monotone cells, explicit adjacencies; tangencies, simultaneous events and holes must be handled; does not prove maneuverability | High / medium |
| Exact trapezoidal decomposition | Similar cost family, splits at more events | Good partition oracle; often more cells and links | Medium to high / medium |
| Triangulation then convex/monotone merging | Typically `O(n log n)` for a method suited to polygons with holes, then merging of variable cost | Robust partition with a library; indirect coverage objective | High / medium |
| Current discrete cells | a generations of m line intersections; matching by fragments per line | Cheap baseline; events between stripes potentially missed | Existing / risk of incomplete coverage |
| Global Adaptive Sweep Orientation | `O(a p × generation/evaluation cost)` | Edge directions, principal axis as a candidate, refinement of the best angles; phase and orientation coupled | Medium / low with validated fallback |
| Per-cell orientation / MSA | Projected height as a proxy for the number of stripes; joint choice with links is combinatorial | Suited to L/U shapes; a local gain may be cancelled by a bad transition | High / medium |
| Minimum Turn Coverage | An objective, not a single algorithm; m serpentine rows imply m−1 row changes | Distinguish two 90° pivots from one 180° pivot; account for spaces at stripe ends | Medium to high / medium |

BCD is itself an **exact cellular decomposition**, not a family distinct from it. Choset and Pignon reduce coverage to visiting a graph of cells. Completeness in their model guarantees neither optimal time nor the feasibility of every rotation of a rectangle. [Choset and Pignon, FSR 1997](https://publications.ri.cmu.edu/coverage-path-planning-the-boustrophedon-decomposition).

"Exact" here describes an exhaustive partition of the polygonal domain, not exact coordinate arithmetic. A JTS implementation in doubles must handle predicate robustness, intersection precision and validation tolerances separately.

Huang studies the sum of projected heights and several sweep directions. His argument relies in particular on maneuvering space and on U-turn cost dominating transit variations. Use edge directions and this proxy to propose candidates; then recompute their complete mission. His optimality does not carry over directly to the LUBA with recharge and imposed passages. [Huang, ICRA 2001, text of the paper](https://static.aminer.org/pdf/PDF/000/352/549/optimal_line_sweep_based_decompositions_for_coverage_algorithms.pdf).

Proposal for this project: keep the baseline angles, add long-edge directions and refine around the best results. Test several phases in `[0, spacing)`. Keep non-dominated variants according to length, maneuvers, coverage and entry/exit poses. Do not drop a globally useful entry because of a slightly higher internal cost. Any truncation must be recorded: it removes any optimality guarantee.

Concentric rings and spirals remain useful for borders and certain pockets. Their offsets may split or vanish: check residuals and topology. The medial axis can diagnose bottlenecks and suggest maneuvering points; it does not cover an area on its own. Grid spanning-tree coverage (Spanning Tree Coverage) is a simple comparator, but resolution and border artifacts make it secondary here.

## Links and kinematic constraints

| Method | Indicative complexity | Guarantee and limitation |
|---|---|---|
| Visibility Graph | At most `O(n²)` edges; naive construction `O(n² Cg)`, then search `O((V+E) log V)` | Euclidean shortest path of the point in the represented polygonal domain; headings and chassis must be added. Lazy construction ≠ guaranteed linear memory |
| Dijkstra | `O((V+E) log V)` with a heap; memory `O(V+E)` | Non-negative costs, suited to the passage network; does not handle heading without enriching the state |
| A* | Same finite worst case under usual assumptions; practical efficiency depends on the heuristic | Already present. Euclidean distance admissible for length; distance/maximum speed for time. A turn penalty in the heuristic requires a bound proof |
| Grid A* | `O(Q log Q)` with bounded degree, excluding collisions | Simple reference; resolution h implies memory cost in `1/h²`, may close a passage; check diagonals |
| Theta* | Grid search + line-of-sight checks Cg and possible reopenings | Free angles, without optimality of the continuous path or curvature guarantee |
| Hybrid A* | About `O(Q b (log Q+Cg))` for bounded expansions; reopenings on top | Continuous propagation in `(x,y,heading[,direction])`; neither continuous optimum nor general completeness after per-cell merging |
| State lattice / primitive network | A* on a fixed graph: `O((Q+E) log Q)` + collisions | Better-controlled replay and budgets; guarantees only on the lattice; primitives to be calibrated |
| Dubins | Constant number of analytic families in free space, collisions on top | Forward only, bounded curvature; no pivots, and obstacles not built into the analytic solution |
| Reeds–Shepp | Finite number of analytic families, collisions on top | Forward/reverse, bounded curvature; cost of reversals and permission to reverse must be handled |

The framework of configuration spaces and search is presented in [Planning Algorithms, LaValle](https://www.lavalle.pl/planning/). The bounds above describe the structures proposed for the project, not a single theorem from the book.

Theta* connects grid vertices by visibility, but this angular freedom does not make heading changes feasible for a chassis. Compare it as a link generator, not as a validator. [Daniel, Nash, Koenig and Felner, JAIR 2010, author repository](https://arxiv.org/abs/1401.3843).

Hybrid A* searches for vehicle trajectories with continuous states within a discrete search. For the LUBA, explicitly allow calibrated pivots; a bicycle model chosen by default would be an unjustified assumption. [Dolgov et al., 2008, authors' publication](https://ai.stanford.edu/~ddolgov/dolgov08gppSTAIR.html).

[Dubins curves](https://lavalle.pl/planning/node821.html) and [Reeds–Shepp curves](https://lavalle.pl/planning/node822.html) serve as candidates or bounds for rolling turns. A configured minimum radius is not enough to make them commandable by the current BLE follower. A "shorter" link with several reversals can be slower and damage the lawn.

Recommended choice: visibility + A* and passage network as the nominal path; validation of the complete motion with poses; SE(2) primitive network or Hybrid A* only for localized failures. Never artificially enlarge an allowed area to obtain a path.

## Combinatorial ordering

A task initially represents a cell, a loop or a pocket, with direction variants and entry/exit poses. Opening a block into independent stripes remains a later improvement. A TSP on centroids ignores internal costs, headings and access, and therefore fits the need poorly.

| Method | Cost of one pass / solve | Usefulness and limitations |
|---|---|---|
| Imposed order + DP over variants | `O(s K²)` excluding the link matrix | Exact for that order and that finite set if costs are additive; battery and residual coverage require more state |
| Pass order in a cell, sliding window (Zamboni) | `O(n × S × 8)` with `S ≤ 4096` states (first row not done, rows done in a window of `W ≤ 12`, last pass) | Exact under the window rule (beam beyond 4,096 states); rolling U-turns between passes `m..m+2` rows apart, front at most `W` rows; in production for `ZAMBONI` ([Zamboni order](ENGINE.md#zamboni-order)) |
| Spiral rings (offset, opening, slanted links) | one erosion and one opening per ring (JTS buffers, 23 segments per quadrant), each ring piece checked once (surface and chassis envelope) | Contour-parallel coverage without U-turns; splits give one chain per part, the core and thin parts are zigzag pockets; in production for `SPIRAL` ([Spiral](ENGINE.md#spiral)) |
| Greedy insertion | Typically `O(s² K²)` depending on the variants explored | Simple improved baseline; local minima |
| Oriented 2-opt, relocation, variant reversal | `O(s²)` moves; `O(1)` delta only with suitable/precomputed evaluation | In the asymmetric case, reversing a block changes internal costs and headings; do not reuse the symmetric four-edge delta |
| 3-opt | `O(s³)` triples, evaluation cost on top | Small cases or bounded neighborhoods; gain to be measured |
| LNS / ALNS | `O(I × destroy-repair cost)`; naive insertion of r tasks about `O(r s K²)` | Removal of costly groups then reinsertion; fixed seed and budgets; no guaranteed optimum |
| GTSP / ATSP / MILP / CP-SAT | NP-hard in general, exponential worst case | Off-phone oracle for small cases; publish bound and gap; stop on timeout ≠ optimum |
| Subset DP | Order of magnitude `O(2^s s² K²)`, exponential memory | Small exact oracle on a fixed matrix; not for all the stripes of a large garden |

Bähnemann et al. formulate sweep variants as a GTSP and take obstacles in transitions into account. Their platform is aerial: reuse the formulation, not the margins or the published gains. [Preprint 2019 / FSR 2021](https://arxiv.org/abs/1907.09224).

Separating order from variant choice motivates a DP over variants after order changes. [Gutin and Karapetyan, GTSP local search](https://arxiv.org/abs/1005.5525). Adaptive destroy/repair is a possible extension, not a ready-to-plug CPP solver. [Røpke and Pisinger, ALNS, 2006](https://orbit.dtu.dk/en/publications/an-adaptive-large-neighborhood-search-heuristic-for-the-pickup-an/).

For multiple zones, keep the user order by default and optimize the variants with DP. A later free-order mode could add precedences, an imposed first zone and locked zones. Transit costs must use the whole reachable map, including unmowed zones; a missing link gives an impossible edge, never a substitute Euclidean distance.

Recharging makes the cost depend on the battery and exit points. Start with a validated geometric route and evaluate it with a common mission simulator. Then add energy optimization if returns dominate the results. An additive DP without battery state does not optimize the whole mission.

## Libraries and chosen combination

[Fields2Cover](https://github.com/Fields2Cover/Fields2Cover) provides modules for borders, stripes, ordering and trajectory; its current repository announces decompositions with obstacles and Dubins/Reeds–Shepp turns. The C++17/GDAL/GEOS/OR-tools base increases the Android/JNI cost: start with it as an out-of-app comparator, pinned version. The repository announces BSD-3-Clause; check the dependencies actually used before integrating.

[ETH Zürich's `polygon_coverage_planning`](https://github.com/ethz-asl/polygon_coverage_planning/blob/master/README.md) brings exact geometry and GTSP, with a ROS/CGAL toolchain and specific conditions for the cited solver. Initial use: research reference and external comparator, without adding its dependencies to Android.

JTS 1.20.0 is enough for the proposed Kotlin base. [`GeometryFixer`](https://locationtech.github.io/jts/javadoc/org/locationtech/jts/geom/util/GeometryFixer.html) may modify even an already valid object: every repair must be reported, and any unjustified extension of the safe terrain refused.

The recommended combination is: **versioned preprocessing → event-based BCD → stripes/borders parameterized by the tool → candidate orientations and phases → DP then 2-opt/relocation → visibility with headings → local primitives as a fallback → independent validation → complete mission score**. 3-opt/LNS come after profiling; reversing during coverage waits for follower support. Rolling U-turns are now offered as one of the candidates of the Turn Optimizer (Dubins arcs of 0.35 m, passes taken one in two or three, [U-turns](../features/mowing-preparation.md#u-turns-turn-optimizer)); their radius still has to be confirmed on the robot. No quantified gain is attributed to this proposal.
