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

    // ./gradlew externalTest — verifies the adapters against the real services.
    // Registered inside plugins.withType so the source sets exist: reaching for them at the
    // top of the subprojects block finds only ExtraPropertiesExtension.
    plugins.withType<JavaPlugin> {
        val sources = extensions.getByType<SourceSetContainer>()

        // Only the standard `test` task excludes external tests. Putting this in
        // configureEach would apply it to externalTest as well, and a task that both
        // includes and excludes a tag runs nothing at all — while still reporting
        // BUILD SUCCESSFUL, which is the worst way for a verification task to fail.
        tasks.named<Test>("test") {
            useJUnitPlatform {
                // Opt-in: these need credentials and the network, and write to a live account.
                excludeTags("external")
            }
        }
        tasks.register<Test>("externalTest") {
            description = "Runs tests tagged 'external' against real third-party services."
            group = "verification"
            testClassesDirs = sources["test"].output.classesDirs
            classpath = sources["test"].runtimeClasspath
            useJUnitPlatform { includeTags("external") }
            // A live service can change under us; a cached "up to date" would hide that.
            outputs.upToDateWhen { false }
        }
    }
}
