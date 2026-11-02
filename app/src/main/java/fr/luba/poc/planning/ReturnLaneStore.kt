package fr.luba.poc.planning

import android.content.Context
import androidx.core.content.edit
import fr.luba.poc.simulation.ReturnLaneChoice
import fr.luba.poc.simulation.ReturnLaneHistory
import fr.luba.poc.simulation.ReturnLanes

/**
 * Lanes of the last returns to each base ([ReturnLanes]), kept between two launches; [onRecord] is told of every return
 * planned (event log).
 */
class ReturnLaneStore(context: Context, private val onRecord: (ReturnLaneChoice) -> Unit = {}) : ReturnLaneHistory {
    private val prefs = context.applicationContext.getSharedPreferences("return-lanes", Context.MODE_PRIVATE)

    override fun recent(key: String): List<Int> = decode(prefs.getString(key, null))

    override fun record(key: String, choice: ReturnLaneChoice) {
        prefs.edit { putString(key, (recent(key) + choice.lane).takeLast(ReturnLanes.KEEP).joinToString(",")) }
        onRecord(choice)
    }

    companion object {
        /** Saved list "0,2,1": unreadable entries are left out (older or damaged value). */
        fun decode(saved: String?): List<Int> = saved.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
    }
}
