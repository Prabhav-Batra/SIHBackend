package com.sih26046.ctms.documents;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the documents module.
 *
 * <p>The backend is selected by an explicit property rather than by
 * {@code @ConditionalOnMissingBean}. That annotation is only dependable on auto-configuration
 * {@code @Bean} methods — on a component-scanned class its result depends on scanning order,
 * and the first version of this module registered no storage backend at all because of it.
 * A named property says which implementation is running and fails loudly when the name is
 * wrong, which is what a decision this consequential should do.
 *
 * <p>{@code @EnableScheduling} used to live here as the platform's first and only timer; B8
 * added a second (the rollup refresh), so it moved to {@code CtmsApplication} per this class's
 * own prior note.
 */
@Configuration
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentsConfig {

    @Bean
    @ConditionalOnProperty(
            name = "ctms.documents.storage-backend",
            havingValue = "local",
            matchIfMissing = true)
    StorageBackend localStorageBackend(DocumentProperties properties) {
        return new LocalStorageBackend(properties);
    }

    @Bean
    @ConditionalOnProperty(name = "ctms.documents.storage-backend", havingValue = "cloudinary")
    StorageBackend cloudinaryStorageBackend(DocumentProperties properties) {
        if (!properties.cloudinary().isConfigured()) {
            // Failing at startup rather than at the first upload. A deployment that believes
            // it is storing documents and is not is the worst of the available outcomes.
            throw new IllegalStateException(
                    "ctms.documents.storage-backend=cloudinary requires cloud-name, api-key"
                            + " and api-secret");
        }
        return new CloudinaryStorageBackend(properties.cloudinary());
    }

    @Bean
    @ConditionalOnProperty(
            name = "ctms.documents.scanner",
            havingValue = "clamav",
            matchIfMissing = true)
    MalwareScanner clamAvScanner(DocumentProperties properties) {
        return new ClamAvScanner(properties);
    }
}
