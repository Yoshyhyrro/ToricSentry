# ToricSentry release rules.
# Current app code does not depend on reflection-heavy frameworks or JSON serializers,
# so obfuscation can remain mostly aggressive. The rules below preserve diagnostics and
# Kotlin metadata that are useful when investigating coroutine or math-engine failures.

# Keep source and line information for release stack traces and retrace.
-keepattributes SourceFile,LineNumberTable

# Preserve Kotlin/generic metadata used by coroutines and nested/top-level declarations.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Keep the Android entry point explicit in case the manifest is processed by external tooling.
-keep class com.yoshyhyrro.toricsentry.MainActivity { <init>(...); }

# Keep demo entry points and model names stable if they are later referenced from tests,
# manual adb instrumentation, or diagnostic logging around the detector engines.
-keep class com.yoshyhyrro.toricsentry.core.ToricCodeSkimmingDetector { *; }
-keep class com.yoshyhyrro.toricsentry.core.QToricDetector { *; }
-keep class com.yoshyhyrro.toricsentry.core.SensorReading { *; }
-keep class com.yoshyhyrro.toricsentry.core.ParityCheck { *; }
-keep class com.yoshyhyrro.toricsentry.core.SkimmingEvent { *; }
-keep class com.yoshyhyrro.toricsentry.core.HybridReading { *; }
-keep class com.yoshyhyrro.toricsentry.core.QParityCheck { *; }

# Preserve top-level demo functions compiled into *Kt holder classes.
-keep class com.yoshyhyrro.toricsentry.core.DetectorCoreKt { *; }
-keep class com.yoshyhyrro.toricsentry.core.QDeformedDetectorCoreKt { *; }
