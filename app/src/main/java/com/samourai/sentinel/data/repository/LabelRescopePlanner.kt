package com.samourai.sentinel.data.repository

import com.samourai.sentinel.data.db.entity.LabelEntry
import com.samourai.sentinel.data.db.entity.UtxoLabel

/**
 * #42: pure planner for rescoping label rows when the keychain
 * derivation corrects the network flag. Rows written under the old
 * network are copied to the real one (all other fields — createdAt,
 * updatedAt, origin — preserved by data-class copy) and the distinct
 * source networks are purged.
 *
 * The planner is deliberately pure so its semantics are JVM-golden
 * covered without Room; the repository executor applies the plan
 * inside one withTransaction (copy-then-purge, so a crash between
 * steps leaves the rows recoverable on the next run — the plan is
 * idempotent: rows already on target produce an empty plan, so the
 * purge can never fire on good rows).
 */
object LabelRescopePlanner {

    data class RescopePlan(
        val utxoCopies: List<UtxoLabel>,
        val entryCopies: List<LabelEntry>,
        val purgeNetworks: Set<String>
    )

    fun plan(
        target: String,
        utxoLabels: List<UtxoLabel>,
        entries: List<LabelEntry>
    ): RescopePlan {
        val foreignUtxos = utxoLabels.filter { it.network != target }
        val foreignEntries = entries.filter { it.network != target }
        return RescopePlan(
            utxoCopies = foreignUtxos.map { it.copy(network = target) },
            entryCopies = foreignEntries.map { it.copy(network = target) },
            purgeNetworks = (foreignUtxos.map { it.network } +
                foreignEntries.map { it.network }).toSet()
        )
    }
}
