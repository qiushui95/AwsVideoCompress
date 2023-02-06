plugins {
    kotlin("jvm") version "1.8.0"
    kotlin("kapt") version "1.8.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "org.example"
version = "1.0"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    testImplementation(kotlin("test"))

    val javeVersion = "3.3.1"

    implementation("ws.schild:jave-core:$javeVersion")

    implementation("com.amazonaws:aws-lambda-java-core:1.2.2")
    implementation("com.amazonaws:aws-lambda-java-events:3.11.0")

    runtimeOnly("com.amazonaws:aws-lambda-java-log4j2:1.5.1")

    implementation("software.amazon.awssdk:s3:2.19.4")
    testImplementation("software.amazon.awssdk:lambda:2.19.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    implementation("joda-time:joda-time:2.12.2")
}

tasks.test {
    useJUnitPlatform()
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

tasks.withType(Zip::class.java) {
    from("compileJava")
    from("compileKotlin")
    from("processResources")
    into("lib") {
        from("configurations.runtimeClasspath")
    }
}