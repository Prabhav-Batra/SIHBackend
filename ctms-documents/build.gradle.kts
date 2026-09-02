// Document storage, validation, malware scanning, and the version chain.
dependencies {
    implementation(projects.ctmsCommon)
    implementation(projects.ctmsSecurity)

    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")

    // Content sniffing (§16.5). Tika's core detector reads magic bytes; the parser modules
    // are deliberately absent, because extracting document *content* is not wanted here and
    // would pull a large, historically CVE-prone dependency tree in for no benefit.
    implementation("org.apache.tika:tika-core:3.3.2")

    // clamd's INSTREAM protocol, rather than a hand-rolled socket dialogue. Small, and the
    // chunked-framing and reply-parsing details are exactly the sort of thing that works in
    // testing and truncates on a 40 MB file in production.
    implementation("xyz.capybara:clamav-client:2.1.2")


    // Cloudinary's own SDK rather than hand-built signed URLs. Its signature scheme is
    // undocumented in the details that matter and changes across API versions; getting it
    // subtly wrong yields URLs that work today and 401 after an upgrade.
    implementation("com.cloudinary:cloudinary-http5:2.4.0")
}
