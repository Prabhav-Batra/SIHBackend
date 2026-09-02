package com.sih26046.ctms.documents;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuration for storage, scanning, and signed download (§16). */
@ConfigurationProperties(prefix = "ctms.documents")
public record DocumentProperties(
        @DefaultValue("52428800") long maxFileSizeBytes,
        @DefaultValue("local") String storageBackend,
        @DefaultValue("300s") Duration downloadUrlTtl,
        @DefaultValue Local local,
        @DefaultValue Clamav clamav) {

    /** The filesystem backend: local development, and the test path. */
    public record Local(@DefaultValue("${java.io.tmpdir}/ctms-documents") String root) {}

    public record Clamav(
            @DefaultValue("localhost") String host,
            @DefaultValue("3310") int port,
            @DefaultValue("30s") Duration timeout) {}
}
