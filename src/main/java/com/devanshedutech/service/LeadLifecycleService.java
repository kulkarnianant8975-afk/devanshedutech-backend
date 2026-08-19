package com.devanshedutech.service;

import com.devanshedutech.model.Lead;
import com.devanshedutech.model.LeadActivity;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.LostReason;
import com.devanshedutech.model.crm.OutcomeCode;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The rules of the pipeline, in one place.
 *
 * <p>Recording an outcome applies the consequences the Counsellor SOP prescribes: the stage
 * moves, the next touch is booked, and the extra follow-ups that section 6 demands are
 * scheduled. A counsellor picks what happened; they never have to remember what the SOP says
 * should happen next.</p>
 *
 * <p>Business rules live here and only here, so the pipeline cannot drift between the API, the
 * screens and the reports.</p>
 */
@Slf4j
@Service
public class LeadLifecycleService {

    private static final List<Stage> CLOSED = List.of(Stage.ENROLLED, Stage.LOST);

    /** Words that mean a student is ready to move, per the SOP's Hot signals. */
    private static final List<String> BUYING_SIGNALS = List.of(
            "fee", "fees", "price", "cost", "batch", "admission", "join", "enrol", "enroll",
            "demo", "visit", "seat", "instal", "emi", "start date", "timing");

    private final LeadRepository leadRepository;
    private final LeadActivityRepository activityRepository;

    public LeadLifecycleService(LeadRepository leadRepository,
                                LeadActivityRepository activityRepository) {
        this.leadRepository = leadRepository;
        this.activityRepository = activityRepository;
    }

    /** Who performed an action, for the audit trail. */
    public record Actor(String id, String name) {
        public static Actor system() { return new Actor(null, "System"); }
    }

    // ==================================================================
    // Recording contact
    // ==================================================================

    /**
     * Applies an outcome from SOP section 6: writes the activity, moves the stage, books the
     * next touch, and schedules any prescribed extra follow-ups.
     *
     * @param note   free text; required for outcomes the SOP refuses to leave vague
     * @param reason required when the outcome closes the lead as Lost
     */
    @Transactional
    public Lead applyOutcome(Lead lead, OutcomeCode outcome, String note, LostReason reason, Actor actor) {
        if (outcome == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose what happened on this contact.");
        }
        if (lead.getStage() == Stage.ENROLLED && outcome.isLosing()) {
            // An enrolled student is not a lost lead. Reversing that is a refund, not a stage
            // change, and it must not happen by picking the wrong item from a dropdown.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This student is already enrolled. A cancellation is handled separately, "
                    + "not by marking the lead lost.");
        }
        if (outcome.isRequiresReason() && (note == null || note.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "\"" + outcome.getLabel() + "\" needs a note — write what the student actually said.");
        }
        if (outcome.isLosing() && reason == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Marking a lead lost needs a reason, so the institute can learn from it.");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean isCall = outcome == OutcomeCode.CONNECTED || outcome == OutcomeCode.NO_ANSWER;

        log(lead, isCall ? ActivityType.CALL : ActivityType.WHATSAPP, outcome, Direction.OUTBOUND,
                (isCall ? "Call — " : "Contact — ") + outcome.getLabel(), note, actor);

        if (outcome == OutcomeCode.NO_ANSWER) {
            lead.setCallAttempts(nz(lead.getCallAttempts()) + 1);
        } else if (outcome == OutcomeCode.CONNECTED) {
            // The SOP's three-attempt limit counts consecutive misses, not calls ever placed,
            // so an answered call resets it.
            lead.setCallAttempts(0);
        }
        if (lead.getFirstRespondedAt() == null) {
            lead.setFirstRespondedAt(now);
        }
        lead.setLastTouchAt(now);
        lead.setLastTouchNote(outcome.getLabel() + (note == null || note.isBlank() ? "" : " — " + note));

        if (outcome.getStage() != null) {
            moveStage(lead, outcome.getStage(), "Outcome: " + outcome.getLabel(), actor);
        }
        if (outcome.getGrade() != null) {
            moveGrade(lead, outcome.getGrade(), "Outcome: " + outcome.getLabel(), actor);
        }

        if (outcome.isLosing()) {
            lead.setLostReason(reason);
            lead.setLostNote(note);
            lead.setUpdatesOnly(true);
            lead.setNextTouchOn(null);
            lead.setNextTouchNote(null);
        } else if (outcome.getNextTouchDays() != null) {
            setNextTouch(lead, LocalDate.now().plusDays(outcome.getNextTouchDays()), followUpNoteFor(outcome));
        }

        // The D+1/D+3 pairs the playbook says everyone forgets. The nearest becomes the next
        // touch; the whole commitment is written to the timeline so it is visible.
        int[] extras = outcome.getExtraFollowUpDays();
        if (extras.length > 0 && !outcome.isLosing()) {
            LocalDate first = LocalDate.now().plusDays(extras[0]);
            if (lead.getNextTouchOn() == null || first.isBefore(lead.getNextTouchOn())) {
                setNextTouch(lead, first, followUpNoteFor(outcome));
            }
            String plan = Arrays.stream(extras).mapToObj(d -> "day +" + d)
                    .collect(Collectors.joining(" and "));
            log(lead, ActivityType.SYSTEM, null, Direction.INTERNAL, "Follow-ups scheduled",
                    "Because of \"" + outcome.getLabel() + "\", follow-ups are booked for " + plan + ".",
                    Actor.system());
        }

        return leadRepository.save(lead);
    }

