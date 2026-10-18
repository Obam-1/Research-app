package com.resellcomps.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resellcomps.app.ValueState
import com.resellcomps.core.SoldComp
import com.resellcomps.core.SoldValue
import com.resellcomps.core.SoldValue.usd

/**
 * Value from the user's own screenshots of eBay's sold results: the price comes only from
 * matching sold listings, and every listing it used (or left out) is shown as proof.
 */
@Composable
fun ValueCard(
    state: ValueState,
    currentQuery: String,
    onPickScreenshots: () -> Unit,
    onToggle: (Int) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Value from real sold listings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when (state) {
                ValueState.Idle -> {
                    Text(
                        "1. Tap eBay Sold above.\n2. Screenshot the results (scroll and take 2–3 for a better read).\n" +
                            "3. Come back and pick the screenshots.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "The price comes only from matching sold listings on your screenshots — no matches, no price.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onPickScreenshots, modifier = Modifier.fillMaxWidth()) { Text("Pick sold screenshots") }
                }
                is ValueState.Working -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                }
                is ValueState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onPickScreenshots, modifier = Modifier.fillMaxWidth()) { Text("Pick screenshots again") }
                }
                is ValueState.Done -> Result(state, currentQuery, onPickScreenshots, onToggle)
            }
        }
    }
}

@Composable
private fun Result(state: ValueState.Done, currentQuery: String, onPickScreenshots: () -> Unit, onToggle: (Int) -> Unit) {
    val estimate = SoldValue.estimate(state.comps)
    if (state.query.isNotBlank() && state.query != currentQuery.trim()) {
        Text("These solds are from the search \"${state.query}\".", style = MaterialTheme.typography.bodySmall)
    }
    if (estimate == null) {
        Text("No price: none of the ${state.comps.size} sold listings matched this item.", style = MaterialTheme.typography.titleSmall)
        Text(
            "Without matching sales there's nothing to back a price. Try a broader or different search, then screenshot again — " +
                "or tap a listing below if it really is the same item.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        Text(
            (if (estimate.upperLimitOnly) "At most about " else "Typical sold price: ") + usd(estimate.median),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        )
        val lines = buildList {
            val typicalLow = estimate.typicalLow
            val typicalHigh = estimate.typicalHigh
            if (typicalLow != null && typicalHigh != null) {
                add("Most sold between ${usd(typicalLow)} and ${usd(typicalHigh)}")
            }
            if (estimate.used > 1) add("Lowest ${usd(estimate.low)} · highest ${usd(estimate.high)}")
            add("Priced to sell fast: about ${usd(estimate.quickSale)}")
            add("Proof: ${estimate.strength} (${estimate.used} sale${if (estimate.used == 1) "" else "s"})")
        }
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text("Why:", style = MaterialTheme.typography.labelLarge)
        estimate.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }

    var showAll by rememberSaveable(state.comps.size) { mutableStateOf(estimate == null || state.comps.size <= 6) }
    TextButton(onClick = { showAll = !showAll }) {
        Text(if (showAll) "Hide the listings" else "See all ${state.comps.size} listings read")
    }
    if (showAll) {
        Text("Tap a listing to count it in or leave it out.", style = MaterialTheme.typography.bodySmall)
        state.comps.forEachIndexed { i, comp ->
            HorizontalDivider()
            CompRow(comp) { onToggle(i) }
        }
        HorizontalDivider()
    }
    OutlinedButton(onClick = onPickScreenshots, modifier = Modifier.fillMaxWidth()) { Text("Use other screenshots") }
}

@Composable
private fun CompRow(comp: SoldComp, onClick: () -> Unit) {
    val dim = if (comp.match) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        val shipping = comp.shipping
        val ship = when {
            shipping == null -> ""
            shipping == 0.0 -> " · free shipping"
            else -> " + ${usd(shipping)} shipping"
        }
        Text(
            (if (comp.match) "✓ Counted  " else "✗ Left out  ") + usd(comp.price) + ship +
                (if (comp.bestOffer) " · best offer (sold for less)" else ""),
            style = MaterialTheme.typography.labelLarge, color = dim,
        )
        Text(comp.title, style = MaterialTheme.typography.bodySmall, color = dim, maxLines = 2)
        listOfNotNull(comp.soldDate, comp.why).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
