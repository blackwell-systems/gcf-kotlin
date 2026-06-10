plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    `maven-publish`
    application
}

application {
    mainClass.set("com.blackwellsystems.gcf.CliKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

group = "com.blackwellsystems"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    environment("GCF_ITERATIONS", System.getenv("GCF_ITERATIONS") ?: "100000")
}

kotlin {
    jvmToolchain(21)
}

tasks.register<Jar>("fatJar") {
    archiveBaseName.set("gcf")
    archiveClassifier.set("")
    archiveVersion.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.blackwellsystems.gcf.CliKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "com.blackwellsystems"
            artifactId = "gcf"
        }
    }
}
