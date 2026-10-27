package site.xmpp.greenthumb.ui.screens.species

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.catalog.SpeciesCatalogStore
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.catalog.SpeciesLight
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtModalHeader
import site.xmpp.greenthumb.ui.components.GtModalHeaderAction
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.species_choose
import site.xmpp.greenthumb.ui.res.species_lightBrightIndirect
import site.xmpp.greenthumb.ui.res.species_lightDirect
import site.xmpp.greenthumb.ui.res.species_lightLow
import site.xmpp.greenthumb.ui.res.species_lightMedium
import site.xmpp.greenthumb.ui.res.species_noResults
import site.xmpp.greenthumb.ui.res.species_searchHint
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * The bundled catalog, loaded once per process. Null while it loads or if
 * the resource cannot be read; screens then behave as without a catalog.
 */
@Composable
public fun rememberSpeciesCatalog(): CatalogRepository? {
    val catalog by produceState<CatalogRepository?>(initialValue = null) {
        value = try {
            SpeciesCatalogStore.repository()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
    }
    return catalog
}

@Composable
public fun speciesLightLabel(light: SpeciesLight): String = stringResource(
    when (light) {
        SpeciesLight.Low -> Res.string.species_lightLow
        SpeciesLight.Medium -> Res.string.species_lightMedium
        SpeciesLight.BrightIndirect -> Res.string.species_lightBrightIndirect
        SpeciesLight.Direct -> Res.string.species_lightDirect
    },
)

/**
 * Search dialog over the offline catalog. An empty query lists every species
 * by name in [language]; a typed query uses [CatalogRepository.search].
 */
@Composable
public fun SpeciesPickerDialog(
    catalog: CatalogRepository?,
    language: String,
    onPick: (SpeciesEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val allSorted = remember(catalog, language) { catalog?.sortedByName(language).orEmpty() }
    val results = remember(catalog, query, allSorted) {
        if (query.isBlank()) allSorted else catalog?.search(query).orEmpty()
    }
    GtModal(onDismissRequest = onDismiss) {
        GtModalHeader(
            title = stringResource(Res.string.species_choose),
            trailing = GtModalHeaderAction(stringResource(Res.string.common_cancel), scheme.onSurfaceVariant, onDismiss),
        )
        GtInputField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(Res.string.species_searchHint),
            modifier = Modifier.padding(horizontal = Spacing.xl),
        )
        if (catalog != null && results.isEmpty()) {
            Text(
                text = stringResource(Res.string.species_noResults),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(Spacing.xl),
            )
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = Spacing.xxl * 10)
                .padding(vertical = Spacing.sm),
        ) {
            items(results, key = { it.id }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { onPick(entry) }
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    Text(
                        text = entry.names.forLanguage(language),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = entry.scientificName,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
