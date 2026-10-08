# Release shrinking. Media3 and Compose ship their own consumer rules.

# NewPipeExtractor runs the service's player JavaScript in Rhino, which is reflective; keep both
# whole (the same rules the NewPipe app ships with).
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# LiteRT loads its interpreter through JNI (the sticker cutout model).
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
