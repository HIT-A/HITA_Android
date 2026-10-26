# ====== HITA_Agent ProGuard/R8 Rules (Conservative) ======

# Keep line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes *Annotation*

# ====== Kotlin Core ======
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Kotlin coroutines & suspend functions
-keepclassmembers class kotlin.coroutines.** { *; }
-keepclassmembers class * {
    kotlin.coroutines.Continuation **;
}

# Kotlin objects (singletons)
-keepclassmembers class * {
    ** INSTANCE;
    ** INSTANCE$;
}

# Kotlin companion objects
-keepclassmembers class * {
    ** Companion;
    ** Companion$;
}
-keep class **$Companion { *; }
-keep class **$Companion$* { *; }

# Kotlin data classes
-keepclassmembers class * {
    *** copy(...);
    *** component1(...);
    *** component2(...);
    *** component3(...);
    *** component4(...);
    *** component5(...);
}

# ====== AndroidX / Support ======
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.view.View

# ====== Room Database (Critical) ======
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase$* { *; }
-keep class **$*_Impl { *; }
-keep class **$*_Impl$* { *; }

# Keep all Room entities, DAOs, and TypeConverters
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep class * {
    @androidx.room.TypeConverter <methods>;
}
-keepclassmembers @androidx.room.Entity class * {
    <init>();
    <fields>;
}
-keepclassmembers @androidx.room.Dao interface * {
    <methods>;
}

# Keep Room database classes in ALL modules
-keep class cn.limpu.hita.data.AppDatabase { *; }
-keep class cn.limpu.hita.data.AppDatabase$* { *; }
-keep class cn.limpu.hita.data.AppDatabase_Impl { *; }
-keep class com.limpu.stupiduser.data.UserDatabase { *; }
-keep class com.limpu.stupiduser.data.UserDatabase$* { *; }
-keep class com.limpu.stupiduser.data.UserDatabase_Impl { *; }

# Keep all model classes used by Room/Gson in ALL modules
-keep class cn.limpu.hita.data.model.** { *; }
-keep class com.limpu.stupiduser.data.model.** { *; }
-keep class cn.limpu.hita.data.source.dao.** { *; }
-keep class com.limpu.stupiduser.data.source.dao.** { *; }

# ====== Gson / JSON Parsing (Critical) ======
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class * {
    @com.google.gson.annotations.Expose <fields>;
}

# Keep ALL data classes that may be serialized by Gson
-keep class cn.limpu.hita.data.model.** { <fields>; }
-keep class cn.limpu.hita.agent.**.** { <fields>; }
-keep class cn.limpu.hita.ui.main.agent.** { <fields>; }

# ====== Retrofit / OkHttp ======
-keep class com.squareup.retrofit2.** { *; }
-keepclassmembers interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}

# Keep Retrofit service interfaces in ALL modules
-keep interface cn.limpu.hita.data.source.web.** { *; }
-keep interface cn.limpu.hita.agent.remote.** { *; }
-keep interface com.limpu.component.**.** { *; }
-keep interface com.limpu.stupiduser.data.source.web.** { *; }

# ====== PDFBox (Reflection-heavy) ======
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**

# ====== Apache POI ======
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
-dontwarn schemasMicrosoftComOfficeOffice.**
-dontwarn schemasMicrosoftComOfficePowerpoint.**
-dontwarn schemasMicrosoftComOfficeWord.**
-dontwarn schemasMicrosoftComVml.**
-dontwarn com.microsoft.schemas.**.**
-dontwarn org.etsi.**.**
-dontwarn org.w3.2009.**.**
-dontwarn javax.xml.stream.**

# ====== Markwon ======
-keep class io.noties.markwon.** { *; }
-keep class io.noties.markwon.ext.** { *; }
-keep class io.noties.markwon.image.** { *; }
-dontwarn io.noties.markwon.**

