// Every plugin is declared here once so all modules share one classpath (Phase 2 adds :app on the Android plugin).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
}
