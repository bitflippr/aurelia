package com.aurelia.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aurelia.app.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistPickerBottomSheet(
  artists: List<Screen.ArtistDetail>,
  onDismiss: () -> Unit,
  onSelect: (Screen.ArtistDetail) -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Text(
      "Choose artist",
      style = MaterialTheme.typography.titleLarge,
      modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
    )
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
      items(artists, key = { it.artistId }) { artist ->
        Row(
          Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { onSelect(artist) })
            .heightIn(min = 64.dp)
            .padding(horizontal = 24.dp, vertical = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Icon(Icons.Filled.Person, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          Text(artist.artistName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
          Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}
