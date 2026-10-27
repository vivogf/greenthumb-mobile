# GreenThumb release R8 rules.
#
# Keep rules stay minimal. Every library on the release path ships consumer rules
# (kotlinx.serialization, Ktor, OkHttp, Room, Credentials, Firebase, Coil, Glance, WorkManager),
# and the app avoids the reflection those rules cannot see:
# - Room is built through the KSP-generated GreenThumbDbConstructor, not by reflective _Impl lookup.
# - Ktor gets the OkHttp engine factory explicitly, so no ServiceLoader engine discovery is needed.
# - All JSON goes through compiler-generated serializers (Type.serializer() / reified calls).
# Add a rule here only for a failure seen in a minified run, with a one-line reason above it.
# Never switch off obfuscation, shrinking or optimization, never silence warnings, and never keep
# whole packages.

# WorkManager 2.7.1 builds InputMerger via Class.newInstance(); its own -keep rule keeps no
# constructor in R8 full mode, so the Glance widget worker failed and the widget never rendered.
-keepclassmembers class * extends androidx.work.InputMerger {
    public <init>();
}
