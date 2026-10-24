# Only Lucide's statically referenced icon classes may be removed in this pass.
# Keep application code, serializers, service loaders, JNI/JNA bridges and all
# other dependencies intact. This deliberately trades maximum shrinking for
# compatibility and readable production stack traces.
-keep class !com.composables.icons.lucide.**,** { *; }
-keepattributes *

# OkHttp probes optional TLS providers and GraalVM integration. This desktop
# distribution uses the bundled JDK's TLS provider and HotSpot, so those optional
# classes are intentionally absent. Keep all other unresolved references fatal.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**
