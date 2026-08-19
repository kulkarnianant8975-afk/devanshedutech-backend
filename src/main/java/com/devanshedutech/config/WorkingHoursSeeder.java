package com.devanshedutech.config;

import com.devanshedutech.model.WorkingHours;
import com.devanshedutech.repository.WorkingHoursRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Sets the opening hours the first time the application starts.
 *
 * <p>Ten to seven, Monday to Saturday, is the institute's actual pattern and a reasonable
 * default for one like it. It is written to the database rather than compiled in, so changing
 * it is a screen rather than a deployment.</p>
 *
 * <p>Seeded only when the table is empty. Hours edited in the product are never overwritten by
 * a redeploy — an institute that shortens its Saturday would otherwise find it silently
 * restored every release.</p>
 */
@Slf4j
@Configuration
public class WorkingHoursSeeder {

    @Bean
    @Order(6)
    public ApplicationRunner seedWorkingHours(WorkingHoursRepository repo) {
        return args -> {
            if (repo.count() > 0) return;

            List<WorkingHours> week = new ArrayList<>();
            for (DayOfWeek day : DayOfWeek.values()) {
                week.add(WorkingHours.builder()
                        .day(day)
                        .opensAt(LocalTime.of(10, 0))
                        .closesAt(LocalTime.of(19, 0))
                        // Sunday keeps its times rather than being blanked, so opening for an
                        // admission season is one switch instead of retyping.
                        .closed(day == DayOfWeek.SUNDAY)
                        .build());
            }
            repo.saveAll(week);
            log.info("Opening hours set to 10:00-19:00, Monday to Saturday. Change them in Settings.");
        };
    }
}
