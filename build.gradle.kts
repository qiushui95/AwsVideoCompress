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

    val awsSdkVersion="2.19.31"

    implementation("software.amazon.awssdk:s3:$awsSdkVersion")
    implementation("software.amazon.awssdk:lambda:$awsSdkVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    implementation("joda-time:joda-time:2.12.2")

    implementation("com.squareup.moshi:moshi:1.14.0")
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.14.0")

    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.10.0")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
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