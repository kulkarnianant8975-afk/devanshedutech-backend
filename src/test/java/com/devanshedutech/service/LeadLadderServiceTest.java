package com.devanshedutech.service;

import com.devanshedutech.model.LadderStep;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.crm.Grade;
import com.devanshedutech.model.crm.LostReason;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.LadderStepRepository;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The follow-up ladder and, more importantly, its guards.
 *
 * <p>A decay engine is easy to write and easy to get catastrophically wrong: the failure is
 * silent, it happens overnight, and it destroys exactly the leads worth most. These tests are
 * mostly about the cases where the ladder must refuse to act.</p>
 */
class LeadLadderServiceTest {

    private LeadRepository leads;
    private LeadActivityRepository activities;
    private LadderStepRepository steps;
    private LeadLadderService ladder;

    private static final int GRACE = 3;

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        activities = mock(LeadActivityRepository.class);
        steps = mock(LadderStepRepository.class);
        when(leads.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.save(any())).thenAnswer(i -> i.getArgument(0));
        when(activities.countRealTouches(any())).thenReturn(5L);

        // The Hot lane, as seeded: days 0, 0, 1, 2, 3, 5, 7.
        when(steps.findByGradeOrderByStepNoAsc(Grade.HOT)).thenReturn(lane(Grade.HOT, 0, 0, 1, 2, 3, 5, 7));
        when(steps.findByGradeOrderByStepNoAsc(Grade.WARM)).thenReturn(lane(Grade.WARM, 0, 1, 3, 5, 8, 12, 18));
        when(steps.findByGradeOrderByStepNoAsc(Grade.COLD)).thenReturn(lane(Grade.COLD, 0, 14, 30, 45, 60, 75, 90));

