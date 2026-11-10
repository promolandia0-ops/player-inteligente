# ONNX Runtime Java + JNI (NudeNet). Do NOT strip — clinit/JNI look up by name.
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-keepnames class ai.onnxruntime.**
-dontwarn ai.onnxruntime.**

# Keep all native methods (JNI)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod,SourceFile,LineNumberTable
