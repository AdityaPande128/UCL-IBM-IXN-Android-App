package com.jarvis.companion

import com.journeyapps.barcodescanner.CaptureActivity

// The stock zxing scanner is landscape by manifest; this subclass exists so
// the manifest can pin the scanner upright like the rest of the app.
class PortraitCaptureActivity : CaptureActivity()
