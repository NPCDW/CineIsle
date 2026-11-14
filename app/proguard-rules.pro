# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontobfuscate
-keep,allowoptimization class is.xyz.mpv.** { public protected *; }
-keep,allowoptimization class net.mediaarea.mediainfo.lib.** { public protected *; }
-keepclassmembers class net.mediaarea.mediainfo.lib.** {
    native <methods>;
}
-dontwarn org.xmlpull.v1.**
-dontnote org.xmlpull.v1.**
-dontwarn org.slf4j.impl.StaticLoggerBinder

# pgjdbc optional GSS/Kerberos paths are unused on Android.
-dontwarn org.ietf.jgss.**
-dontnote org.ietf.jgss.**

# Keep all classes that use reflection or are loaded dynamically
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# DanmakuFlameMaster（v115 起源码内置，原先 AAR 的 consumer rules 随之失效，必须自己 keep）
# 引擎内部按字段名/类型直接访问（flags、duration、mTimer 等），混淆会直接崩。
-keep class master.flame.danmaku.** { *; }
-dontwarn master.flame.danmaku.**
-keep class tv.cjump.jni.** { *; }
-dontwarn tv.cjump.jni.**

# pgjdbc: fixed reflectively-created SSL factories and Android-safe SAN verifier.
# Do not keep the entire driver: optional desktop DataSource/JNDI/OSGi/GSS paths are unused.
-keep,allowoptimization class org.postgresql.ssl.DefaultJavaSSLFactory {
    public <init>(java.util.Properties);
}
-keep,allowoptimization class org.postgresql.ssl.NonValidatingFactory {
    public <init>(java.lang.String);
}
-keep,allowoptimization class app.marlboroadvance.mpvex.domain.postgres.AndroidPostgresHostnameVerifier {
    public <init>();
    public boolean verify(java.lang.String, javax.net.ssl.SSLSession);
}
-dontwarn javax.naming.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn java.awt.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.security.auth.callback.**
-dontwarn javax.transaction.xa.**
# SQLXML StAX conversion is optional and never used by the read-only Emby row mapper.
-dontwarn javax.xml.stream.XMLInputFactory
-dontwarn javax.xml.stream.XMLOutputFactory
-dontwarn javax.xml.stream.XMLStreamException
-dontwarn javax.xml.stream.XMLStreamReader
-dontwarn javax.xml.stream.XMLStreamWriter
-dontwarn javax.xml.transform.stax.StAXResult
-dontwarn javax.xml.transform.stax.StAXSource
-dontwarn org.osgi.**
-dontwarn org.checkerframework.**
-dontwarn com.sun.jna.**
-dontwarn waffle.windows.**