    private String followUpNoteFor(OutcomeCode outcome) {
        return switch (outcome) {
            case NO_ANSWER -> "Call again at a different hour — evenings work best for students";
            case THINKING -> "Follow up on the reason they gave";
            case PARENTS -> "Check whether the parents have seen the summary";
            case FEE_OBJECTION -> "Instalment options and the free demo";
            case COMPARING -> "Follow up after the competitor's deadline";
            case DEMO_BOOKED -> "Demo reminder";
            case DEMO_ATTENDED -> "How did the demo feel? Offer to reserve the seat";
            case READY_TO_ENROL -> "Week-one check-in, then the referral ask";
            case CONNECTED -> "Next step agreed on the call";
            default -> "Follow up";
        };
    }

    /**
     * Records a message received from the student, and applies the SOP's promotion rule: a cold
     * lead who re-engages is warm again, one asking about fees or batch dates is hot, and a lost
     * lead who comes back is reopened rather than left closed.
     */
    @Transactional
    public Lead recordInbound(Lead lead, String text, Actor actor) {
        LocalDateTime now = LocalDateTime.now();
        lead.setLastInboundAt(now);
        lead.setLastTouchAt(now);
        lead.setLastTouchNote("Student replied");

        log(lead, ActivityType.WHATSAPP, null, Direction.INBOUND, "Message from student", text, actor);

        if (lead.getStage() == Stage.LOST) {
            // They were kept rather than deleted precisely so this could happen.
            moveStage(lead, Stage.CONTACTED, "Student re-engaged after being marked lost", Actor.system());
            lead.setUpdatesOnly(false);
            lead.setLostReason(null);
            moveGrade(lead, Grade.WARM, "Re-engaged after loss", Actor.system());
        } else if (looksLikeBuyingSignal(text)) {
            moveGrade(lead, Grade.HOT, "Asked about fees, batch dates or a demo", Actor.system());
        } else if (lead.getGrade() == Grade.COLD) {
            moveGrade(lead, Grade.WARM, "Replied to an update", Actor.system());
        }

        if (lead.getNextTouchOn() == null && lead.isActive()) {
            setNextTouch(lead, LocalDate.now(), "Student replied — respond today");
        }
        return leadRepository.save(lead);
    }

    private boolean looksLikeBuyingSignal(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return BUYING_SIGNALS.stream().anyMatch(t::contains);
    }

    // ==================================================================
    // Transitions
    // ==================================================================

    /** Moves the stage and records why, so no stage ever changes invisibly. */
    @Transactional
    public Lead moveStage(Lead lead, Stage next, String why, Actor actor) {
        Stage prev = lead.getStage();
        if (prev == next) return lead;

        lead.setStage(next);
        lead.setStatus(next.getLabel()); // keeps the legacy column readable
        // The stage is recorded structurally as well as in the readable text, so funnel
        // conversion is computed from what actually happened rather than from where the lead
        // happens to sit now. Written in the same insert rather than a follow-up update.
        logStageChange(lead, prev, next, why, actor);

        if (next == Stage.ENROLLED) {
            setNextTouch(lead, LocalDate.now().plusDays(7), "Week-one check-in, then the referral ask");
        } else if (next == Stage.LOST) {
            lead.setNextTouchOn(null);
            lead.setNextTouchNote(null);
            lead.setUpdatesOnly(true);
        }
        return lead;
    }

