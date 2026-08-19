package com.devanshedutech.service;

import com.devanshedutech.model.LadderStep;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.LostReason;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.LadderStepRepository;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The follow-up ladder: seven steps per grade, and a decay to the next lane when they run out.
 *
 * <p>This is the part of the system that plugs the leak both source documents describe. A
 * counsellor is never asked to remember that day five is the proof touch or that a warm lead
 * has gone quiet for three weeks; the ladder advances on the calendar and puts the work in
 * front of them.</p>
 *
 * <p>The guards matter as much as the schedule. Decay is refused while a demo or fee discussion
 * is live, while a student's reply is recent, and while the lead is paused by hand. Without
 * those, a machine designed to stop leads being forgotten becomes a machine that quietly
 * discards the most valuable ones.</p>
 */
@Slf4j
@Service
public class LeadLadderService {

    private final LeadRepository leadRepository;
    private final LeadActivityRepository activityRepository;
    private final LadderStepRepository ladderRepository;
    private final LeadLifecycleService lifecycle;

    /** Days of slack after the final step before a lead decays a lane. */
    @Value("${app.crm.ladder.grace-days:3}")
    private int graceDays;

    /** A recent reply freezes the ladder: never decay somebody who just spoke to you. */
    @Value("${app.crm.ladder.reply-freeze-hours:48}")
    private int replyFreezeHours;

    /** Below this many real touches, a closed lead is flagged as never actually worked. */
    @Value("${app.crm.ladder.worked-threshold:3}")
    private int workedThreshold;

    public LeadLadderService(LeadRepository leadRepository,
                             LeadActivityRepository activityRepository,
                             LadderStepRepository ladderRepository,
                             LeadLifecycleService lifecycle) {
        this.leadRepository = leadRepository;
        this.activityRepository = activityRepository;
        this.ladderRepository = ladderRepository;
        this.lifecycle = lifecycle;
    }

    /** What the pass decided about one lead. */
    public record LadderOutcome(String leadId, String action, String detail) {}

    // ==================================================================
    // The daily pass
    // ==================================================================

    /**
     * Moves one lead along its lane, or decays it at the end of one.
     *
     * @return what changed, or empty when nothing did
     */
    @Transactional
    public Optional<LadderOutcome> advance(Lead lead, LocalDate today) {
        if (lead.getGrade() == null || !lead.isActive()) {
            // An ungraded lead is not on a ladder at all. That is deliberate: grading is a
            // counsellor's judgement, and a machine should not start a follow-up sequence
            // against a student nobody has assessed.
            return Optional.empty();
        }
        if (lead.isLadderPaused(today)) {
            return Optional.empty();
        }
        if (lead.getGradeEnteredAt() == null) {
            lead.setGradeEnteredAt(lead.getCreatedAt() == null ? LocalDateTime.now() : lead.getCreatedAt());
        }

        List<LadderStep> lane = activeLane(lead.getGrade());
        if (lane.isEmpty()) {
            return Optional.empty();
        }

        long days = ChronoUnit.DAYS.between(lead.getGradeEnteredAt().toLocalDate(), today);
        LadderStep last = lane.get(lane.size() - 1);

        if (days >= (long) last.getDayOffset() + graceDays) {
            return decay(lead, lane);
        }

        LadderStep due = dueStep(lane, days);
        int current = nz(lead.getLadderStep());
        if (due == null || due.getStepNo() <= current) {
            return Optional.empty();
        }

        lead.setLadderStep(due.getStepNo());
        if (lead.getNextTouchOn() == null || lead.getNextTouchOn().isBefore(today)) {
            lifecycle.setNextTouch(lead, today, due.getTitle());
        }
        lifecycle.log(lead, ActivityType.SYSTEM, null, Direction.INTERNAL,
                "Follow-up due — step " + due.getStepNo() + " of " + lane.size(),
                due.getTitle() + (due.getAction() == null ? "" : ". " + due.getAction()),
                Actor.system());
        leadRepository.save(lead);

        return Optional.of(new LadderOutcome(lead.getId(), "step",
                "Step " + due.getStepNo() + " — " + due.getTitle()));
    }

