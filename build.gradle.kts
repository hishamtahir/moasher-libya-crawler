plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    application
}
kotlin { jvmToolchain(17) }
// shared/ = exact copies of the Android app's parser/content/market sources (tools/sync_shared.sh), so the server
// parses posts with the same code as the app.
sourceSets { main { kotlin.srcDir("shared") } }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
application { mainClass.set(providers.gradleProperty("mainClass").orElse("tools.BoardCrawlerKt")) }
