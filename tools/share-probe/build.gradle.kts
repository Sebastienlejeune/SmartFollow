plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":data-share"))
    implementation(project(":alarm-engine"))
    implementation(libs.ktor.client.java)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.zamateka.smartfollow.probe.MainKt")
}
