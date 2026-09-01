plugins {
    id("org.springframework.boot")
    id("org.graalvm.buildtools.native")
}

dependencies {
    // Composition root — the only module that knows about all the others (spec §8).
    implementation(project(":ctms-common"))
    implementation(project(":ctms-security"))
    implementation(project(":ctms-persistence"))
    implementation(project(":ctms-trials"))
    implementation(project(":ctms-clinical"))
    implementation(project(":ctms-safety"))
    implementation(project(":ctms-ethics"))
    implementation(project(":ctms-documents"))
    implementation(project(":ctms-gis"))
    implementation(project(":ctms-analytics"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-registry-prometheus")

    // Spec §5: Flyway, SQL-first — RLS policies and grants are raw SQL (B3).
    // Must be the STARTER, not flyway-core: Boot 4 moved Flyway autoconfiguration out
    // of spring-boot-autoconfigure into its own module. flyway-core alone puts the
    // library on the classpath and silently never runs it.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Boot 4 split test autoconfiguration per technology; MockMvc support moved out
    // of spring-boot-test-autoconfigure into its own artifact.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    // Testcontainers 2.x (managed at 2.0.5 by the Boot 4.1 BOM) prefixes every
    // module artifact with "testcontainers-". The 1.x names resolve to no version.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

// Spec §5.2: native image is built in CI from day one so AOT drift surfaces
// in the PR that caused it, not in B9.
graalvmNative {
    binaries {
        named("main") {
            imageName.set("ctms")
        }
    }
}
