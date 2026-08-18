# YFP release ProGuard rules.
# The app has a tiny, self-contained surface (no reflection-heavy libs,
# no JSON model binding, no third-party SDKs), so default AGP shrinking
# is safe with only a couple of defensive keeps below.

# Keep the foreground service's class name stable — it's referenced by
# string action names in notification PendingIntents.
-keep class com.mnmyounus.yfp.service.WipeService { *; }

# Keep Parcelable implementations (used to pass WipeConfig into the service).
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Standard AndroidX/Kotlin metadata keep (harmless if unused).
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontwarn kotlin.**
