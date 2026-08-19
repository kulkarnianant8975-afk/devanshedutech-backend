package com.devanshedutech.model;

import com.devanshedutech.model.crm.Grade;
import jakarta.persistence.*;
import lombok.*;

/**
 * One rung of a grade's seven-step follow-up ladder.
 *
 * <p>Each grade is a lane: work the seven steps, and if the student has not converted by the
 * end, the lead decays to the next lane down — Hot to Warm, Warm to Cold, Cold to closed.
 * Entering a lane restarts its counter at one, so a promoted lead gets the whole sequence
 * rather than resuming somewhere in the middle of it.</p>
 *
 * <p>These live in the database rather than in code because the day offsets are the numbers
 * most worth tuning once real conversion data arrives, and tuning them should not require a
 * redeploy.</p>
 */
@Entity
@Table(name = "ladder_steps", uniqueConstraints = @UniqueConstraint(
        name = "uk_ladder_grade_step", columnNames = {"grade", "step_no"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LadderStep {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "grade", length = 8, nullable = false)
    private Grade grade;

    /** 1-based position within the lane. */
    @Column(name = "step_no", nullable = false)
    private Integer stepNo;

    /** Days after the lead entered this grade that the step falls due. */
    @Column(name = "day_offset", nullable = false)
    private Integer dayOffset;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "action", length = 1000)
    private String action;

    /**
     * True when the step may be sent by the scheduler with no counsellor involved. Only the
     * cold-lane broadcasts qualify today; everything else is scheduled by the system and sent
     * by a person, which is what keeps the messages feeling human.
     */
    @Column(name = "auto_send")
    private Boolean autoSend;

    @Column(name = "active")
    private Boolean active;

    public boolean isAutoSend() { return Boolean.TRUE.equals(autoSend); }

    public boolean isActive() { return active == null || active; }
}
