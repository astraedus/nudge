# QR / barcode scanning and generation (`ui/qr/`)

A reusable component, built first for Nuke Mode (scan a code to turn it on or off), with no Nuke
knowledge in it. It does three things: scan a code with the camera, generate a printable QR image,
and hand that image to the share sheet.

## The pinned API

Other features code against exactly this; do not rename or reshape it.

```kotlin
// Returns the decoded raw text, or null on cancel / camera denied / no camera.
class ScanQrContract : ActivityResultContract<ScanQrContract.Request, String?>() {
    data class Request(val title: String, val subtitle: String? = null)
}
object QrCodeGenerator {
    fun generate(content: String, sizePx: Int = 1024): android.graphics.Bitmap
}
object QrShare {
    fun share(context: Context, bitmap: Bitmap, fileName: String, chooserTitle: String)
}
```

```kotlin
val scan = rememberLauncherForActivityResult(ScanQrContract()) { text: String? -> /* ... */ }
scan.launch(ScanQrContract.Request(title = "Scan your code", subtitle = "The one you printed"))
```

**What a caller can rely on:**

- **The same physical code gives the same string, every time.** The format set and decode hints are
  fixed, so a code registered through the contract and scanned again later through it compares equal
  as a plain string. (A UPC-A reads as its 12 digits, not as a 13-digit EAN with a leading zero.)
- **At most one result per launch.** The scanner finishes on the first CONFIRMED read.
- **A 1D barcode is only returned after two agreeing reads** (`ScanConfirmer`). A QR code carries
  Reed-Solomon error correction, so a read is right or absent; a 1D barcode has at best one check
  digit, and a skewed or glare-split partial read can decode as a different valid number. Registering
  that wrong number would leave the user holding a bottle that never matches. The cost is ~100 ms of
  steady hand.
- **Scannable symbologies** (`BarcodeFrameDecoder.SUPPORTED_FORMATS`): QR, Data Matrix, EAN-13, EAN-8,
  UPC-A, UPC-E, Code 128, Code 39, Code 93. ITF and Codabar are deliberately excluded: no mandatory
  check, so partial reads produce plausible wrong numbers. `BarcodeFrameDecoderTest` pins that as a
  class invariant.
- **`generate`** is error correction Q (above the M floor; a printed code gets creased), a 4-module
  quiet zone (the QR spec's), pure black on pure white. The bitmap is `sizePx` square, or larger if
  `sizePx` is below one pixel per module.
- **`share`** writes the PNG to `cacheDir/qr/<safe name>.png` and opens the chooser. The name is
  reduced to one safe path segment (`QrShare.safeFileName`). A failure is a toast, never a crash.

## Library choice: CameraX + zxing core (FOSS only)

Nudge ships on F-Droid and IzzyOnDroid, so ML Kit and the Google Play services code scanner are out.

| | Chosen: CameraX + `com.google.zxing:core` | Rejected: `zxing-android-embedded` |
|---|---|---|
| Licence | Apache-2.0 (AOSP Jetpack; no Play services) | Apache-2.0 |
| Camera API | Camera2, lifecycle-bound | deprecated Camera1 |
| UI | our Compose screen over `PreviewView` | its own View-based capture activity, landscape by default |
| Decode path | OUR code over the Y plane, so the exact on-device decode is JVM-tested | inside the library, untestable here |
| Status | actively maintained | maintenance mode |

CameraX is pinned to 1.5.3 because it is built against kotlin-stdlib 2.0.21, this project's Kotlin;
1.6.x pulls stdlib 2.1.20 and waits for the Kotlin bump. CameraX adds no permission to the merged
manifest (checked); it contributes one disabled metadata service.

## How a frame is decoded

`QrFrameAnalyzer` (thin CameraX adapter) copies the Y plane, `LuminanceFrames.pack` strips the row
padding (a 1280-wide frame often has a 1344 stride; unpacked, the image shears into noise), and
`BarcodeFrameDecoder` runs zxing with `TRY_HARDER` over the whole frame, not just the viewfinder.

**The rotation fallback is load-bearing.** zxing's 1D readers scan rows. A sensor is usually mounted
landscape, so a barcode held level in the portrait viewfinder arrives as vertical bars no row crosses,
and zxing cannot rotate a `PlanarYUVLuminanceSource` itself. So the decoder tries the orientation
CameraX's `rotationDegrees` says is upright first, then the other one. The counterfactual test
`without the rotation fallback a sideways barcode is NOT readable` keeps that honest.

Analysis runs at ~1280x720 with `STRATEGY_KEEP_ONLY_LATEST` on one background thread; nothing is
photographed or stored.

## Permission flow

`CAMERA` is declared, and `android.hardware.camera.any` is declared `required="false"` so the Play
listing does not drop camera-less devices; on such a device `ScanQrContract.getSynchronousResult`
answers null without opening anything. The runtime request happens only when the scanner opens. The
decisions are the pure `CameraPermissionFlow` (JVM-tested):

| On open | Screen |
|---|---|
| granted | scanning |
| not granted, rationale available (said no before) | explanation + "Allow camera" |
| not granted, no rationale | system dialog, with the rationale line behind it |

| After the dialog | Result |
|---|---|
| granted | scanning |
| denied, rationale still available | ordinary no: return null |
| denied, no rationale | the system will not ask again (or it answered instantly because the camera was already off for good): "Camera access is off" + "Open settings", instead of the screen blinking shut |

Coming back from Settings with the permission granted starts scanning with no further tap.

The MIRROR block mode reuses this CAMERA permission and `CameraPermissionFlow.afterRequest` for its
editor prompt (`ui/components/rule/MirrorCameraAccess.kt`); it binds only a `Preview` use case and
adds no permission. See "MIRROR" in `block-overlay-lifecycle.md`.

## Manifest and build gates

- `QrScanActivity` is registered, `exported="false"`, portrait (Android 16 ignores the lock on
  large screens; the layout is size-driven, so that is fine), on the dark framework theme so the
  window does not flash white before the preview. `QrManifestContractTest` pins the permission, the
  optional feature and the non-exported activity.
- **The merged manifest's permissions are an allowlist**, enforced by
  `verify<Variant>MergedPermissions`, which every `assemble<Variant>` / `bundle<Variant>` depends on
  (`app/build.gradle.kts`). The manifest we write is not the manifest that ships: libraries add
  `<uses-permission>` at merge time, silently, which is how a dependency bump could give a
  "no INTERNET" app the INTERNET permission. Intended additions go in `allowedMergedPermissions` in
  the same diff; a library's unwanted one is stripped with `tools:node="remove"`.
- Sharing reuses the ONE FileProvider (`${applicationId}.fileprovider`), whose `cache-path` already
  covers the whole cache dir. Do not add a second provider.

## What is not JVM-testable here

The activity itself (CameraX binding, torch, the permission launcher, Compose drawing) and the
Bitmap construction in `generate` (an android constructor is a no-op stub on the JVM). Everything
they call into is pure and tested: `LuminanceFrames`, `BarcodeFrameDecoder`, `ScanConfirmer`,
`CameraPermissionFlow`, `QrCodeGenerator.encode/toPixels`, `QrShare.safeFileName`,
`ScanQrContract.parseResult`. The device half is L6: scan a printed QR, an EAN-13 on a product held
level and upright, toggle the torch, deny twice then reach Settings, cancel with back.
