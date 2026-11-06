package bot.tafs.app.drive

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Every sheet, dialog and menu of the app opens through one of these (docs/drive.md): each is a window of its own, so each is
// guarded while Coach drives, its dismissal checked, and Coach's back closes it. DriveWindowsTest fails the build on a raw
// ModalBottomSheet, AlertDialog, Dialog, DatePickerDialog or DropdownMenu anywhere else.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveSheet(
  onDismissRequest: () -> Unit,
  sheetState: SheetState = rememberModalBottomSheetState(),
  containerColor: Color,
  modifier: Modifier = Modifier,
  sheetGesturesEnabled: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) = ModalBottomSheet(
  onDismissRequest = driveDismissal(onDismissRequest),
  modifier = modifier,
  sheetState = sheetState,
  sheetGesturesEnabled = sheetGesturesEnabled,
  containerColor = containerColor,
) {
  DriveWindow()
  content()
}

@Composable
fun DriveAlert(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  modifier: Modifier = Modifier,
  dismissButton: @Composable (() -> Unit)? = null,
  title: @Composable (() -> Unit)? = null,
  text: @Composable (() -> Unit)? = null,
  containerColor: Color = androidx.compose.material3.AlertDialogDefaults.containerColor,
  properties: DialogProperties = DialogProperties(),
) = AlertDialog(
  onDismissRequest = driveDismissal(onDismissRequest),
  confirmButton = {
    DriveWindow()
    confirmButton()
  },
  modifier = modifier,
  dismissButton = dismissButton,
  title = title,
  text = text,
  containerColor = containerColor,
  properties = properties,
)

@Composable
fun DriveDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) =
  Dialog(onDismissRequest = driveDismissal(onDismissRequest), properties = properties) {
    DriveWindow()
    content()
  }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveDatePicker(
  onDismissRequest: () -> Unit,
  confirmButton: @Composable () -> Unit,
  dismissButton: @Composable (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) = DatePickerDialog(onDismissRequest = driveDismissal(onDismissRequest), confirmButton = confirmButton, dismissButton = dismissButton) {
  DriveWindow()
  content()
}

/** A menu's popup has no `Window`: its own touches are guarded on their way in, and Coach's back folds it. */
@Composable
fun DriveMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
  containerColor: Color = MenuDefaults.containerColor,
  content: @Composable ColumnScope.() -> Unit,
) = DropdownMenu(
  expanded = expanded,
  onDismissRequest = driveMenuDismissal(onDismissRequest),
  modifier = modifier.driveGuard(),
  containerColor = containerColor,
) {
  DriveWindow(dismiss = onDismissRequest)
  content()
}
