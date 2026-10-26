# ADR-0008 — User-initiated device city lookup

Date: 2026-10-26. Status: implemented; physical-device availability pending.

The settings city field retains manual entry and gains an explicit foreground lookup action. Request only ACCESS_COARSE_LOCATION. Use Android LocationManager through the existing AndroidX Core compatibility API, preferring network location; accept a cached fix only if its monotonic age is at most ten minutes. New fix requests are bounded to four seconds per provider and ten seconds overall and cancelled on coroutine cancellation. Do not start background or periodic location work.

Resolve coordinates through the phone's system Geocoder, using its asynchronous API on Android 13+ and an interruptible IO worker on older devices. Wait at most eight seconds for geocoding; older OEM blocking implementations may not honor interruption immediately. Prefer recognized Chinese municipality names in the administrative field to district names, otherwise locality then sub-administrative area; never invent a city from timezone, language, country or province. Missing permission, disabled location, absent provider, missing Geocoder, timeout or errors preserve the current city and allow manual input. A late lookup does not overwrite a manually saved change. Only the sanitized city string is persisted; coordinates never enter Room, settings, exports or logs. New session titles freeze the selected city at start; existing titles remain unchanged.

System location and reverse geocoding may use the device vendor's network service and may transmit coordinates to that service. The app adds no direct third-party geocoding API, key or SDK, does not send audio or subtitles, and never invokes this lookup automatically at launch or recording start. The UI identifies the system lookup and its possible network use. Offline ASR, translation and speaker tracking do not depend on city lookup. No model or dependency-license changes are introduced.

Reference: [Android Geocoder](https://developer.android.com/reference/android/location/Geocoder), [AndroidX LocationManagerCompat](https://developer.android.com/reference/androidx/core/location/LocationManagerCompat).
