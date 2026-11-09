plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core-model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
