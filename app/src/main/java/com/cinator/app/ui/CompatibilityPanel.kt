package com.cinator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinator.app.compat.CompatibilityEntry
import com.cinator.app.compat.CompatibilityMatrix
import com.cinator.app.compat.CompatibilityStatus
import com.cinator.app.ui.theme.CinatorBorder
import com.cinator.app.ui.theme.CinatorCard
import com.cinator.app.ui.theme.CinatorMetadataBlue
import com.cinator.app.ui.theme.CinatorMutedText
import com.cinator.app.ui.theme.CinatorWarningYellow
import java.util.Locale

private const val BLOCKER_PAGE_SIZE = 8
private val blockerStatuses = setOf(
    CompatibilityStatus.BLOCKED_SECURITY,
    CompatibilityStatus.BLOCKED_RIGHTS_OR_API,
    CompatibilityStatus.REQUIRES_USER_SERVER
)

@Composable
fun CompatibilityPanel(
    matrix: CompatibilityMatrix?,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    var blockerQuery by remember { mutableStateOf("") }
    var blockerPage by remember { mutableIntStateOf(0) }
    val entries = matrix?.entries.orEmpty()
    val query = blockerQuery.trim()
    val matchingBlockers = entries.filter { entry ->
        entry.status in blockerStatuses && (query.isEmpty() || listOf(
            entry.id, entry.name, entry.ecosystem, entry.status.name, entry.reason,
            entry.evidenceUrl.orEmpty(), entry.version.orEmpty()
        ).any { it.contains(query, ignoreCase = true) })
    }
    val pageCount = ((matchingBlockers.size + BLOCKER_PAGE_SIZE - 1) / BLOCKER_PAGE_SIZE).coerceAtLeast(1)
    val safePage = blockerPage.coerceIn(0, pageCount - 1)
    val pageRows = matchingBlockers.drop(safePage * BLOCKER_PAGE_SIZE).take(BLOCKER_PAGE_SIZE)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CinatorCard)
            .border(1.dp, CinatorBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            "Engine compatibility: Phase1 incomplete",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Text(
            "This bundled matrix reports declared implementation coverage, not engines currently running on this device. Policies are validation rules, not running engines; status counts are not device playback results.",
            color = CinatorMutedText,
            fontSize = 11.sp
        )
        Text(
            "The 79 reference items are not a full provider inventory. Native JavaScript and DEX execution are disabled; a disabled or blocked status does not mean code was executed.",
            color = CinatorMutedText,
            fontSize = 11.sp
        )

        if (errorMessage != null || matrix == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Compatibility matrix unavailable: ${errorMessage ?: "not supplied"}",
                color = CinatorWarningYellow,
                fontSize = 12.sp
            )
        }
        if (matrix == null) return@Column

        Spacer(Modifier.height(10.dp))
        Text("Status counts (${entries.size} matrix entries)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        CompatibilityStatus.values().forEach { status ->
            val count = matrix.entries.count { it.status == status }
            Text("${status.displayLabel()}: $count", color = CinatorMetadataBlue, fontSize = 11.sp)
        }

        Spacer(Modifier.height(10.dp))
        Text("Unresolved blockers", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        SectionSearchBar(
            query = blockerQuery,
            placeholder = "Search blocked engine, ecosystem, reason, or URL",
            onQueryChange = {
                blockerQuery = it
                blockerPage = 0
            }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${matchingBlockers.size} matching blocked or user-server-required entries • page ${safePage + 1} of $pageCount",
            color = CinatorMutedText,
            fontSize = 11.sp
        )
        if (pageRows.isEmpty()) {
            Text("No unresolved blockers match this search.", color = CinatorMutedText, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            pageRows.forEach { CompatibilityBlockerRow(it) }
        }
        Row(Modifier.fillMaxWidth()) {
            Button(
                onClick = { blockerPage = (safePage - 1).coerceAtLeast(0) },
                enabled = safePage > 0,
                colors = ButtonDefaults.buttonColors(containerColor = CinatorMetadataBlue),
                modifier = Modifier.weight(1f)
            ) { Text("Previous", fontSize = 11.sp) }
            Spacer(Modifier.padding(horizontal = 4.dp))
            Button(
                onClick = { blockerPage = (safePage + 1).coerceAtMost(pageCount - 1) },
                enabled = safePage + 1 < pageCount,
                colors = ButtonDefaults.buttonColors(containerColor = CinatorMetadataBlue),
                modifier = Modifier.weight(1f)
            ) { Text("Next", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun CompatibilityBlockerRow(entry: CompatibilityEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(entry.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "${entry.ecosystem} • ${entry.status.displayLabel()}${entry.version?.let { " • version $it" }.orEmpty()}",
            color = CinatorWarningYellow,
            fontSize = 11.sp
        )
        Text(entry.reason, color = CinatorMutedText, fontSize = 11.sp)
        Text(
            "Declared capabilities: ${entry.capability.enabledLabels().ifEmpty { listOf("none") }.joinToString()}",
            color = CinatorMutedText,
            fontSize = 10.sp
        )
        entry.evidenceUrl?.let {
            Text("Evidence URL (not fetched): $it", color = CinatorMetadataBlue, fontSize = 10.sp)
        }
    }
}

private fun CompatibilityStatus.displayLabel(): String =
    name.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

private fun com.cinator.app.compat.EngineCapability.enabledLabels(): List<String> = buildList {
    if (catalog) add("catalog")
    if (search) add("search")
    if (details) add("details")
    if (episodes) add("episodes")
    if (directPlayback) add("direct playback")
    if (download) add("download")
    if (externalPlayback) add("external playback")
}
