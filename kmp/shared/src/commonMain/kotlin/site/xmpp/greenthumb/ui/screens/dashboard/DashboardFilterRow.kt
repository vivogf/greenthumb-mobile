package site.xmpp.greenthumb.ui.screens.dashboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.ui.components.GtChip
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.dashboard_all
import site.xmpp.greenthumb.ui.res.dashboard_healthy
import site.xmpp.greenthumb.ui.res.dashboard_waterFilter
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Filter chips «Все · Полить (N) · В порядке». The short labels fit one line at 384 dp; at a very large
 * font the row scrolls instead of wrapping (the 20 dp side padding scrolls with the chips).
 */
@Composable
internal fun DashboardFilterRow(
    filter: DashboardFilter,
    dueCount: Int,
    onSelect: (DashboardFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        GtChip(
            label = stringResource(Res.string.dashboard_all),
            selected = filter == DashboardFilter.All,
            onClick = { onSelect(DashboardFilter.All) },
        )
        GtChip(
            label = waterChipLabel(stringResource(Res.string.dashboard_waterFilter), dueCount),
            selected = filter == DashboardFilter.NeedsWater,
            onClick = { onSelect(DashboardFilter.NeedsWater) },
        )
        GtChip(
            label = stringResource(Res.string.dashboard_healthy),
            selected = filter == DashboardFilter.Healthy,
            onClick = { onSelect(DashboardFilter.Healthy) },
        )
    }
}

/** RN `{label} (${count})` when count > 0. */
private fun waterChipLabel(baseLabel: String, dueCount: Int): String =
    if (dueCount > 0) "$baseLabel ($dueCount)" else baseLabel