# ====== JLatexMath ======
-keep class org.scilab.forge.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# ====== iCal4j ======
-keep class net.fortuna.ical4j.** { *; }
-dontwarn net.fortuna.ical4j.**

# ====== Apache Commons Logging (ical4j dependency) ======
-keep class org.apache.commons.logging.** { *; }
-dontwarn org.apache.commons.logging.**

# ====== WorkManager ======
-keep class * extends androidx.work.Worker { <init>(...); }
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keepclassmembers class * extends androidx.work.Worker { <methods>; }
-keepclassmembers class * extends androidx.work.ListenableWorker { <methods>; }

# ====== Glide ======
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-dontwarn com.bumptech.glide.load.resource.bitmap.VideoDecoder**

# ====== Jetpack Compose ======
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ====== JSoup ======
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ====== BouncyCastle ======
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ====== Serializable ======
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ====== Enums ======
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    **[] $VALUES;
}

# ====== Fragments / ViewModels ======
-keep class * extends androidx.fragment.app.Fragment { <init>(...); }
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keepclassmembers class * extends androidx.lifecycle.ViewModel { <fields>; <methods>; }

# ====== LiveData / Observer internals ======
-keep class androidx.lifecycle.LiveData { *; }
-keep class androidx.lifecycle.MutableLiveData { *; }
-keep class androidx.lifecycle.Transformations { *; }
-keep class androidx.lifecycle.Observer { *; }

# ====== Keep ALL application classes (conservative fallback) ======
-keep class cn.limpu.hita.** { *; }
-keep class com.limpu.component.** { *; }
-keep class com.limpu.stupiduser.** { *; }
-keep class com.limpu.style.** { *; }
-keep class com.limpu.hita.theta.** { *; }

# ====== Third-party UI libs ======
-keep class br.com.simplepass.loadingbutton.** { *; }
-keep class net.cachapa.expandablelayout.** { *; }
-keep class com.beloo.widget.chipslayoutmanager.** { *; }
-keep class tyrantgit.explosionfield.** { *; }
-keep class com.cncoderx.wheelview.** { *; }
-keep class me.ibrahimsn.lib.** { *; }
-keep class com.angcyo.dslspan.** { *; }
-keep class top.zibin.luban.** { *; }
-keep class com.bm.library.** { *; }

# ====== Missing classes (R8 auto-generated) ======
-dontwarn com.beloo.widget.chipslayoutmanager.Orientation
-dontwarn java.awt.Rectangle
-dontwarn java.awt.Shape
-dontwarn java.awt.geom.AffineTransform
-dontwarn java.awt.geom.PathIterator
-dontwarn java.awt.geom.Point2D
-dontwarn java.awt.geom.Rectangle2D$Double
-dontwarn java.awt.geom.Rectangle2D
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.OpenSSLProvider
-dontwarn sun.misc.Perf

# ====== Remove logging in release ======
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn com.github.luben.zstd.ZstdOutputStream
-dontwarn javax.annotation.ParametersAreNonnullByDefault
-dontwarn javax.servlet.ServletContextListener
-dontwarn org.apache.avalon.framework.logger.Logger
-dontwarn org.apache.log.Hierarchy
-dontwarn org.apache.log.Logger
-dontwarn org.apache.log4j.Category
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.brotli.dec.BrotliInputStream
-dontwarn org.tukaani.xz.ARMOptions
-dontwarn org.tukaani.xz.ARMThumbOptions
-dontwarn org.tukaani.xz.FilterOptions
-dontwarn org.tukaani.xz.IA64Options
-dontwarn org.tukaani.xz.LZMA2Options
-dontwarn org.tukaani.xz.LZMAOutputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.PowerPCOptions
-dontwarn org.tukaani.xz.SPARCOptions
-dontwarn org.tukaani.xz.X86Options
-dontwarn org.tukaani.xz.XZ
-dontwarn org.tukaani.xz.XZOutputStream