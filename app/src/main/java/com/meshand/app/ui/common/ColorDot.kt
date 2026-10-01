package com.meshand.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.meshand.app.domain.NodeColors
import com.meshand.app.domain.model.MeshNode

/** The person's map colour, so the list matches what OsmAnd shows. */
@Composable
fun ColorDot(node: MeshNode, modifier: Modifier = Modifier) {
    Box(modifier.size(14.dp).background(Color(NodeColors.colorFor(node)), CircleShape))
}
