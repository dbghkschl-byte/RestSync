plugins { id("com.android.application") version "9.4.0" }
dependencies {
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
android {
    namespace = "com.restsync.demo"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig {
        applicationId = "com.restsync.demo"
        minSdk = 28
        targetSdk = 34
        versionCode = 17
        versionName = "0.17.0"
    }
}