    /** Moves the lead between Hot, Warm and Cold, recording when and why. */
    @Transactional
    public Lead moveGrade(Lead lead, Grade next, String why, Actor actor) {
        Grade prev = lead.getGrade();
        if (prev == next) return lead;

        lead.setGrade(next);
        lead.setGradeEnteredAt(LocalDateTime.now());
        // Entering a lane always restarts it, so a promoted lead gets the whole sequence
        // rather than resuming halfway through somebody else's schedule.
        lead.setLadderStep(1);
        log(lead, ActivityType.GRADE_CHANGE, null, Direction.INTERNAL, "Grade changed",
                (prev == null ? "Ungraded" : prev.getLabel()) + " → " + next.getLabel()
                        + (why == null ? "" : " — " + why), actor);

        // A hot lead must be called today; the SOP is explicit about reacting fast.
        if (next == Grade.HOT && lead.isActive()
                && (lead.getNextTouchOn() == null || lead.getNextTouchOn().isAfter(LocalDate.now()))) {
            setNextTouch(lead, LocalDate.now(), "Graded Hot — call today");
        }
        return lead;
    }

    @Transactional
    public Lead assign(Lead lead, String userId, String userName, Actor actor) {
        lead.setAssignedToId(userId);
        lead.setAssignedAt(userId == null ? null : LocalDateTime.now());
        log(lead, ActivityType.ASSIGNMENT, null, Direction.INTERNAL, "Lead assigned",
                userId == null ? "Owner cleared" : "Owner set to " + (userName == null ? userId : userName),
                actor);
        return leadRepository.save(lead);
    }

    /** Sets the date the SOP refuses to leave blank. */
    public void setNextTouch(Lead lead, LocalDate when, String note) {
        lead.setNextTouchOn(when);
        lead.setNextTouchNote(note);
    }

    @Transactional
    public Lead optOut(Lead lead, Actor actor) {
        lead.setOptedOut(true);
        lead.setOptedOutAt(LocalDateTime.now());
        lead.setUpdatesOnly(false);
        lead.setNextTouchOn(null);
        lead.setNextTouchNote(null);
        log(lead, ActivityType.SYSTEM, null, Direction.INTERNAL, "Opted out",
                "Student asked to stop receiving messages. Excluded from every follow-up and broadcast.",
                actor);
        return leadRepository.save(lead);
    }

    // ==================================================================
    // Activity log
    // ==================================================================

    /** A stage transition, with the destination stored in its own column for reporting. */
    private LeadActivity logStageChange(Lead lead, Stage prev, Stage next, String why, Actor actor) {
        Actor who = actor == null ? Actor.system() : actor;
        return activityRepository.save(LeadActivity.builder()
                .id(UUID.randomUUID().toString())
                .leadId(lead.getId())
                .type(ActivityType.STAGE_CHANGE)
                .direction(Direction.INTERNAL)
                .stageTo(next)
                .summary("Stage changed")
                .detail((prev == null ? "—" : prev.getLabel()) + " → " + next.getLabel()
                        + (why == null ? "" : " (" + why + ")"))
                .createdById(who.id())
                .createdByName(who.name())
                .createdAt(LocalDateTime.now())
                .build());
    }

    public LeadActivity log(Lead lead, ActivityType type, OutcomeCode outcome, Direction direction,
                            String summary, String detail, Actor actor) {
        Actor who = actor == null ? Actor.system() : actor;
        return activityRepository.save(LeadActivity.builder()
                .id(UUID.randomUUID().toString())
                .leadId(lead.getId())
                .type(type)
                .outcome(outcome)
                .direction(direction)
                .summary(summary)
                .detail(detail)
                .createdById(who.id())
                .createdByName(who.name())
                .createdAt(LocalDateTime.now())
                .build());
    }

    public List<LeadActivity> timeline(String leadId) {
        return activityRepository.findByLeadIdOrderByCreatedAtDesc(leadId);
    }

    public List<Stage> closedStages() { return CLOSED; }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
