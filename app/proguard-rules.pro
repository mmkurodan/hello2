# ============================================================
# LlamaChat ProGuard / R8 rules
# ============================================================

# アプリ自身のクラスはすべて保持（リフレクション・ActivityやService参照のため）
-keep class com.micklab.llamachat.** { *; }

# ---- OkHttp / Okio ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }

# ---- Google API Client（CalendarはJSONデシリアライズに@Keyを多用） ----
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keep class com.google.** { *; }
-keep interface com.google.** { *; }
-keepclassmembers class com.google.** { *; }
-dontwarn com.google.**

# ---- GSON (Google HTTP Clientが内部使用) ----
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- Jackson ----
-keep class com.fasterxml.** { *; }
-dontwarn com.fasterxml.**

# ---- Markwon ----
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.**

# ---- Android JSON ----
-keep class org.json.** { *; }

# ---- Kotlin stdlib (OkHttpが参照) ----
-dontwarn kotlin.**
-keep class kotlin.** { *; }

# ---- 汎用 ----
-dontwarn javax.annotation.**
-dontwarn org.codehaus.**
-dontwarn sun.misc.**

# ---- Apache HTTP / javax.naming / JGSS (Google API Client の推移的依存) ----
-dontwarn javax.naming.InvalidNameException
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

# SQLiteOpenHelper サブクラスのonCreate/onUpgradeを保持
-keepclassmembers class * extends android.database.sqlite.SQLiteOpenHelper {
    public void onCreate(android.database.sqlite.SQLiteDatabase);
    public void onUpgrade(android.database.sqlite.SQLiteDatabase, int, int);
}

# BroadcastReceiver / Service / Activity サブクラスはAndroidManifest経由で参照
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
