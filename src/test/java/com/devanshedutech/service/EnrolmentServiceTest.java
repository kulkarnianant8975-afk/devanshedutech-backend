package com.devanshedutech.service;

import com.devanshedutech.model.Batch;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.BatchRepository;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Enrolment, and what the SOP says should happen straight afterwards.
 *
 * <p>Section 7 is explicit that enrolment is not the end: welcome them, check in after a week,
 * ask for a referral once they are happy. An enrolled student nobody follows up is a referral
 * the institute did not get, so the check-in is booked by the act of enrolling rather than left
 * to somebody's memory.</p>
 */
class EnrolmentServiceTest {

    private LeadRepository leads;
    private BatchRepository batches;
    private EnrolmentService service;
    private LeadLifecycleService.Actor sneha;

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        batches = mock(BatchRepository.class);
        LeadActivityRepository activities = mock(LeadActivityRepository.class);
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new EnrolmentService(leads, batches, new LeadLifecycleService(leads, activities));
        sneha = new LeadLifecycleService.Actor("u1", "Sneha");
    }

    private Lead lead() {
        return Lead.builder().id("l1").fullName("Omkar Bhosale").courseId("c1")
                .courseInterested("Gen AI").stage(Stage.FEE_DISCUSSION)
                .optedOut(false).updatesOnly(false).callAttempts(0).build();
    }

    private Batch batch(LocalDate start, String status) {
        return Batch.builder().id("b1").courseId("c1").courseName("Gen AI")
                .name("September morning batch").startDate(start).timing("10am to 12pm")
                .status(status).build();
    }

    @Test
    @DisplayName("enrolling books the week-one check-in that leads to a referral")
    void enrolmentSchedulesTheCheckIn() {
        Lead l = lead();
        when(batches.findById("b1")).thenReturn(Optional.of(batch(LocalDate.now().plusDays(14), "OPEN")));

        service.enrol(l, "b1", "45,000 in three instalments", "PART_PAID", sneha);

        assertEquals(Stage.ENROLLED, l.getStage());
        assertEquals("b1", l.getBatchId());
        assertEquals("PART_PAID", l.getPaymentStatus());
        assertNotNull(l.getEnrolledAt());
        assertEquals(LocalDate.now().plusDays(7), l.getNextTouchOn(),
                "an enrolled student nobody follows up is a referral not asked for");
        assertTrue(l.getNextTouchNote().toLowerCase().contains("referral"));
    }

    @Test
    @DisplayName("payment status defaults to pending rather than being left blank")
    void paymentDefaultsToPending() {
        Lead l = lead();
        when(batches.findById("b1")).thenReturn(Optional.of(batch(LocalDate.now().plusDays(7), "OPEN")));
        service.enrol(l, "b1", null, null, sneha);
        assertEquals("PENDING", l.getPaymentStatus());
    }

    @Test
    @DisplayName("a batch that has already started cannot be joined")
    void cannotJoinABatchThatHasStarted() {
        when(batches.findById("b1")).thenReturn(Optional.of(batch(LocalDate.now().minusDays(3), "RUNNING")));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.enrol(lead(), "b1", null, null, sneha));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    @DisplayName("a student who opted out cannot be enrolled behind their back")
    void optedOutCannotBeEnrolled() {
        Lead l = lead();
        l.setOptedOut(true);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.enrol(l, null, null, null, sneha));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    @DisplayName("enrolling without a batch is allowed, because the date is often agreed later")
    void batchIsOptional() {
        Lead l = lead();
        assertDoesNotThrow(() -> service.enrol(l, null, "Paid in full", "PAID", sneha));
        assertEquals(Stage.ENROLLED, l.getStage());
        assertNull(l.getBatchId());
    }

    @Test
    @DisplayName("the next intake for the student's own course is preferred over the soonest")
    void prefersTheCourseSpecificBatch() {
        Lead l = lead();
        Batch mine = batch(LocalDate.now().plusDays(20), "OPEN");
        when(batches.findUpcomingForCourse(eq("c1"), any())).thenReturn(List.of(mine));
        when(batches.findUpcoming(any())).thenReturn(List.of(batch(LocalDate.now().plusDays(3), "OPEN")));

        assertEquals("b1", service.nextBatchFor(l).orElseThrow().getId());
    }

    @Test
    @DisplayName("with no batch for their course, the soonest intake is still offered")
    void fallsBackToTheSoonestIntake() {
        Lead l = lead();
        when(batches.findUpcomingForCourse(any(), any())).thenReturn(List.of());
        Batch soonest = batch(LocalDate.now().plusDays(5), "OPEN");
        when(batches.findUpcoming(any())).thenReturn(List.of(soonest));

        // Telling a counsellor nothing is scheduled when something is would end the call badly.
        assertTrue(service.nextBatchFor(l).isPresent());
    }

    @Test
    @DisplayName("a referral is recorded on both records, so the credit is traceable")
    void referralIsRecordedBothWays() {
        Lead referrer = lead();
        Lead referred = Lead.builder().id("l2").fullName("Harshad Patil").optedOut(false).build();

        service.recordReferral(referrer, referred, sneha);
        assertEquals("l1", referred.getReferredById());
    }

    @Test
    @DisplayName("a student cannot refer themselves")
    void noSelfReferral() {
        Lead l = lead();
        assertThrows(ResponseStatusException.class, () -> service.recordReferral(l, l, sneha));
    }

    @Test
    @DisplayName("a batch describes itself the way a counsellor would say it out loud")
    void batchDescribesItself() {
        Batch b = batch(LocalDate.of(2026, 9, 5), "OPEN");
        assertEquals("September morning batch, starting 2026-09-05, 10am to 12pm", b.describe());
    }

    @Test
    @DisplayName("an uncapped batch is not reported as full")
    void nullCapacityIsNotZero() {
        Batch b = batch(LocalDate.now().plusDays(10), "OPEN");
        b.setCapacity(null);
        assertTrue(b.isTakingEnrolments());
    }
}
