// The security module. Unlike the other libraries this one legitimately needs the web
// layer: a Spring Security filter chain and the /auth endpoints are HTTP concerns.
dependencies {
    implementation(projects.ctmsCommon)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // JWT encode/decode via Spring Security's Nimbus integration rather than a
    // hand-rolled signer — §18.2 needs HS256 issue + verify, which this provides
    // together with claim validation and clock-skew handling.
    implementation("org.springframework.security:spring-security-oauth2-jose")

    // Argon2PasswordEncoder delegates to BouncyCastle; without it on the runtime
    // classpath the encoder fails at construction (§18.4).
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    // Enforces §6.1 mechanically: no role name may appear in an authorization expression.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
}
