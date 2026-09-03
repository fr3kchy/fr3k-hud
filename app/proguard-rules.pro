# Keep fr3k protocol classes for reflection-based serialization
-keep class com.mcpintelligence.fr3k.protocol.** { *; }
-keepclassmembers class * implements kotlinx.serialization.KSerializer { *; }