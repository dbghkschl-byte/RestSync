plugins { id("com.android.application") version "9.4.0" }
dependencies { implementation("ai.pleos.playground:NaviHelper:3.0.5") }
android {
    namespace = "com.restsync.demo"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.restsync.demo"
        minSdk = 28
        targetSdk = 34
        versionCode = 16
        versionName = "0.16.0"
    }
}
