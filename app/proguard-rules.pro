# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# 保留 stack trace 行号，便于线上日志定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 注解信息（Room、Gson @SerializedName 都依赖）
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ============== Gson DTO（反射读写字段） ==============
-keep class com.example.network.dto.** { *; }
-keep class com.example.speakassist.ApiConfigBackup$BackupDto { *; }

# Gson 自身
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * extends com.google.gson.TypeAdapterFactory
-keep class * extends com.google.gson.JsonSerializer
-keep class * extends com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ============== Room 实体与 DAO ==============
-keep class com.example.data.entity.** { *; }
-keep class com.example.data.dao.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# ============== Vosk JNI ==============
-keep class org.vosk.** { *; }
-keep class org.kaldi.** { *; }
-dontwarn org.vosk.**
-dontwarn org.kaldi.**

# ============== OkHttp / Okio ==============
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ============== Retrofit（虽未实际使用，但 build path 含此依赖） ==============
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Exceptions

# ============== Kotlin 协程 ==============
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-dontwarn kotlinx.coroutines.**

# ============== Kotlin Metadata（反射 + 协程依赖） ==============
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ============== AndroidViewModel（构造反射） ==============
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}

# ============== AccessibilityService / IME 入口（系统 Manifest 引用） ==============
-keep class com.example.service.MyAccessibilityService { *; }
-keep class com.example.service.MyInputMethodService { *; }

# ============== 项目内通过反射调用的方法 ==============
# AccessibilityNodeInfo.close 反射兼容（input/AccessibilityTextInput.kt）
-keepclassmembers class android.view.accessibility.AccessibilityNodeInfo {
    public void close();
}

# DataStore Preferences（默认即可，但显式声明防漏）
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# QMUI（外部依赖反射重）
-keep class com.qmuiteam.** { *; }
-dontwarn com.qmuiteam.**

# Material Components 警告抑制
-dontwarn com.google.android.material.**

# Firebase（依赖在 libs.versions.toml 引入了 firebase-appdistribution-gradle，运行时极少用到，防漏）
-dontwarn com.google.firebase.**

# Netty / Reactor 间接依赖链（来自 firebase-appdistribution-gradle），R8 提示缺类
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
-dontwarn io.netty.**
-dontwarn reactor.**
