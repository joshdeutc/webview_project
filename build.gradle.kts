// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

subprojects {
    plugins.withId("com.android.application") {
        configure<com.android.build.api.dsl.ApplicationExtension> {
            val keystoreFile = (project.findProperty("RELEASE_KEYSTORE_FILE") as String?)
                ?: System.getenv("RELEASE_KEYSTORE_FILE")
            if (keystoreFile != null && file(keystoreFile).exists()) {
                signingConfigs {
                    create("release") {
                        storeFile = file(keystoreFile)
                        storePassword = (project.findProperty("RELEASE_KEYSTORE_PASSWORD") as String?)
                            ?: System.getenv("RELEASE_KEYSTORE_PASSWORD")
                        keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                            ?: System.getenv("RELEASE_KEY_ALIAS")
                        keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                            ?: System.getenv("RELEASE_KEY_PASSWORD")
                    }
                }
                buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
