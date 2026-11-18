package com.knap43.rocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

val PanelShape = RoundedCornerShape(18.dp)

/** The rounded, slightly lifted block every group of rows sits on. */
@Composable
fun Modifier.panel(): Modifier = this
    .fillMaxWidth()
    .padding(horizontal = 12.dp, vertical = 4.dp)
    .clip(PanelShape)
    .background(MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
    )
}
