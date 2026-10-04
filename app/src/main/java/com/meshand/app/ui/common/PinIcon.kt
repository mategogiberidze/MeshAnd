package com.meshand.app.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.meshand.app.R
import com.meshand.app.domain.NodeColors
import com.meshand.app.domain.model.MeshNode
import com.meshand.app.domain.model.Pin

/** A map pin in the sender's colour, matching the pin on the OsmAnd map. */
@Composable
fun PinIcon(pin: Pin, sender: MeshNode?) {
    Icon(
        painter = painterResource(R.drawable.ic_pin),
        contentDescription = "Pin",
        tint = Color(sender?.let(NodeColors::colorFor) ?: NodeColors.colorForId(pin.fromNodeId ?: 0)),
        modifier = Modifier.size(24.dp),
    )
}
