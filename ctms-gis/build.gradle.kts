// Library module: no Spring Boot plugin, so no executable jar and no web layer by default.
//
// PostGIS aggregation and k-anonymity run in SQL (§10.3, spec §7), not in Java, so this module
// has no JPA entities and needs no ORM — spring-boot-starter-jdbc is enough for JdbcTemplate.
dependencies {
    implementation(projects.ctmsSecurity)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // compileOnly: only for the @Schema disambiguation on the ComplianceSummary record, which
    // collides by simple name with ctms-ethics's own (springdoc keys its registry that way).
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta:2.2.28")
}
