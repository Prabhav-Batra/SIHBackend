// Library module: no Spring Boot plugin, so no executable jar and no web layer by default.
//
// Plain SQL over JdbcTemplate, not JPA — the rollup lives in a materialized view and the
// dashboard is a read model, not a domain model with entities of its own.
dependencies {
    implementation(projects.ctmsSecurity)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Caffeine, not hand-rolled TTL bookkeeping (standing instruction, §7 of BACKEND_CONTEXT):
    // the dashboard cache-aside layer (§12.1, §23.8).
    implementation("com.github.ben-manes.caffeine:caffeine")
}
