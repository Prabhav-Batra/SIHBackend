package com.sih26046.ctms.security;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Replaces Boot's default JPA transaction manager with the RLS-aware one.
 *
 * <p>Overriding the single transaction manager — rather than adding a second one — is what
 * makes the identity binding unconditional. There is no path through {@code @Transactional}
 * that skips it.
 */
@Configuration
public class RlsTransactionConfig {

    @Bean
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        RlsAwareTransactionManager manager = new RlsAwareTransactionManager();
        manager.setEntityManagerFactory(factory);
        return manager;
    }
}
