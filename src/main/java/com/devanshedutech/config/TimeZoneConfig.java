package com.devanshedutech.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Pins the application to the institute's timezone.
 *
 * <p>Almost every rule in this system is a date rather than an instant: a next touch is due
 * "today", a ladder step falls on "day 5", a lead is "two days overdue". Those are all resolved
 * with {@code LocalDate.now()}, which reads the JVM's default zone — and a server running in
 * UTC is five and a half hours behind Parbhani. A counsellor booking a follow-up at 8pm would
 * have it recorded for the previous day, and the morning pass would treat it as already
 * overdue.</p>
 *
 * <p>The scheduler already pins its cron to this zone; this makes the rest of the application
 * agree with it. The institute operates in one place, so a single default is the simplest thing
 * that is actually correct — per-user timezones would be complexity with no user.</p>
 */
@Slf4j
@Configuration
public class TimeZoneConfig {

    @Value("${app.crm.timezone:Asia/Kolkata}")
    private String timezone;

    @PostConstruct
    public void applyTimezone() {
        TimeZone zone = TimeZone.getTimeZone(timezone);
        TimeZone.setDefault(zone);
        log.info("Application timezone set to {} — all dates and follow-up scheduling use it.",
                zone.getID());
    }
}
