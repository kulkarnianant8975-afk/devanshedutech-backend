package com.devanshedutech.service;

import com.devanshedutech.model.Demo;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.OutcomeCode;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.DemoRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.service.LeadLifecycleService.Actor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Booking demos and recording who turned up.
 *
 * <p>Marking attendance is the important half. It moves the lead's stage, schedules the day-one
 * and day-three follow-ups the playbook says everyone forgets, and — because it is recorded
 * rather than inferred — makes the enquiry-to-demo and demo-to-enrolment rates real numbers
 * instead of estimates.</p>
 */
@Slf4j
@Service
public class DemoService {

    private final DemoRepository demoRepository;
    private final LeadRepository leadRepository;
    private final LeadLifecycleService lifecycle;

    public DemoService(DemoRepository demoRepository,
                       LeadRepository leadRepository,
                       LeadLifecycleService lifecycle) {
        this.demoRepository = demoRepository;
        this.leadRepository = leadRepository;
        this.lifecycle = lifecycle;
    }

    @Transactional
    public Demo book(Lead lead, LocalDateTime when, String mode, Actor actor) {
        if (when == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a date and time for the demo.");
        }
        if (when.isBefore(LocalDateTime.now().minusHours(1))) {
            // A little slack for someone recording a demo that just happened, but not a booking
            // made for last week, which is almost always a typo in the year or month.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That date is in the past. Book the demo for when it will actually happen.");
        }

        Demo demo = demoRepository.save(Demo.builder()
                .id(UUID.randomUUID().toString())
                .leadId(lead.getId())
                .studentName(lead.getFullName())
                .course(lead.getCourseInterested())
                .scheduledAt(when)
                .mode(mode == null || mode.isBlank() ? "Demo class" : mode.trim())
                .createdById(actor == null ? null : actor.id())
                .createdAt(LocalDateTime.now())
                .build());

        lifecycle.moveStage(lead, Stage.DEMO_BOOKED, "Demo booked", actor);
        lifecycle.log(lead, ActivityType.DEMO, null, Direction.INTERNAL, "Demo booked",
                demo.getMode() + " on " + when.toLocalDate() + " at " + when.toLocalTime()
                        + ". Confirm it in writing so the student has the detail.", actor);
        lifecycle.setNextTouch(lead, when.toLocalDate(), "Demo — " + demo.getMode());
        leadRepository.save(lead);

        return demo;
    }

    /**
     * Records whether the student turned up.
     *
     * <p>Attending applies the SOP's post-demo rules through the same path as any other outcome,
     * so the day-one and day-three follow-ups are booked exactly as they would be if a counsellor
     * had logged it by hand. A no-show is not a loss: the lead keeps its stage and gets a
     * same-day recovery touch, because missing a class is usually logistics, not rejection.</p>
     */
    @Transactional
    public Demo mark(Demo demo, boolean attended, String feedback, Actor actor) {
        if (demo.getAttended() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This demo has already been marked as "
                    + (demo.getAttended() ? "attended" : "a no-show") + ".");
        }
        Lead lead = leadRepository.findById(demo.getLeadId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That lead no longer exists."));

        demo.setAttended(attended);
        demo.setFeedback(feedback);
        demo.setMarkedAt(LocalDateTime.now());
        demoRepository.save(demo);

        if (attended) {
            lifecycle.applyOutcome(lead, OutcomeCode.DEMO_ATTENDED, feedback, null, actor);
        } else {
            lifecycle.log(lead, ActivityType.DEMO, null, Direction.INTERNAL, "Demo — no-show",
                    "The student did not attend. Missing a class is usually logistics rather than "
                    + "rejection, so the stage is unchanged and a same-day recovery touch is booked.",
                    actor);
            lifecycle.setNextTouch(lead, LocalDate.now(),
                    "Missed the demo — offer the next available slot");
            if (lead.getGrade() == null) {
                lifecycle.moveGrade(lead, Grade.WARM, "Booked a demo but did not attend", actor);
            }
            leadRepository.save(lead);
        }
        return demo;
    }

    @Transactional
    public void cancel(Demo demo, String reason, Actor actor) {
        Lead lead = leadRepository.findById(demo.getLeadId()).orElse(null);
        demoRepository.delete(demo);
        if (lead != null) {
            lifecycle.log(lead, ActivityType.DEMO, null, Direction.INTERNAL, "Demo cancelled",
                    (reason == null || reason.isBlank() ? "No reason given." : reason)
                    + " The lead stays in the pipeline.", actor);
            if (lead.getStage() == Stage.DEMO_BOOKED) {
                lifecycle.moveStage(lead, Stage.CONTACTED, "Demo cancelled", actor);
            }
            lifecycle.setNextTouch(lead, LocalDate.now().plusDays(1), "Rebook the demo");
            leadRepository.save(lead);
        }
    }

    public List<Demo> forLead(String leadId) {
        return demoRepository.findByLeadIdOrderByScheduledAtDesc(leadId);
    }

    public List<Demo> between(LocalDate from, LocalDate to) {
        return demoRepository.findScheduledBetween(from.atStartOfDay(), to.plusDays(1).atStartOfDay());
    }

    /** Demos whose time has passed that nobody has marked. These are where the data rots. */
    public List<Demo> awaitingMarking() {
        return demoRepository.findAwaitingMarking(LocalDateTime.now());
    }
}
