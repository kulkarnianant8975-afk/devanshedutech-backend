package com.devanshedutech.service;

import com.devanshedutech.model.Batch;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.ActivityType;
import com.devanshedutech.model.crm.Direction;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.BatchRepository;
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
import java.util.Optional;

/**
 * Enrolling a student, and the referral that should follow.
 *
 * <p>The SOP does not stop at payment: section 7 asks for a welcome, a week-one check-in, and
 * a referral request once the student is happy, because "a happy student is your cheapest
 * source of new leads". Enrolment therefore schedules the follow-up rather than closing the
 * record — an enrolled student who is never asked is a referral the institute did not get.</p>
 */
@Slf4j
@Service
public class EnrolmentService {

    private final LeadRepository leads;
    private final BatchRepository batches;
    private final LeadLifecycleService lifecycle;

    public EnrolmentService(LeadRepository leads, BatchRepository batches,
                            LeadLifecycleService lifecycle) {
        this.leads = leads;
        this.batches = batches;
        this.lifecycle = lifecycle;
    }

    /** The next intake a counsellor can offer this student, if there is one. */
    public Optional<Batch> nextBatchFor(Lead lead) {
        LocalDate today = LocalDate.now();
        if (lead.getCourseId() != null && !lead.getCourseId().isBlank()) {
            List<Batch> forCourse = batches.findUpcomingForCourse(lead.getCourseId(), today);
            if (!forCourse.isEmpty()) return Optional.of(forCourse.get(0));
        }
        // No course match is not the same as no batch. Offering the soonest intake is more
        // useful to a counsellor mid-call than telling them nothing is scheduled.
        return batches.findUpcoming(today).stream().findFirst();
    }

    @Transactional
    public Lead enrol(Lead lead, String batchId, String feePlan, String paymentStatus, Actor actor) {
        if (Boolean.TRUE.equals(lead.getOptedOut())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This student asked not to be contacted. Their record is read-only.");
        }
        Batch batch = batchId == null || batchId.isBlank() ? null
                : batches.findById(batchId).orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "That batch no longer exists."));

        if (batch != null && !batch.isTakingEnrolments()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That batch has already started or closed. Choose the next intake.");
        }

        lead.setBatchId(batch == null ? null : batch.getId());
        lead.setFeePlan(feePlan);
        lead.setPaymentStatus(paymentStatus == null || paymentStatus.isBlank() ? "PENDING" : paymentStatus);
        lead.setEnrolledAt(LocalDateTime.now());

        lifecycle.moveStage(lead, Stage.ENROLLED, "Enrolled", actor);
        lifecycle.log(lead, ActivityType.SYSTEM, null, Direction.INTERNAL, "Enrolled",
                (batch == null ? "No batch chosen yet." : "Joined " + batch.describe() + ".")
                + (feePlan == null || feePlan.isBlank() ? "" : " Fee plan: " + feePlan + ".")
                + " Welcome them, check in after their first week, and ask for a referral once "
                + "they are happy.", actor);

        // moveStage already books the week-one check-in; naming it here makes the intent visible
        // to the counsellor rather than leaving it as a bare date.
        lifecycle.setNextTouch(lead, LocalDate.now().plusDays(7),
                "Week-one check-in, then the referral ask");

        return leads.save(lead);
    }

    /**
     * Records that this student referred somebody, and credits the referrer.
     *
     * <p>Word of mouth is already working at this institute; the playbook's advice is to
     * formalise it. A referral that is not written down cannot be rewarded, and a reward that
     * never arrives stops the referrals.</p>
     */
    @Transactional
    public Lead recordReferral(Lead referrer, Lead referred, Actor actor) {
        if (referrer.getId().equals(referred.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A student cannot refer themselves.");
        }
        referred.setReferredById(referrer.getId());
        leads.save(referred);

        lifecycle.log(referrer, ActivityType.NOTE, null, Direction.INTERNAL, "Referred a friend",
                referred.getFullName() + " was referred by this student. Make sure the referral "
                + "benefit actually reaches them — a reward that never arrives stops the referrals.",
                actor);
        lifecycle.log(referred, ActivityType.NOTE, null, Direction.INTERNAL, "Came by referral",
                "Referred by " + referrer.getFullName() + ". A referred enquiry converts far "
                + "better than a cold one; treat it accordingly.", actor);

        return referred;
    }

    /** Everyone this student has sent, for the referral credit. */
    public List<Lead> referralsBy(String leadId) {
        return leads.findAll().stream()
                .filter(l -> leadId.equals(l.getReferredById()))
                .toList();
    }
}
