package com.zamateka.smartfollow.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zamateka.smartfollow.R
import com.zamateka.smartfollow.model.GlucoseReading
import com.zamateka.smartfollow.model.arrow

/** Glucose value and trend arrow on one line, the unit underneath, so that it never wraps. */
@Composable
fun GlucoseValue(
    reading: GlucoseReading?,
    color: Color,
    modifier: Modifier = Modifier,
    valueSize: TextUnit = 88.sp,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = reading?.mgDl?.toString() ?: "—",
                fontSize = valueSize,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
            )
            val arrow = reading?.trend?.arrow().orEmpty()
            if (arrow.isNotEmpty()) {
                Text(
                    text = arrow,
                    fontSize = valueSize * 0.8f,
                    color = color,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        Text(
            stringResource(R.string.unit_mg_dl),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
