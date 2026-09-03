// Ethics submission, review, and decision; compliance requirements and per-trial status.
dependencies {
    implementation(projects.ctmsCommon)
    implementation(projects.ctmsSecurity)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // compileOnly: only for the @Schema disambiguation on request/response records that
    // collide by simple name with another module's (springdoc keys its registry that way).
    // The annotation is a no-op if springdoc isn't on the runtime classpath at all; ctms-app
    // brings the real implementation transitively when it is.
    compileOnly("io.swagger.core.v3:swagger-annotations-jakarta:2.2.28")
}
