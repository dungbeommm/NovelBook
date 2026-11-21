# TramTruyenReader release rules
-keepattributes *Annotation*,AnnotationDefault,InnerClasses,Signature,EnclosingMethod,SourceFile
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.jsoup.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
# Serialization is JSON-tree/manual; no broad app keep rule is needed.

# ---- TOI UU TOC DO CHAY (v31) ----
# Cho R8 doi package/quyen truy cap de inline manh tay hon -> it lenh goi hon.
-allowaccessmodification
-repackageclasses r
-adaptclassstrings
# Không để lộ tên file Kotlin gốc trong stack trace release. LineNumberTable
# không được keep nên R8 cũng loại thông tin dòng; debug build vẫn giữ đầy đủ.
-renamesourcefileattribute R

# Xoa han log muc debug/verbose/info khoi ban release. Khong chi bo dong log ma
# bo luon phep noi chuoi "...${bien}..." dung de tao thong bao -> bot rac bo nho
# tren cac duong chay nong (scraper, feeder PCM, tick vi tri).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static boolean isLoggable(...);
}


# sherpa-onnx JNI resolves these exact class/method/field names.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# GeckoFeature resolves these entry points after Android installs the split.
-keep class vn.tramtruyen.reader.gecko.GeckoBrowserActivity { public <init>(); }
-keep class vn.tramtruyen.reader.gecko.GeckoLoader { public <init>(); }
