package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * When the institute is open, one row per weekday.
 *
 * <p>This exists because the five-minute reply rule was being measured against the wall clock.
 * An enquiry that arrived at 11pm and was answered at 9:30am counted as 630 minutes late, so
 * the metric measured what time students happened to fill the form rather than how quickly
 * anyone replied — and it blamed a counsellor who was asleep.</p>
 */
@Entity
@Table(name = "working_hours")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkingHours {

    // "day" is a reserved word in H2 and a keyword in several other engines, so the column is
    // named explicitly rather than left to Hibernate's default.
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 16)
    private DayOfWeek day;

    private LocalTime opensAt;

    private LocalTime closesAt;

    /** A closed day keeps its times, so reopening a Sunday does not mean retyping them. */
    private boolean closed;

    /** Minutes of this day that fall inside opening hours, for a window already clipped to it. */
    public long minutesBetween(LocalTime from, LocalTime to) {
        if (closed || opensAt == null || closesAt == null || !closesAt.isAfter(opensAt)) return 0;
        LocalTime start = from.isBefore(opensAt) ? opensAt : from;
        LocalTime end = to.isAfter(closesAt) ? closesAt : to;
        return end.isAfter(start) ? java.time.Duration.between(start, end).toMinutes() : 0;
    }
}
