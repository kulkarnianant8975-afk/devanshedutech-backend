package com.devanshedutech.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "courses",          // CourseController list
                "placedStudents",   // PlacedStudentService list
                "mentors",          // MentorService list
                "stats"             // StatsController dashboard
        );
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)  // Cache expires 5 min after write
                .maximumSize(300)                        // Max 300 entries per cache
                .recordStats()                           // Enable hit/miss metrics
        );
        return cacheManager;
    }
}
