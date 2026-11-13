# HomeRelay ProGuard/R8 rules.
# The app does no reflection-based serialization; rules below are intentionally minimal.
-keepattributes *Annotation*
-keep class com.homerelay.app.service.SharingService { *; }
# Keep our framework-free core (plain Java, used from Kotlin).
-keep class com.homerelay.app.proxy.** { *; }
-keep class com.homerelay.app.qr.** { *; }
-keep class com.homerelay.app.data.Validation { *; }
-keep class com.homerelay.app.util.FormatUtils { *; }
