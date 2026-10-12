# kotlinx.serialization keeps what it needs through its own consumer rules.
# Ktor's OkHttp engine references optional classes that are not on Android.
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
