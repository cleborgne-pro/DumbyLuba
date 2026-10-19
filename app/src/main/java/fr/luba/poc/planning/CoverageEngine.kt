package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*

/** Pure JVM facade, without controller or Android dependency. All outputs are local. */
object CoverageEngine {
    internal fun planAutomatic(input: PlanningRequest,checkpoint: () -> Unit): PlanningResult =
        AutomaticPlanner.plan(input,CoverageMode.LINES,checkpoint)
    /** Historical 0.15: benchmark comparator only, never the interface.
     * Do not pay for an extra repair on a plateau where
     * phase 2 keeps exactly the initial mission. The corpus shows the
     * extra cost without gain on Fosse; no global optimum guarantee is inferred.
     */
    internal fun planPreviousAutomatic(input: PlanningRequest,checkpoint: () -> Unit): PlanningResult =
        try { planInternal(input,PlannerStrategy.EXPERIMENTAL_LNS,checkpoint,automatic=true) }
        catch(e: org.locationtech.jts.geom.TopologyException) {
            val failure=CandidateResult("geometry",PlanStatus.NUMERIC_FAILURE,null,null,null,listOf(e.message ?: tr("Topology error", "Erreur topologique")))
            PlanningResult(input.snapshot().hash,PlannerStrategy.EXPERIMENTAL_LNS,failure,listOf(failure),emptyList(),emptyMap(),0,0)
        }
    /** Loop, grid and checkerboard: distinct objective, shared assembly and validation.
     * The survey without blades does not impose a mowed-surface rate.
     */
    internal fun planPattern(input: PlanningRequest,mode: CoverageMode,checkpoint: () -> Unit): PlanningResult {
        require(mode!=CoverageMode.LINES)
        if(mode==CoverageMode.OPTIMIZED) return planOptimized(input,checkpoint)
        return AutomaticPlanner.plan(input,mode,checkpoint)
    }
    /** Mixed orientations kept only if the complete evaluated mission (actual links,
     * U-turns, pivot duration measured on the robot) does at least as well as the simple
     * zigzag, within 0.2 surface point. The per-zone ranking is only an estimate.
     */
    /** Pivot and stop measured on the robot (docs/TRACKING_EXECUTION.md): a U-turn costs
     * ≈ 10 s there, against ≈ 4 s with the default computation setting (45 °/s, no stop). */
    const val ROBOT_TURN_DEGREES_PER_SECOND = 27.0
    const val ROBOT_STOP_SECONDS = 1.5
    /** Mission score with the pivot duration measured on the robot: same metrics,
     * only the rotation and stop durations change. Used to decide between mixed and simple. */
    internal fun robotScore(candidate: CandidateResult,config: PlanningConfig): Double {
        val m=candidate.metrics!!
        val turnRate=minOf(config.turnDegreesPerSecond,ROBOT_TURN_DEGREES_PER_SECOND)
        val stop=maxOf(config.stopSeconds,ROBOT_STOP_SECONDS)
        val duration=m.distanceM/config.speedMps+m.turnDegrees/turnRate+m.pivots*stop
        return config.weights.score(m.copy(durationSeconds=duration))
    }
    internal fun planOptimized(input: PlanningRequest,checkpoint: () -> Unit): PlanningResult {
        val mixed=AutomaticPlanner.plan(input,CoverageMode.OPTIMIZED,checkpoint)
        val simple=AutomaticPlanner.plan(input,CoverageMode.LINES,checkpoint)
        val a=mixed.selected; val b=simple.selected
        val keepMixed=a.valid && (!b.valid || robotScore(a,input.config)<=robotScore(b,input.config)+1e-6 &&
            a.metrics!!.coveredAreaM2+1e-6>=b.metrics!!.coveredAreaM2-0.002*b.metrics.targetAreaM2)
        val chosen=if(keepMixed) mixed else simple
        val selected=chosen.selected.copy(name="optimized-"+chosen.selected.name,
            preview=chosen.selected.preview?.copy(mode=CoverageMode.OPTIMIZED))
        return chosen.copy(selected=selected,candidates=mixed.candidates.map { it.copy(name="mixed-"+it.name) }+
            simple.candidates.map { it.copy(name="single-"+it.name) },
            phaseNanos=(mixed.phaseNanos.keys+simple.phaseNanos.keys).associateWith { (mixed.phaseNanos[it] ?: 0)+(simple.phaseNanos[it] ?: 0) },
            transitionQueries=mixed.transitionQueries+simple.transitionQueries,transitionCacheHits=mixed.transitionCacheHits+simple.transitionCacheHits)
    }
    fun plan(input: PlanningRequest,strategy: PlannerStrategy = PlannerStrategy.BASELINE,checkpoint: () -> Unit = {}): PlanningResult =
        try { planInternal(input,strategy,checkpoint) }
        catch (e: org.locationtech.jts.geom.TopologyException) {
            val failure=CandidateResult("geometry",PlanStatus.NUMERIC_FAILURE,null,null,null,listOf(e.message ?: tr("Topology error", "Erreur topologique")))
            PlanningResult(input.snapshot().hash,strategy,failure,listOf(failure),emptyList(),emptyMap(),0,0)
        }
    private fun planInternal(input: PlanningRequest,strategy: PlannerStrategy = PlannerStrategy.BASELINE,
        checkpoint: () -> Unit = {},mode: CoverageMode = CoverageMode.LINES,automatic: Boolean = false): PlanningResult {
        val request=input.snapshot()
        val phases=linkedMapOf<String,Long>()
        fun <T> timed(name: String,block: () -> T): T {
            checkpoint()
            val begin=System.nanoTime()
            return try { block() } finally { phases[name]=(phases[name] ?: 0L)+System.nanoTime()-begin }
        }
        val terrain = try { timed("preprocessing") { PlanningTerrain(request) } }
        catch (e: IllegalArgumentException) {
            val invalid=CandidateResult("input",PlanStatus.INVALID_INPUT,null,null,null,listOf(e.message ?: tr("Invalid input", "Entrée invalide")))
            return PlanningResult(request.hash,strategy,invalid,listOf(invalid),emptyList(),phases,0,0)
        }
        val transitions=TransitionPlanner(terrain)
        val evaluator=MissionEvaluator(terrain,transitions)
        val candidates=mutableListOf<CandidateResult>()
        val refinements=mutableListOf<Preview>()
        val joint=strategy==PlannerStrategy.EXPERIMENTAL_JOINT || strategy==PlannerStrategy.ADVANCED
        val lns=strategy==PlannerStrategy.EXPERIMENTAL_LNS || strategy==PlannerStrategy.ADVANCED
        val c=request.config
        val baseline=try { timed("baseline_generation") {
            MultiZonePlanner.plan(terrain.zones,terrain.holes,c.spacingM,c.marginM,mode,c.borders,
                c.borderPasses,c.noGoPasses,terrain.obstacleDiscs,request.robot,c.boundary==BoundaryMeaning.RECORDED_TRACE,
                request.start,c.bordersFirst,c.nearbyNoGo,progress={ _,_,_->checkpoint() },outerOffsetM=c.outerOffsetM).copy(localOnly=true)
        } } catch (e: IllegalArgumentException) {
            val failed=CandidateResult("baseline",PlanStatus.NO_SOLUTION_FOUND,null,null,null,listOf(e.message ?: tr("Generation impossible", "Génération impossible")))
            return PlanningResult(request.hash,strategy,failed,listOf(failed),terrain.assumptions,phases,transitions.queries,transitions.hits)
        }
        candidates.add(timed("evaluation") { evaluator.evaluate("baseline",baseline) })
        if(mode!=CoverageMode.LINES) {
            val reference=candidates.first()
            if(reference.valid) try {
                val repaired=timed("neighborhood_search") { NeighborhoodSequencer(terrain,evaluator,checkpoint).optimize(baseline) }
                candidates.add(timed("evaluation") { evaluator.evaluate("pattern-local-search",repaired,reference.metrics!!.coveredAreaM2) })
            } catch(e: org.locationtech.jts.geom.TopologyException) {
                candidates.add(CandidateResult("pattern-local-search",PlanStatus.NUMERIC_FAILURE,null,null,null,listOf(e.message ?: tr("Numerical error", "Erreur numérique"))))
            } catch(e: IllegalArgumentException) {
                candidates.add(CandidateResult("pattern-local-search",PlanStatus.NO_SOLUTION_FOUND,null,null,null,listOf(e.message ?: tr("Link refused", "Raccord refusé"))))
            }
            val selected=candidates.filter { it.valid && preservesReference(it,reference) }.minByOrNull { it.score!! } ?: reference
            return PlanningResult(request.hash,strategy,selected,candidates.toList(),terrain.assumptions+
                tr("Valid in the model; physical behavior not certified", "Validité dans le modèle ; comportement physique non certifié"),phases.toMap(),transitions.queries,transitions.hits)
        }
        if (strategy != PlannerStrategy.BASELINE) {
            val floor=candidates.first().metrics?.coveredAreaM2 ?: 0.0
            val sequencer=TaskSequencer(terrain,evaluator,checkpoint)
            try {
                val reordered=timed("sequencing") { sequencer.optimize(baseline) }
                candidates.add(timed("evaluation") { evaluator.evaluate("baseline-dp-2opt",reordered,floor) })
            } catch(e: org.locationtech.jts.geom.TopologyException) {
                candidates.add(CandidateResult("baseline-dp-2opt",PlanStatus.NUMERIC_FAILURE,null,null,null,listOf(e.message ?: tr("Numerical error", "Erreur numérique"))))
            } catch(e: IllegalArgumentException) {
                candidates.add(CandidateResult("baseline-dp-2opt",PlanStatus.NO_SOLUTION_FOUND,null,null,null,listOf(e.message ?: tr("Order refused", "Ordre refusé"))))
            }
            val options=AdaptiveOrientation.options(terrain.zones,baseline,c.candidateBudget-1)
            for ((index,option) in options.withIndex()) {
                checkpoint()
                try {
                    val variant=timed("candidate_generation") {
                        val previews=mutableListOf<Preview>()
                        for (zone in terrain.zones) {
                            checkpoint()
                            val entry=previews.lastOrNull()?.cuttingPaths?.lastOrNull()?.lastOrNull() ?: request.start
                            val settings=if (strategy==PlannerStrategy.EXPERIMENTAL_BCD)
                                option.copy(decompose={ BoustrophedonDecomposer().cells(it) }) else option
                            val raw=CoveragePlanner.plan(zone.points,terrain.holes,c.spacingM,c.marginM,CoverageMode.LINES,c.borders,
                                c.borderPasses,c.noGoPasses,terrain.obstacleDiscs,request.robot,c.boundary==BoundaryMeaning.RECORDED_TRACE,
                                entry,sweepOptions=settings,outerOffsetM=c.outerOffsetM)
                            previews.add(MowingOrder.apply(raw,c.bordersFirst,c.nearbyNoGo,entry))
                        }
                        MultiZonePlanner.combine(terrain.zones,previews,CoverageMode.LINES,request.start).copy(localOnly=true)
                    }
                    // DP and local search reserved for the seed: orientations remain a separate ablation.
                    candidates.add(timed("evaluation") { evaluator.evaluate("orientation-${option.angles.first()}-phase-${option.phase}",variant,floor) })
                    if (joint) refinements.add(variant)
                } catch(e: org.locationtech.jts.geom.TopologyException) {
                    candidates.add(CandidateResult("candidate-$index",PlanStatus.NUMERIC_FAILURE,null,null,null,listOf(e.message ?: tr("Numerical error", "Erreur numérique"))))
                } catch (e: IllegalArgumentException) {
                    candidates.add(CandidateResult("candidate-$index",if(e is PlanningBudgetExceeded) PlanStatus.BUDGET_EXHAUSTED else PlanStatus.NO_SOLUTION_FOUND,null,null,null,
                        listOf(e.message ?: tr("Candidate refused", "Candidat refusé"))))
                }
            }
            // Freeze phase 2 before any refinement: same search and same selection.
            val phase2=candidates.filter { it.valid }.minByOrNull { it.score!! }
            fun refine(name: String,seed: Preview,useNeighborhood: Boolean) {
                checkpoint()
                try {
                    val refined=timed(if(useNeighborhood) "neighborhood_search" else "joint_sequencing") {
                        if(useNeighborhood) NeighborhoodSequencer(terrain,evaluator,checkpoint).optimize(seed)
                        else sequencer.optimize(seed,preserveLoopClasses=true)
                    }
                    candidates.add(timed("evaluation") { evaluator.evaluate(name,refined,floor) })
                } catch(e: org.locationtech.jts.geom.TopologyException) {
                    candidates.add(CandidateResult(name,PlanStatus.NUMERIC_FAILURE,null,null,null,listOf(e.message ?: tr("Numerical error", "Erreur numérique"))))
                } catch(e: IllegalArgumentException) {
                    candidates.add(CandidateResult(name,PlanStatus.NO_SOLUTION_FOUND,null,null,null,listOf(e.message ?: tr("Repair refused", "Réparation refusée"))))
                }
            }
            refinements.forEachIndexed { index,seed -> refine("joint-$index",seed,false) }
            val plateau=automatic && phase2!=null && phase2.planHash==candidates.first().planHash
            if(plateau) phases["neighborhood_skipped_plateau"]=0
            if(lns && phase2!=null && !plateau) {
                refine("lns-phase2",phase2.preview!!,true)
                // Two passes at most, same LNS budget in the ablation and the hybrid.
                val seed=candidates.filter { it.valid }.minByOrNull { it.score!! }
                if(seed!=null && seed.planHash!=phase2.planHash) refine("lns-refined",seed.preview!!,true)
            }
            if(joint || lns) {
                // The weighted score may buy a slower mission: here the phase 2 duration,
                // distance and coverage are explicit safeguards.
                val selected=candidates.filter { it.valid && (phase2==null || preservesReference(it,phase2)) }
                    .minByOrNull { it.score!! } ?: candidates.maxBy { it.metrics?.coveredAreaM2 ?: -1.0 }
                return PlanningResult(request.hash,strategy,selected,candidates.toList(),terrain.assumptions+
                    tr("Tool modelled as a disc; time without recharge, level B; no execution authorization",
                        "Outil modélisé par un disque ; temps sans recharge, niveau B ; aucune autorisation d'exécution"),
                    phases.toMap(),transitions.queries,transitions.hits)
            }
        }
        val selected=candidates.filter { it.valid }.minByOrNull { it.score!! }
            ?: candidates.maxBy { it.metrics?.coveredAreaM2 ?: -1.0 }
        return PlanningResult(request.hash,strategy,selected,candidates.toList(),terrain.assumptions+
            tr("Tool modelled as a disc; time without recharge, level B; no execution authorization",
                "Outil modélisé par un disque ; temps sans recharge, niveau B ; aucune autorisation d'exécution"),
            phases.toMap(),transitions.queries,transitions.hits)
    }
    internal fun preservesReference(candidate: CandidateResult,reference: CandidateResult): Boolean {
        val a=candidate.metrics ?: return false
        val b=reference.metrics ?: return false
        return candidate.valid && a.coveredAreaM2+1e-6>=b.coveredAreaM2 &&
            a.durationSeconds<=b.durationSeconds+1e-6 && a.distanceM<=b.distanceM+1e-6 &&
            a.transitM<=b.transitM+1e-6 && candidate.score!!<=reference.score!!+1e-6
    }
}
