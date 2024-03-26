package net.opendasharchive.openarchive.features.internetarchive.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter.Companion.tint
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.opendasharchive.openarchive.R
import net.opendasharchive.openarchive.core.presentation.theme.ThemeColors
import net.opendasharchive.openarchive.core.presentation.theme.ThemeDimensions

@Composable
fun InternetArchiveHeader(modifier: Modifier = Modifier, titleSize: TextUnit = 18.sp) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(ThemeDimensions.touchable)
                .background(
                    color = ThemeColors.material.surface,
                    shape = CircleShape
                )
                .clip(CircleShape)
        ) {
            Image(
                modifier = Modifier
                    .matchParentSize()
                    .padding(11.dp),
                painter = painterResource(id = R.drawable.ic_internet_archive),
                contentDescription = stringResource(
                    id = R.string.internet_archive
                ),
                colorFilter = tint(colorResource(id = R.color.colorPrimary))
            )
        }
        Column(modifier = Modifier.padding(start = ThemeDimensions.spacing.medium)) {
            Text(
                text = stringResource(id = R.string.internet_archive),
                fontSize = titleSize,
                fontWeight = FontWeight.Bold,
                color = ThemeColors.material.onSurface
            )
            Text(
                text = stringResource(id = R.string.internet_archive_description),
                color = ThemeColors.material.onSurfaceVariant
            )
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun InternetArchiveHeaderPreview() {
    InternetArchiveHeader()
}
