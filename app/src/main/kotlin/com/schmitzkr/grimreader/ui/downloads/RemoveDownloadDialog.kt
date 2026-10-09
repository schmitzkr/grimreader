package com.schmitzkr.grimreader.ui.downloads

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.schmitzkr.grimreader.ui.formatBytes

/** Confirms deleting a downloaded book from the device, shared by the book page and Downloads. */
@Composable
fun RemoveDownloadDialog(bytes: Long, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove download?") },
        text = { Text("This frees ${formatBytes(bytes)} on this device. You can download it again later.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
