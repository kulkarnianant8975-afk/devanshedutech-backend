package com.devanshedutech;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableScheduling
@EnableCaching
public class DevanshEduTechApplication implements org.springframework.boot.CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public static void main(String[] args) {
        SpringApplication.run(DevanshEduTechApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        System.out.println("\n--- BROCHURE DATABASE CHECK ---");
        try {
            Integer appSettingsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_setting WHERE setting_key LIKE 'GLOBAL_BROCHURE' OR setting_key LIKE 'COURSE_BROCHURE_%'", 
                Integer.class
            );
            System.out.println("Total Brochure Settings Found: " + appSettingsCount);

            Integer chunksCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM brochure_chunks", 
                Integer.class
            );
            System.out.println("Total Brochure Chunks Found: " + chunksCount);
            
            if (chunksCount != null && chunksCount > 0) {
                System.out.println("Details per brochure:");
                jdbcTemplate.queryForList("SELECT setting_key, COUNT(*) as count FROM brochure_chunks GROUP BY setting_key")
                    .forEach(row -> System.out.println(" - " + row.get("setting_key") + ": " + row.get("count") + " chunks"));
            }
        } catch (Exception e) {
            System.err.println("Error checking database: " + e.getMessage());
        }
        System.out.println("-------------------------------\n");
    }

    @PostConstruct
    public void init() {
        try {
            jdbcTemplate.execute("ALTER TABLE users ALTER COLUMN photo_url TYPE TEXT");
        } catch (Exception e) {
            // Table might not exist yet, or column is already text
        }
    }
}
