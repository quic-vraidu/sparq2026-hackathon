# Add project specific ProGuard rules here.

# Keep llama.cpp JNI — native method names must not be obfuscated
-keep class com.aster.ondevice.llm.LlamaEngine { native <methods>; }

# Keep Hilt-generated entry points
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }

# Keep Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.aster.ondevice.**$$serializer { *; }
-keepclassmembers class com.aster.ondevice.** {
    *** Companion;
}
-keepclasseswithmembers class com.aster.ondevice.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data models used for serialization
-keep class com.aster.ondevice.data.model.** { *; }
-keep class com.aster.ondevice.agent.AgentMessage { *; }
-keep class com.aster.ondevice.agent.AgentState { *; }
