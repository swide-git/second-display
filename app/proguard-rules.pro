# R8 rules for Second Display.

# JNI: the C functions are named after the class and method
# (Java_io_github_swiderekartmax_seconddisplay_NativeBridge_*).
-keep class io.github.swiderekartmax.seconddisplay.NativeBridge { *; }

# receiver_jni.c finds the listener interface with FindClass and calls its methods by name
# (GetMethodID "onVideoFrame", "onAudioFrame", ...), on whatever object implements it.
-keep interface io.github.swiderekartmax.seconddisplay.NativeBridge$Listener { *; }
-keep class * implements io.github.swiderekartmax.seconddisplay.NativeBridge$Listener { *; }

# Readable crash reports: keep file names and line numbers, rename the source file attribute.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
