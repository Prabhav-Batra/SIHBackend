// Library module: no Spring Boot plugin, so no executable jar and no web layer by default.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // The job queue (spec §10) is plain JDBC: the claim is a single UPDATE … FOR UPDATE SKIP
    // LOCKED, which has no useful expression through JPA.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // The request-id filter and the global error envelope (§18.17, §30.3) are servlet-layer
    // concerns, same as every domain module's own @RestController — not a departure from
    // "libraries own no web dependency of their own choosing" (README), just this module's
    // first need for one.
    implementation("org.springframework.boot:spring-boot-starter-web")
    // Explicit rather than relying on -web's transitive resolution: AuditTrail serialises
    // old_values/new_values (§19.5) and needs ObjectMapper regardless of which starter
    // happens to bring Jackson along.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // For AccessDeniedException/AuthenticationException in the global error advice — the
    // exception types only, not the filter chain (that stays ctms-security's).
    implementation("org.springframework.boot:spring-boot-starter-security")
}