        ladder = new LeadLadderService(leads, activities, steps,
                new LeadLifecycleService(leads, activities, TestCalendars.openEveryDay()));
        ReflectionTestUtils.setField(ladder, "graceDays", GRACE);
        ReflectionTestUtils.setField(ladder, "replyFreezeHours", 48);
        ReflectionTestUtils.setField(ladder, "workedThreshold", 3);
    }

    private List<LadderStep> lane(Grade grade, int... offsets) {
        java.util.List<LadderStep> out = new java.util.ArrayList<>();
        for (int i = 0; i < offsets.length; i++) {
            out.add(LadderStep.builder()
                    .id(UUID.randomUUID().toString()).grade(grade).stepNo(i + 1)
                    .dayOffset(offsets[i]).title("Step " + (i + 1)).active(true).autoSend(false)
                    .build());
        }
        return out;
    }

    private Lead graded(Grade grade, int daysInLane) {
        return Lead.builder()
                .id("l1").fullName("Rohit Deshmukh").stage(Stage.CONTACTED)
                .grade(grade).ladderStep(1)
                .gradeEnteredAt(LocalDateTime.now().minusDays(daysInLane))
                .createdAt(LocalDateTime.now().minusDays(daysInLane))
                .callAttempts(0).updatesOnly(false).optedOut(false).lostUnworked(false)
                .build();
    }

    // ---------------- advancing ----------------

    @Test
    @DisplayName("the ladder advances to whichever step the calendar has reached")
    void advancesToTheDueStep() {
        Lead l = graded(Grade.HOT, 3);   // day 3 is step 5 in the Hot lane
        var result = ladder.advance(l, LocalDate.now());

        assertTrue(result.isPresent());
        assertEquals("step", result.get().action());
        assertEquals(5, l.getLadderStep());
        assertEquals(LocalDate.now(), l.getNextTouchOn());
    }

    @Test
    @DisplayName("nothing happens on a day with no step due")
    void doesNothingWhenNotDue() {
        Lead l = graded(Grade.WARM, 2);
        l.setLadderStep(3);              // day 3's step, already reached
        assertTrue(ladder.advance(l, LocalDate.now()).isEmpty());
    }

    @Test
    @DisplayName("a next touch already booked for the future is not overwritten")
    void doesNotStampOverAFutureCommitment() {
        Lead l = graded(Grade.HOT, 3);
        LocalDate promised = LocalDate.now().plusDays(2);
        l.setNextTouchOn(promised);

        ladder.advance(l, LocalDate.now());
        assertEquals(promised, l.getNextTouchOn(),
                "a date a counsellor agreed with the student outranks the schedule");
    }

    @Test
    @DisplayName("an ungraded lead is never put on a ladder")
    void ungradedLeadsAreLeftAlone() {
        Lead l = graded(Grade.HOT, 30);
        l.setGrade(null);
        assertTrue(ladder.advance(l, LocalDate.now()).isEmpty());
        verify(leads, never()).save(any());
    }

    // ---------------- decay ----------------

    @Test
    @DisplayName("finishing the Hot lane without converting drops the lead to Warm")
    void hotDecaysToWarm() {
        Lead l = graded(Grade.HOT, 7 + GRACE);
        var result = ladder.advance(l, LocalDate.now());

        assertEquals("demoted", result.orElseThrow().action());
        assertEquals(Grade.WARM, l.getGrade());
        assertEquals(1, l.getLadderStep(), "the new lane restarts at step one");
    }

    @Test
    @DisplayName("finishing the Warm lane without converting drops the lead to Cold")
    void warmDecaysToCold() {
        Lead l = graded(Grade.WARM, 18 + GRACE);
        var result = ladder.advance(l, LocalDate.now());

        assertEquals("demoted", result.orElseThrow().action());
        assertEquals(Grade.COLD, l.getGrade());
        assertEquals(1, l.getLadderStep(), "the new lane restarts at step one");
    }

    @Test
    @DisplayName("one lead walks the whole way down: Hot to Warm to Cold to Lost")
    void theWholeChainRunsEndToEnd() {
        // Each link is covered on its own above. This is the chain itself, which is the thing
        // that was actually asked for — and the failure it guards against is a lane that does
        // not restart its clock on demotion, which would drop a lead from Hot straight to Lost
        // in a single pass while every individual test still passed.
        // Nobody ever rings this student, which is the case worth following all the way down.
        // The shared fixture stubs a well-worked lead, so this has to be said explicitly.
        when(activities.countRealTouches(any())).thenReturn(0L);
        Lead l = graded(Grade.HOT, 7 + GRACE);

        assertEquals("demoted-untouched", ladder.advance(l, LocalDate.now()).orElseThrow().action());
        assertEquals(Grade.WARM, l.getGrade());

        // Nothing further happens until the new lane has had its own time to run.
        assertTrue(ladder.advance(l, LocalDate.now()).isEmpty(),
                "a freshly demoted lead is not demoted again the same day");

        l.setGradeEnteredAt(LocalDateTime.now().minusDays(18 + GRACE));
        assertEquals("demoted-untouched", ladder.advance(l, LocalDate.now()).orElseThrow().action());
        assertEquals(Grade.COLD, l.getGrade());

        l.setGradeEnteredAt(LocalDateTime.now().minusDays(90 + GRACE));
        ladder.advance(l, LocalDate.now());

        assertEquals(Stage.LOST, l.getStage(), "the end of the road is Lost");
        assertEquals(Grade.COLD, l.getGrade(),
                "and it stays Cold — a closed lead keeps the grade it was closed at");
        assertEquals(Boolean.TRUE, l.getLostUnworked(),
                "never contacted once on the way down, so this is a follow-up failure");
    }

    @Test
    @DisplayName("the end of the Cold lane closes the lead but never deletes it")
    void coldClosesTheLead() {
        Lead l = graded(Grade.COLD, 90 + GRACE);
        var result = ladder.advance(l, LocalDate.now());

        assertEquals("lost", result.orElseThrow().action());
        assertEquals(Stage.LOST, l.getStage());
        assertEquals(LostReason.NO_RESPONSE, l.getLostReason());
        assertEquals(Grade.COLD, l.getGrade(), "the grade is history, not something to erase");
        verify(leads, never()).deleteById(any());
    }

    // ---------------- the guards ----------------

    @Test
    @DisplayName("a booked demo outranks the ladder — this is the expensive bug")
    void aLiveConversationBlocksDecay() {
        Lead l = graded(Grade.HOT, 7 + GRACE);
        l.setStage(Stage.DEMO_BOOKED);

        var result = ladder.advance(l, LocalDate.now());
        assertEquals("held", result.orElseThrow().action());
        assertEquals(Grade.HOT, l.getGrade(), "demoting someone the day before they pay is the bug");
    }

    @Test
    void feeDiscussionAlsoBlocksDecay() {
        Lead l = graded(Grade.WARM, 18 + GRACE);
        l.setStage(Stage.FEE_DISCUSSION);
        assertEquals("held", ladder.advance(l, LocalDate.now()).orElseThrow().action());
        assertEquals(Grade.WARM, l.getGrade());
    }

    @Test
    @DisplayName("a student who replied yesterday is not demoted today")
    void arecentReplyFreezesTheClock() {
        Lead l = graded(Grade.HOT, 7 + GRACE);
        l.setLastInboundAt(LocalDateTime.now().minusHours(5));

        assertEquals("held", ladder.advance(l, LocalDate.now()).orElseThrow().action());
        assertEquals(Grade.HOT, l.getGrade());
    }

    @Test
    @DisplayName("an old reply does not freeze it forever")
    void anOldReplyDoesNotBlock() {
        Lead l = graded(Grade.HOT, 7 + GRACE);
        l.setLastInboundAt(LocalDateTime.now().minusHours(72));
        assertEquals("demoted", ladder.advance(l, LocalDate.now()).orElseThrow().action());
    }

    @Test
    @DisplayName("a paused lead is skipped entirely")
    void pausedLeadsAreSkipped() {
        Lead l = graded(Grade.HOT, 30);
        l.setLadderPausedUntil(LocalDate.now().plusDays(10));
        l.setLadderPauseReason("Exams until the 5th");

        assertTrue(ladder.advance(l, LocalDate.now()).isEmpty());
        assertEquals(Grade.HOT, l.getGrade());
    }

    @Test
    void anExpiredPauseNoLongerBlocks() {
        Lead l = graded(Grade.HOT, 30);
        l.setLadderPausedUntil(LocalDate.now().minusDays(1));
        assertTrue(ladder.advance(l, LocalDate.now()).isPresent());
    }

    @Test
    @DisplayName("a closed lead is not touched again")
    void closedLeadsAreLeftAlone() {
        Lead l = graded(Grade.WARM, 60);
        l.setStage(Stage.ENROLLED);
        assertTrue(ladder.advance(l, LocalDate.now()).isEmpty());
    }

    // ---------------- accountability ----------------

    @Test
    @DisplayName("a lead closed without ever being worked is flagged, not filed as a rejection")
    void unworkedLeadsAreCalledOut() {
        when(activities.countRealTouches(any())).thenReturn(1L);
        Lead l = graded(Grade.COLD, 90 + GRACE);

        var result = ladder.advance(l, LocalDate.now());
        assertEquals("lost-unworked", result.orElseThrow().action());
        assertTrue(l.getLostUnworked());
        assertTrue(l.getLostNote().contains("not worked"));
    }

    @Test
    void aProperlyWorkedLossIsNotFlagged() {
        when(activities.countRealTouches(any())).thenReturn(9L);
        Lead l = graded(Grade.COLD, 90 + GRACE);

        assertEquals("lost", ladder.advance(l, LocalDate.now()).orElseThrow().action());
        assertFalse(l.getLostUnworked());
    }

    @Test
    @DisplayName("a demotion with no contact at all is recorded as a follow-up failure")
    void untouchedDemotionIsRecorded() {
        when(activities.countRealTouches(any())).thenReturn(0L);
        Lead l = graded(Grade.HOT, 7 + GRACE);

        assertEquals("demoted-untouched", ladder.advance(l, LocalDate.now()).orElseThrow().action());
    }

    // ---------------- reading ----------------

    @Test
    void progressDescribesTheLane() {
        when(steps.countByGrade(Grade.WARM)).thenReturn(7L);
        Lead l = graded(Grade.WARM, 5);
        l.setLadderStep(4);

        var p = ladder.progress(l);
        assertEquals(true, p.get("onLadder"));
        assertEquals(4, p.get("step"));
        assertEquals(7, p.get("total"));
        assertEquals("Step 4", p.get("currentTitle"));
        assertEquals("Step 5", p.get("nextTitle"));
    }

    @Test
    void progressSaysSoWhenThereIsNoLadder() {
        Lead l = graded(Grade.WARM, 1);
        l.setGrade(null);
        assertEquals(false, ladder.progress(l).get("onLadder"));
    }
}
