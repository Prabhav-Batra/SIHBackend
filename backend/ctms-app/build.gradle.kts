plugins {
    id("org.springframework.boot")
}

dependencies {
    // Composition root — the only module that knows about all the others (spec §8).
    implementation(projects.ctmsCommon)
    implementation(projects.ctmsSecurity)
    implementation(projects.ctmsPersistence)
    implementation(projects.ctmsTrials)
    implementation(projects.ctmsClinical)
    implementation(projects.ctmsSafety)
    implementation(projects.ctmsEthics)
    implementation(projects.ctmsDocuments)
    implementation(projects.ctmsGis)
    implementation(projects.ctmsAnalytics)

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Local-testing convenience: a browsable, clickable API explorer generated from the
    // existing @RestController annotations, so manual testing doesn't require hand-rolling a
    // Postman collection. Not part of the platform's own spec.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
    // The composition root hosts the Spring Security filter chain, so the
    // security types are part of its own compile classpath, not just ctms-security's.
    implementation("org.springframework.boot:spring-boot-starter-security")
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
    // GenericContainer itself, for ClamAvScanIT's real clamd — declared explicitly rather
    // than relied on as a transitive of the two above, which is an implementation detail of
    // theirs and not a contract either advertises.
    testImplementation("org.testcontainers:testcontainers")
    // ctms-documents depends on this as `implementation`, so it is not on ctms-app's
    // classpath transitively. ClamAvScanIT uses the client directly to confirm clamd is
    // actually answering PING before any test sends it a scan.
    testImplementation("xyz.capybara:clamav-client:2.1.2")
}

