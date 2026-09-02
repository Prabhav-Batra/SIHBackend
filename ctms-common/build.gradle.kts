// Library module: no Spring Boot plugin, so no executable jar and no web layer by default.
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // The job queue (spec §10) is plain JDBC: the claim is a single UPDATE … FOR UPDATE SKIP
    // LOCKED, which has no useful expression through JPA.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
