package com.cinator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Background = Color(0xFF09090B)
private val Card = Color(0xFF18181B)
private val Accent = Color(0xFFE50914)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CinatorApp() }
    }
}

@Composable
fun CinatorApp() {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = listOf("Home", "Anime", "K-Drama", "More")
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
            Scaffold(
                containerColor = Background,
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF111113), modifier = Modifier.navigationBarsPadding()) {
                        tabs.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = selected == index,
                                onClick = { selected = index },
                                icon = { Icon(if (index == 0) Icons.Default.Home else if (index == 3) Icons.Default.Settings else Icons.Default.Search, label) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            ) { padding ->
                when (selected) {
                    0 -> HomeFeed(padding)
                    1 -> CategoryFeed("Anime", listOf("Trending Anime", "Airing Now", "Popular Series"), padding)
                    2 -> CategoryFeed("K-Drama", listOf("Ongoing Dramas", "Popular Korean Series", "Asian Picks"), padding)
                    else -> MoreFeed(padding)
                }
            }
        }
    }
}

@Composable
private fun HomeFeed(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        Header("Cinator")
        HeroBanner()
        Section("Continue Watching", listOf("The Last Horizon", "Midnight City", "Blue Orbit"))
        Section("Trending Movies", listOf("Neon Run", "Northbound", "Afterlight"))
        Section("Popular Series", listOf("The Archive", "Signal Room", "Parallel"))
    }
}

@Composable
private fun CategoryFeed(title: String, sections: List<String>, padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
        Header(title)
        sections.forEach { Section(it, listOf("Featured One", "Featured Two", "Featured Three")) }
    }
}

@Composable
private fun MoreFeed(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Header("More")
        MoreCard("Live TV", "Public and authorized live channels", Icons.Default.LiveTv)
        MoreCard("Adult Content", "Separate opt-in section", Icons.Default.Settings)
        MoreCard("Downloads", "Your offline library", Icons.Default.Settings)
        MoreCard("Settings", "Playback and provider settings", Icons.Default.Settings)
    }
}

@Composable
private fun Header(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {}) { Icon(Icons.Default.Search, "Search", tint = Color.White) }
    }
}

@Composable
private fun HeroBanner() {
    Box(Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF4C1D95), Color(0xFF111827), Color(0xFF09090B))))) {
        Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
            Text("CINATOR ORIGINAL", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("Discover your next story", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Movies and series, organized your way", color = Color.LightGray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Section(title: String, items: List<String>) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                Column(Modifier.size(width = 135.dp, height = 175.dp)) {
                    Box(Modifier.fillMaxWidth().height(135.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF27272A), Card))))
                    Text(item, color = Color.White, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun MoreCard(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, title, tint = Accent, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(subtitle, color = Color.Gray, fontSize = 13.sp)
        }
    }
}
