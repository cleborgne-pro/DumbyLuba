package fr.luba.poc.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember

/*
 * Callbacks built from local functions of a composable (`::calculateMowingPreview`). Two references to one local
 * function are equal whatever the values they capture: FunctionReference.equals compares the name, the signature and
 * the owner, not the captured values. Compose (strong skipping) compares function parameters and lambda keys with
 * equals, so a child that memoizes a lambda around such a reference, or is skipped, keeps the first reference and
 * runs it on the values of an old composition (task settings, map, obstacles…): "Map changed: recomputation
 * required" on every settings change before "Route preview" (2026-10-27). Passing the action through [latestAction]
 * gives one stable callback that always runs the action of the latest composition.
 */

/** Stable callback running [action] as passed by the latest composition (see the note above). */
@Composable
fun latestAction(action: () -> Unit): () -> Unit {
    val latest = latestState(action)
    return remember { { latest.value() } }
}

/** [latestAction] for a callback taking one argument. */
@Composable
fun <T> latestAction1(action: (T) -> Unit): (T) -> Unit {
    val latest = latestState(action)
    return remember { { value: T -> latest.value(value) } }
}

/** Holder updated on every composition; referential equality, unlike rememberUpdatedState, so an equal reference still replaces the previous one. */
@Composable
private fun <F> latestState(action: F): MutableState<F> =
    remember { mutableStateOf(action, referentialEqualityPolicy()) }.apply { value = action }
