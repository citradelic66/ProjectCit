# JNI entry points are looked up by name from C++.
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.projectcit.libretro.** { *; }
