package com.collabeditor.realtime_editor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

/**
 * Redis-related configuration.
 * <p>
 * Spring Boot's Redis auto-configuration already provides the connection factory and
 * {@code RedisTemplate}/{@code StringRedisTemplate} beans from the {@code spring.data.redis.*}
 * properties, so this class only adds app-level helpers.
 */
@Configuration
public class RedisConfig {

    /**
     * A unique identifier for this running application instance.
     * <p>
     * Regenerated on every boot; useful for tagging entries this node writes to shared
     * (Redis-backed) state so multiple instances can be told apart.
     *
     * @return a random UUID string, stable for the lifetime of this JVM
     */
    @Bean
    public String instanceId() {
        return UUID.randomUUID().toString();
    }
}
