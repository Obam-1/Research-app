package com.resellcomps.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resellcomps.app.ValueState
import com.resellcomps.core.ItemIdentification
import com.resellcomps.core.SoldSearchLinks
import com.resellcomps.core.TagGuide

/** Ready-made questions for Google Lens, picked with one tap. */
/** What the Value card asks Lens; Lens answers with an AI overview of what it's worth. */
private const val LENS_VALUE_QUESTION = "How much is this worth? Recent eBay sold prices"

private val LENS_QUESTIONS = listOf(
    LENS_VALUE_QUESTION,
    "eBay active and sold listings links for this",
    "What year is this tag?",
    "What brand is this?",
    "Is this vintage?",
    "What is this called?",
)

private fun open(context: Context, url: String) =
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

/**
 * The quick screen: the search the app wrote for you, and one-tap buttons that
 * open eBay's active and sold results for it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IdentifiedScreen(
    item: ItemIdentification,
    photos: List<ByteArray>,
    modifier: Modifier,
    /** Progress of the tag check running behind this screen, or null when it's done. */
    tagCheck: String? = null,
    valueState: ValueState = ValueState.Idle,
    /** Screenshots of eBay's sold results the user picked, with the search on screen. */
    onSoldScreenshots: (List<Uri>, String) -> Unit = { _, _ -> },
    onToggleComp: (Int) -> Unit = {},
    onRetake: () -> Unit,
    onAddPhoto: () -> Unit,
    onOpenSettings: () -> Unit,
    onDigDeeper: ((ItemIdentification, String) -> Unit)?,
    lensQuestion: String,
    onLensQuestionChange: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable(item) { mutableStateOf(item.searchQuery) }
    var showDetails by rememberSaveable(item) { mutableStateOf(false) }
    val q = query.trim()
    // Opened from "Search by text": no photo, just type and go.
    val textMode = photos.isEmpty()
    val focus = remember { FocusRequester() }
    LaunchedEffect(textMode) { if (textMode) focus.requestFocus() }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        photos.firstOrNull()?.let { PhotoPreview(it) }

        item.warning?.let { warning ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(warning, style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
                }
            }
        }

        tagCheck?.let { status ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (!textMode && item.vintage != null) {
            VintageCard(item, onDigDeeper?.takeIf { tagCheck == null }?.let { dig -> { dig(item, q) } })
        }

        if (!textMode && (!item.identified || q.isEmpty())) {
            Text(
                "Couldn't read enough from the photo. Add a close-up of the tag or label, or type the search below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(if (textMode) "Type what it is, e.g. vintage hanes beefy" else "Search") }, singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )

        // Other searches the AI suggests (with "vintage", more specific, broader). Tap to use one.
        val options = (listOf(item.searchQuery) + item.altQueries).filter { it.isNotBlank() }.distinct()
        if (options.size > 1) {
            Text("Other searches — tap to use", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    FilterChip(
                        selected = q == option,
                        onClick = { query = option },
                        label = { Text(option) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { open(context, SoldSearchLinks.ebayActive(q)) },
                enabled = q.isNotEmpty(),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text("eBay Active", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            Button(
                onClick = { open(context, SoldSearchLinks.ebaySold(q)) },
                enabled = q.isNotEmpty(),
                modifier = Modifier.weight(1f).height(64.dp),
            ) { Text("eBay Sold", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        }

        // Terapeak on its own row (eBay's official sold research), then the other marketplaces.
        val others = SoldSearchLinks.forQuery(q).drop(1)
        listOf(others.take(1), others.drop(1)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (name, url) ->
                    OutlinedButton(onClick = { open(context, url) }, enabled = q.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Text(
                            if (name == "Terapeak") "Terapeak sold research (eBay)" else name,
                            style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        )
                    }
                }
            }
        }

        val pickSoldScreenshots = rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(5)
        ) { uris -> if (uris.isNotEmpty()) onSoldScreenshots(uris, q) }
        ValueCard(
            state = valueState,
            currentQuery = q,
            onPickScreenshots = {
                pickSoldScreenshots.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onToggle = onToggleComp,
            onAskLens = photos.firstOrNull()?.let { photo -> { GoogleLens.open(context, photo, LENS_VALUE_QUESTION) } },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onAddPhoto, modifier = Modifier.weight(1f)) { Text(if (textMode) "Use a photo" else "Add tag photo") }
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("New item") }
        }


        if (!textMode) {

            // Lens can't be handed text by another app, so the question is copied to paste into
            // its "Add to your search" box. Empty = the search words above.
            var lensText by rememberSaveable { mutableStateOf(lensQuestion) }
            val lensSends = lensText.trim().ifEmpty { q }
            // eBay image search: only when the eBay app accepts a shared photo.
            val ebayImage = remember { EbayImageSearch.isAvailable(context) }
            if (ebayImage) {
                Text("eBay image search — tap a photo", style = MaterialTheme.typography.titleSmall)
                PhotoPicker(photos, "Search photo %d on eBay") { EbayImageSearch.open(context, it) }
            }

            Text("Search with Google Lens", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("" to "My search") + LENS_QUESTIONS.map { it to it }).forEach { (value, label) ->
                    FilterChip(
                        selected = lensText.trim() == value,
                        onClick = { lensText = value; onLensQuestionChange(value) },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = lensText,
                onValueChange = { lensText = it; onLensQuestionChange(it) },
                label = { Text("Question sent with the photo") },
                placeholder = { Text(q.ifEmpty { "e.g. what year is this tag" }) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Tap a photo: Lens opens with it and your question is copied — tap \"Add to your search\" in Lens and paste.",
                style = MaterialTheme.typography.bodySmall,
            )
            PhotoPicker(photos, "Search photo %d with Google Lens") { GoogleLens.open(context, it, lensSends) }

            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "Hide what the app saw" else "What the app saw")
            }
            if (showDetails) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium)
                        listOfNotNull(
                            item.brand?.let { "Brand: $it" },
                            item.model?.let { "Model: $it" },
                            item.category.takeIf { it.isNotBlank() }?.let { "Category: $it" },
                        ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (item.details.isNotBlank()) Text(item.details, style = MaterialTheme.typography.bodySmall)
                        Text("Confidence: ${item.confidence}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/**
 * The AI's vintage call, up front: yes (with era and clues), no, or unsure. "Dig deeper" runs a
 * second, tag-focused look that weighs evidence for an older vs newer date.
 */
@Composable
private fun VintageCard(item: ItemIdentification, onDigDeeper: (() -> Unit)?) {
    var showWhy by rememberSaveable(item) { mutableStateOf(item.vintageReasoning != null) }
    val (title, color) = when (item.vintage) {
        "yes" -> ("Vintage: Yes" + (item.era?.let { ", $it" } ?: "")) to MaterialTheme.colorScheme.primaryContainer
        "no" -> ("Vintage: No" + (item.era?.let { " ($it)" } ?: "")) to MaterialTheme.colorScheme.surfaceVariant
        else -> "Vintage: Unsure" to MaterialTheme.colorScheme.tertiaryContainer
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            item.vintageClues?.let { Text("Based on: $it", style = MaterialTheme.typography.bodyMedium) }
            if (item.vintage == "unsure") {
                Text("Add a clear photo of the tag (and the hem stitching) to settle it.", style = MaterialTheme.typography.bodySmall)
            }
            if (item.tagReading != null || item.vintageReasoning != null) {
                TextButton(onClick = { showWhy = !showWhy }) { Text(if (showWhy) "Hide details" else "Why?") }
                if (showWhy) {
                    item.tagReading?.let { Text("On the tag: $it", style = MaterialTheme.typography.bodySmall) }
                    item.vintageReasoning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            // Learn as you scan: the built-in tag history for the brand(s) on this item.
            TagGuide.matching(item).forEach { brand ->
                var showBrand by rememberSaveable(item, brand.name) { mutableStateOf(false) }
                TextButton(onClick = { showBrand = !showBrand }) {
                    Text(if (showBrand) "Hide ${brand.name} tag history" else "About ${brand.name} tags")
                }
                if (showBrand) BrandNotes(brand)
            }
            if (onDigDeeper != null) {
                OutlinedButton(onClick = onDigDeeper, modifier = Modifier.fillMaxWidth()) {
                    Text(if (item.vintageReasoning == null) "Dig deeper on the tag" else "Dig deeper again")
                }
            }
        }
    }
}

/** A row of the scan's photos; tapping one hands it to [onPick]. */
@Composable
private fun PhotoPicker(photos: List<ByteArray>, description: String, onPick: (ByteArray) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(photos) { i, photo ->
            val bitmap = remember(photo) { BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap() }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = description.format(i + 1),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(84.dp).clip(MaterialTheme.shapes.medium).clickable { onPick(photo) },
                )
            }
        }
    }
}
