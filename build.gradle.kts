plugins {
    java
    id("org.springframework.boot") version "4.1.1" apply false
}

// Spec §5: Java 26, latest release. One line to move to 25 LTS if EOL (Mar 2027) bites.
val javaLanguageVersion = 26

// Spec §5.2: the BOM is applied to every module so versions are managed in one place.
val springBootVersion = "4.1.1"

allprojects {
    group = "com.sih26046"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(javaLanguageVersion)) }
    }

    dependencies {
        add("implementation", platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        add("testImplementation", platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion"))
        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        // -parameters is required by Spring for constructor binding on records.
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "skipped", "failed") }
    }
}
