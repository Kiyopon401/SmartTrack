buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.4.0")
        classpath("com.google.gms:google-services:4.4.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22") // Changed from 2.0.0 to 1.9.22
    }
}

plugins {
    id("com.android.application") version "8.4.0" apply false
    id("com.android.library") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false // Changed from 2.0.0 to 1.9.22
    id("org.jetbrains.kotlin.kapt") version "1.9.22" apply false // Changed from 2.0.0 to 1.9.22
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false // Updated to match Kotlin 1.9.22
}