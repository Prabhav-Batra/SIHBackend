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
        @DefaultValue Clamav clamav,
        @DefaultValue Cloudinary cloudinary) {

    /** The filesystem backend: local development, and the test path. */
    public record Local(@DefaultValue("${java.io.tmpdir}/ctms-documents") String root) {}

    /**
     * Cloudinary credentials.
     *
     * <p>No defaults: a deployment configured to use Cloudinary and missing its secret should
     * fail at startup, not at the first upload.
     */
    public record Cloudinary(
            @DefaultValue("") String cloudName,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String apiSecret,
            @DefaultValue("ctms") String folder) {

        public boolean isConfigured() {
            return !cloudName.isBlank() && !apiKey.isBlank() && !apiSecret.isBlank();
        }
    }

    public record Clamav(
            @DefaultValue("localhost") String host,
            @DefaultValue("3310") int port,
            @DefaultValue("30s") Duration timeout) {}
}
