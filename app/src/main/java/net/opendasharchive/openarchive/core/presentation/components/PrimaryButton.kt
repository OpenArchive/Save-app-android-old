package net.opendasharchive.openarchive.core.presentation.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable

@Composable
fun PrimaryButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) =
    Button(onClick = onClick, content = content)
