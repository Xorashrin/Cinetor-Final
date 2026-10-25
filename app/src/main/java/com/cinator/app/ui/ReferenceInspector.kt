package com.cinator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinator.app.ui.theme.CinatorCard
import com.cinator.app.ui.theme.CinatorMetadataBlue
import com.cinator.app.ui.theme.CinatorMutedText
import com.cinator.app.ui.theme.CinatorWarningYellow

@Composable
fun ReferenceInspector(snapshot: ReferenceSnapshot?, error: String?) {
    var query by remember { mutableStateOf("") }
    var page by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().background(CinatorCard, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Embedded directory references", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("Metadata only • not installed • runtime disabled. No endpoints or plugin code are fetched by this inspector.",
            color = CinatorMetadataBlue, fontSize = 12.sp)
        if (snapshot == null) {
            Text("Reference asset unavailable: ${error ?: "could not load embedded snapshot"}", color = CinatorWarningYellow)
        } else {
            Text("${snapshot.entries.size} unique repositories • ${snapshot.descriptorCount} subplugin descriptors\n" +
                snapshot.ecosystemCounts.entries.joinToString(" • ") { "${it.key}: ${it.value}" },
                color = CinatorMutedText, fontSize = 12.sp)
            Text("Captured: ${snapshot.capturedAt}\n${snapshot.coverage}", color = CinatorMutedText, fontSize = 11.sp)
            SectionSearchBar(query, "Search names, ecosystem, endpoints or descriptors", {
                query = it; page = 0; expanded = null
            })
            val matches = snapshot.search(query)
            val pageSize = 10
            val pageCount = ((matches.size + pageSize - 1) / pageSize).coerceAtLeast(1)
            Text("${matches.size} matches • Page ${page + 1} / $pageCount", color = Color.White, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { page--; expanded = null }, enabled = page > 0) { Text("Previous") }
                OutlinedButton(onClick = { page++; expanded = null }, enabled = page + 1 < pageCount) { Text("Next") }
            }
            snapshot.page(query, page, pageSize).forEach { entry ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${entry.name} • ${entry.ecosystem}", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("REFERENCE ONLY • ${entry.descriptors.size} descriptors", color = CinatorMetadataBlue, fontSize = 11.sp)
                    OutlinedButton(onClick = { expanded = if (expanded == entry.id) null else entry.id }) {
                        Text(if (expanded == entry.id) "Hide metadata" else "Inspect metadata")
                    }
                    if (expanded == entry.id) SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ID: ${entry.id}", color = CinatorMutedText, fontSize = 11.sp)
                            Text(entry.endpoint ?: "No resolved endpoint in snapshot", color = Color.White, fontSize = 12.sp)
                            Text(entry.note, color = CinatorMutedText, fontSize = 12.sp)
                            entry.descriptors.forEach { Text(it, color = CinatorMutedText, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }
    }
}
