package bot.tafs.app.app

import android.app.Activity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Scans a computer's pairing QR with Google's code scanner (docs/android-app.md, Pairing a computer): its own screen, so the
 * app asks for no camera permission. The QR's text, or null when the person closed the scanner.
 */
suspend fun scanPairingQr(activity: Activity): String? = suspendCancellableCoroutine { done ->
  val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
  GmsBarcodeScanning.getClient(activity, options).startScan()
    .addOnSuccessListener { if (done.isActive) done.resume(it.rawValue) }
    .addOnCanceledListener { if (done.isActive) done.resume(null) }
    .addOnFailureListener { if (done.isActive) done.resumeWithException(it) }
}
