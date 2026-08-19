package com.devanshedutech.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * A single day the institute is shut regardless of what the weekly hours say.
 *
 * <p>Kept as dated rows rather than a rule, because the festivals that close an institute in
 * Maharashtra do not fall on the same date each year and nobody should have to encode that.</p>
 */
@Entity
@Table(name = "holidays")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Holiday {

    @Id
    @Column(name = "holiday_date")
    private LocalDate day;

    @Column(length = 120)
    private String name;
}
