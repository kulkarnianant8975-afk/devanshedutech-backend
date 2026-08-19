package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * Who is on live-enquiry duty, and when.
 *
 * <p>The SOP asks for someone watching enquiries every working hour. Without this a new lead
 * arrived owned by nobody and sat there until a counsellor happened to open the list — which is
 * the single most expensive thing that can happen to an enquiry.</p>
 *
 * <p>Shifts are per weekday rather than per date so the roster is set once and keeps working.
 * Overlaps are allowed: two people covering the same hour is a real arrangement, and the
 * earlier-starting shift takes the lead so the assignment is at least predictable.</p>
 */
@Entity
@Table(name = "duty_shifts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DutyShift {

    @Id
    private String id;

    @Column(nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 16, nullable = false)
    private DayOfWeek day;

    private LocalTime startsAt;

    private LocalTime endsAt;

    public boolean covers(LocalTime at) {
        return startsAt != null && endsAt != null
                && !at.isBefore(startsAt) && at.isBefore(endsAt);
    }
}
