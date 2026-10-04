package com.mrj.fancyai.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mrj.fancyai.R
import com.mrj.fancyai.ui.theme.AccentSoft
import com.mrj.fancyai.ui.theme.Ink

@Composable
internal fun AppsPage(
    category: HomeCategory,
    onCategory: (HomeCategory) -> Unit,
    onOpen: (HomeDestination) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Ink.copy(alpha = 0.58f),
                    0.52f to Ink.copy(alpha = 0.76f),
                    1f to Ink.copy(alpha = 0.94f),
                ),
            ),
    ) {
        Spacer(Modifier.height(184.dp))
        Text(
            stringResource(R.string.home_fancy_os),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp),
        )
        Text(
            stringResource(R.string.home_apps),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = AccentSoft,
            modifier = Modifier.padding(start = 24.dp, top = 10.dp),
        )
        CategoryBar(selected = category, onSelect = onCategory)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = 16.dp, top = 38.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(HomeApps.filter { it.category == category }, key = HomeApp::id) { app ->
                AppTile(
                    app = app,
                    onClick = { onOpen(app.destination) },
                )
            }
        }
    }
}
