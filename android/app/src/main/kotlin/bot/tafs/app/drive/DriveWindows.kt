package bot.tafs.app.drive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
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
  // A list that reaches its end with speed left over keeps that leftover: the sheet never gets it (KeepUpwardLeftovers).
  // Full width, as Material's own sheet column is, so a child that centres itself still centres in the whole sheet.
  Column(Modifier.fillMaxWidth().nestedScroll(remember { KeepUpwardLeftovers() })) { content() }
}

/**
 * What a sheet's list doesn't use when it scrolls up past its end stays with the list. Material's sheet otherwise takes that
 * leftover, and a fast fling's leftover speed settles the sheet, already open, with a spring that overshoots. On a tall sheet
 * the overshoot moves it under the status bar, which changes its insets, so its height and its open position, and it settles
 * again: the sheet bounced on its own after a fast swipe to the end of Account (Android 111), never after a slow one. A pull
 * downwards still reaches the sheet, so pulling a list down at its top closes the sheet as before.
 */
internal class KeepUpwardLeftovers : NestedScrollConnection {
  override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
    if (available.y < 0f) Offset(0f, available.y) else Offset.Zero

  override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
    if (available.y < 0f) Velocity(0f, available.y) else Velocity.Zero
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
