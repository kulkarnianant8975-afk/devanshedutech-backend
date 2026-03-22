package com.devanshedutech.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseKeepAliveService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Pings the database every 5 minutes to prevent Neon DB from scaling to zero (sleeping).
     */
    @Scheduled(fixedRate = 300000) // 5 minutes in milliseconds
    public void keepAlive() {
        try {
            log.info("Executing database keep-alive heartbeat...");
            jdbcTemplate.execute("SELECT 1");
            log.info("Database heartbeat successful.");
        } catch (Exception e) {
            log.error("Database heartbeat failed: {}", e.getMessage());
        }
    }
}
