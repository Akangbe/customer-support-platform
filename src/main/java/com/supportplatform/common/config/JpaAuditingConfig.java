package com.supportplatform.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Deliberately kept off {@code SupportPlatformApplication} itself.
 * {@code @WebMvcTest} slices still load the main class as a configuration
 * source (for base-package detection), and {@code @EnableJpaAuditing} there
 * would try to wire a JPA-backed auditing handler with no JPA context in
 * that slice — a separate configuration class isn't picked up by slice
 * tests, so it stays out of their way.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
