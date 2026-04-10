package com.devanshedutech.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {
    // Enables In-Memory ConcurrentMapCacheManager by default in Spring Boot
    // providing lightning-fast cached responses for database lookups.
}