    /**
     * Drops a lead one lane, or closes it if it has run out of lanes. Every guard here exists
     * because decaying the wrong lead costs more than decaying late.
     */
    private Optional<LadderOutcome> decay(Lead lead, List<LadderStep> lane) {
        String blocked = decayBlockedBecause(lead);
        if (blocked != null) {
            return Optional.of(new LadderOutcome(lead.getId(), "held", blocked));
        }

        Grade from = lead.getGrade();
        Grade to = from.demoteTo();
        long touches = activityRepository.countRealTouches(lead.getId());

        if (to != null) {
            lifecycle.moveGrade(lead, to, "Completed all " + lane.size() + " " + from.getLabel()
                    + " steps without converting", Actor.system());
            if (touches == 0) {
                lifecycle.log(lead, ActivityType.SYSTEM, null, Direction.INTERNAL,
                        "Decayed without ever being contacted",
                        "This lead moved from " + from.getLabel() + " to " + to.getLabel()
                        + " without a single recorded call or message. That is a follow-up failure, "
                        + "not a student who said no.", Actor.system());
            }
            leadRepository.save(lead);
            return Optional.of(new LadderOutcome(lead.getId(),
                    touches == 0 ? "demoted-untouched" : "demoted",
                    from.getLabel() + " → " + to.getLabel()));
        }

        // End of the cold lane. The lead is closed but never deleted, because students come
        // back for the next intake — which is exactly what SOP section 6.8 is about.
        boolean unworked = touches < workedThreshold;
        lead.setLostUnworked(unworked);
        lead.setLostReason(LostReason.NO_RESPONSE);
        lead.setLostNote(unworked
                ? "Closed automatically after the full cycle, but only " + touches
                  + " real touches were ever recorded. This lead was not worked."
                : "No response across the full Hot, Warm and Cold cycle.");
        lifecycle.moveStage(lead, Stage.LOST, "Completed the Cold lane with no engagement", Actor.system());
        leadRepository.save(lead);

        return Optional.of(new LadderOutcome(lead.getId(), unworked ? "lost-unworked" : "lost",
                "Closed after the full cycle (" + touches + " touches)"));
    }

    /** The reason decay is refused right now, or null when it may proceed. */
    String decayBlockedBecause(Lead lead) {
        if (lead.getStage() != null && lead.getStage().blocksDecay()) {
            return "Held at " + lead.getGrade().getLabel() + ": the stage is "
                    + lead.getStage().getLabel() + ", and a live conversation outranks the ladder";
        }
        if (lead.getLastInboundAt() != null
                && Duration.between(lead.getLastInboundAt(), LocalDateTime.now()).toHours() < replyFreezeHours) {
            return "Held: the student replied within the last " + replyFreezeHours + " hours";
        }
        return null;
    }

    // ==================================================================
    // Manual control
    // ==================================================================

    @Transactional
    public Lead pause(Lead lead, LocalDate until, String reason, Actor actor) {
        lead.setLadderPausedUntil(until);
        lead.setLadderPauseReason(reason);
        lifecycle.log(lead, ActivityType.SYSTEM, null, Direction.INTERNAL, "Follow-up paused",
                "Paused until " + until + (reason == null || reason.isBlank() ? "" : " — " + reason),
                actor);
        return leadRepository.save(lead);
    }

    @Transactional
    public Lead resume(Lead lead, Actor actor) {
        lead.setLadderPausedUntil(null);
        lead.setLadderPauseReason(null);
        lifecycle.log(lead, ActivityType.SYSTEM, null, Direction.INTERNAL, "Follow-up resumed",
                "The follow-up sequence is running again.", actor);
        return leadRepository.save(lead);
    }

    // ==================================================================
    // Reading
    // ==================================================================

    /** Where this lead sits in its lane, for the pipeline screens. */
    public Map<String, Object> progress(Lead lead) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (lead.getGrade() == null) {
            m.put("onLadder", false);
            m.put("reason", "Not graded yet, so no follow-up sequence has started.");
            return m;
        }
        List<LadderStep> lane = activeLane(lead.getGrade());
        int step = Math.min(nz(lead.getLadderStep()), Math.max(lane.size(), 1));

        m.put("onLadder", true);
        m.put("grade", lead.getGrade().name());
        m.put("step", step);
        m.put("total", lane.size());
        m.put("pausedUntil", lead.getLadderPausedUntil());
        m.put("pauseReason", lead.getLadderPauseReason());
        lane.stream().filter(s -> s.getStepNo() == step).findFirst()
                .ifPresent(s -> m.put("currentTitle", s.getTitle()));
        lane.stream().filter(s -> s.getStepNo() == step + 1).findFirst()
                .ifPresent(s -> m.put("nextTitle", s.getTitle()));
        return m;
    }

    public List<LadderStep> lane(Grade grade) {
        return activeLane(grade);
    }

    public List<LadderStep> allSteps() {
        return ladderRepository.findAllByOrderByGradeAscStepNoAsc();
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private List<LadderStep> activeLane(Grade grade) {
        return ladderRepository.findByGradeOrderByStepNoAsc(grade).stream()
                .filter(LadderStep::isActive).toList();
    }

    /** The furthest step the calendar says is due, or null when none is yet. */
    private LadderStep dueStep(List<LadderStep> lane, long days) {
        LadderStep due = null;
        for (LadderStep s : lane) {
            if (s.getDayOffset() <= days) due = s;
        }
        return due;
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
