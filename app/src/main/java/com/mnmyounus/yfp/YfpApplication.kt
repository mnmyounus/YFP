package com.mnmyounus.yfp

import android.app.Application

/**
 * Deliberately minimal. There is no analytics SDK, crash reporter, or
 * remote-config client to initialize here — per the "Zero Ads & Zero
 * Tracking: No third-party SDKs, analytics, or telemetry" requirement, this
 * class exists only because AndroidManifest references a named Application
 * class for clarity/future-proofing, not because it does anything today.
 */
class YfpApplication : Application()
