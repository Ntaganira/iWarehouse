package com.ntaganira.heritier.iWarehouse.config;

import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/** Fills BaseEntity.createdBy / updatedBy with the current username ("system" for background jobs). */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of(Optional.ofNullable(AppUserPrincipal.currentUsername()).orElse("system"));
    }
}
