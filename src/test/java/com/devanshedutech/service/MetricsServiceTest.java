package com.devanshedutech.service;

import com.devanshedutech.dto.MetricsDTOs.FunnelStep;
import com.devanshedutech.dto.MetricsDTOs.Metric;
import com.devanshedutech.dto.MetricsDTOs.PipelineMetricsResponse;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.User;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The conversion maths.
 *
 * <p>A dashboard that is subtly wrong is worse than no dashboard: people make decisions on it
 * and never find out. The cases that matter most here are the ones where the obvious
 * implementation gives the wrong answer — counting a funnel from where leads sit today, or
 * printing a confident 0% off a sample of two.</p>
 */
class MetricsServiceTest {

    private LeadRepository leads;
    private LeadActivityRepository activities;
    private UserRepository users;
    private MetricsService metrics;

    @BeforeEach
    void setUp() {
        leads = mock(LeadRepository.class);
        activities = mock(LeadActivityRepository.class);
        users = mock(UserRepository.class);

        when(users.findAll()).thenReturn(List.of());
        when(leads.findAll()).thenReturn(List.of());
        when(activities.maxStageReachedPerLead()).thenReturn(List.of());
        when(activities.countTouchesForLeads(anyList())).thenReturn(List.of());
        when(leads.firstResponseTimestampsSince(any())).thenReturn(List.of());
        when(leads.countGroupedBySource()).thenReturn(List.of());
        when(leads.countEnrolledGroupedBySource()).thenReturn(List.of());
        when(leads.countByCreatedAtBetween(any(), any())).thenReturn(0L);

        metrics = new MetricsService(leads, activities, users);
        ReflectionTestUtils.setField(metrics, "minSample", 5);
    }

    private Object[] row(String id, Stage stage, LeadSource source, String owner) {
        return new Object[]{id, stage, source, owner};
    }

    private void givenLeads(List<Object[]> rows) {
        when(leads.funnelProjection()).thenReturn(rows);
    }

    private Metric metric(PipelineMetricsResponse r, String key) {
        return r.getMetrics().stream().filter(m -> m.getKey().equals(key)).findFirst().orElseThrow();
    }

    private FunnelStep step(PipelineMetricsResponse r, Stage stage) {
        return r.getFunnel().stream().filter(f -> f.getStage().equals(stage.name())).findFirst().orElseThrow();
    }

    // ---------------- the funnel ----------------

    @Test
    @DisplayName("a lead that reached a demo and then went cold still counts towards the demo rate")
    void funnelCountsHistoryNotTheSnapshot() {
        // Six leads. Two are sitting at Lost, but their history shows they got as far as a demo.
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) rows.add(row("l" + i, Stage.CONTACTED, LeadSource.INSTAGRAM_AD, null));
        rows.add(row("gone1", Stage.LOST, LeadSource.INSTAGRAM_AD, null));
        rows.add(row("gone2", Stage.LOST, LeadSource.INSTAGRAM_AD, null));
        givenLeads(rows);
        List<Object[]> history = new ArrayList<>();
        history.add(new Object[]{"gone1", Stage.DEMO_DONE});
        history.add(new Object[]{"gone2", Stage.DEMO_BOOKED});
        when(activities.maxStageReachedPerLead()).thenReturn(history);

        var result = metrics.pipelineMetrics(8);

        assertEquals(2, step(result, Stage.DEMO_BOOKED).getReached(),
                "counting only current stages would report zero demos here");
        assertEquals(1, step(result, Stage.DEMO_DONE).getReached());
        assertEquals(0, step(result, Stage.ENROLLED).getReached());
    }

    @Test
    @DisplayName("a lost lead does not appear at a funnel position of its own")
    void lostIsAnExitNotAStep() {
        givenLeads(List.<Object[]>of(row("l1", Stage.LOST, LeadSource.REFERRAL, null)));
        var result = metrics.pipelineMetrics(8);
        assertTrue(result.getFunnel().stream().noneMatch(f -> f.getStage().equals("LOST")));
    }

    @Test
    @DisplayName("the funnel is monotonic — no stage can show more leads than the one before it")
    void funnelNeverIncreases() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row("a" + i, Stage.CONTACTED, LeadSource.WALK_IN, null));
        for (int i = 0; i < 4; i++) rows.add(row("b" + i, Stage.DEMO_DONE, LeadSource.WALK_IN, null));
        for (int i = 0; i < 2; i++) rows.add(row("c" + i, Stage.ENROLLED, LeadSource.WALK_IN, null));
        givenLeads(rows);

        var funnel = metrics.pipelineMetrics(8).getFunnel();
        for (int i = 1; i < funnel.size(); i++) {
            assertTrue(funnel.get(i).getReached() <= funnel.get(i - 1).getReached(),
                    funnel.get(i).getLabel() + " reports more leads than " + funnel.get(i - 1).getLabel());
        }
    }

    @Test
    @DisplayName("drop-off is measured against the previous stage, not the total")
    void dropIsStageToStage() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row("a" + i, Stage.CONTACTED, LeadSource.WALK_IN, null));
        for (int i = 0; i < 5; i++) rows.add(row("b" + i, Stage.DEMO_BOOKED, LeadSource.WALK_IN, null));
        givenLeads(rows);

        var result = metrics.pipelineMetrics(8);
        assertNull(step(result, Stage.NEW).getDropFromPrevious(), "the first stage has nothing to drop from");
        // 15 reached Contacted, 5 reached Demo booked: a two-thirds drop.
        assertEquals(66.7, step(result, Stage.DEMO_BOOKED).getDropFromPrevious(), 0.1);
    }

    // ---------------- honest rates ----------------

    @Test
    @DisplayName("a rate off a tiny sample is withheld rather than printed as fact")
    void smallSamplesReturnNull() {
        givenLeads(List.of(
                row("l1", Stage.CONTACTED, LeadSource.REFERRAL, null),
                row("l2", Stage.ENROLLED, LeadSource.REFERRAL, null)));

        var result = metrics.pipelineMetrics(8);
        Metric headline = metric(result, "enquiry_to_enrolment");
        assertNull(headline.getValue(), "50% from two leads is noise, not a measurement");
        assertEquals(2, headline.getSampleSize(), "the sample size is still reported");
    }

    @Test
    @DisplayName("the headline rate is enrolments over all enquiries")
    void headlineRateIsCorrect() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 16; i++) rows.add(row("a" + i, Stage.CONTACTED, LeadSource.INSTAGRAM_AD, null));
        for (int i = 0; i < 4; i++) rows.add(row("e" + i, Stage.ENROLLED, LeadSource.INSTAGRAM_AD, null));
        givenLeads(rows);

        assertEquals(20.0, metric(metrics.pipelineMetrics(8), "enquiry_to_enrolment").getValue(), 0.01);
    }

    @Test
    @DisplayName("demo to enrolment divides by the demos, not by every enquiry")
    void demoToEnrolmentUsesTheRightDenominator() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(row("a" + i, Stage.CONTACTED, LeadSource.INSTAGRAM_AD, null));
        for (int i = 0; i < 5; i++) rows.add(row("d" + i, Stage.DEMO_DONE, LeadSource.INSTAGRAM_AD, null));
        for (int i = 0; i < 5; i++) rows.add(row("e" + i, Stage.ENROLLED, LeadSource.INSTAGRAM_AD, null));
        givenLeads(rows);

        // 10 reached a demo, 5 enrolled — 50%, not 10% of the 50 total.
        assertEquals(50.0, metric(metrics.pipelineMetrics(8), "demo_to_enrolment").getValue(), 0.01);
    }

    @Test
    @DisplayName("first response is averaged from the timestamps, and judged against five minutes")
    void firstResponseAverage() {
        givenLeads(List.of());
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        List<Object[]> stamps = new ArrayList<>();
        for (long m : new long[]{2, 4, 3, 6, 5}) {
            stamps.add(new Object[]{base, base.plusMinutes(m)});
        }
        when(leads.firstResponseTimestampsSince(any())).thenReturn(stamps);

        Metric m = metric(metrics.pipelineMetrics(8), "first_response");
        assertEquals(4.0, m.getValue(), 0.01);
        assertTrue(m.getHealthy(), "four minutes meets the five-minute rule");
    }

    @Test
    @DisplayName("a slow average is reported as unhealthy rather than quietly shown")
    void slowFirstResponseIsFlagged() {
        givenLeads(List.of());
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        List<Object[]> slow = new ArrayList<>();
        for (int i = 0; i < 6; i++) slow.add(new Object[]{base, base.plusMinutes(40)});
        when(leads.firstResponseTimestampsSince(any())).thenReturn(slow);

        assertFalse(metric(metrics.pipelineMetrics(8), "first_response").getHealthy());
    }

    @Test
    @DisplayName("an enrolment with no logged contact drags the persistence average down, as it should")
    void unloggedEnrolmentsAreNotExcluded() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(row("e" + i, Stage.ENROLLED, LeadSource.REFERRAL, null));
        givenLeads(rows);
        // Only three of the five have any recorded touches.
        List<Object[]> touches = new ArrayList<>();
        touches.add(new Object[]{"e0", 5L});
        touches.add(new Object[]{"e1", 5L});
        touches.add(new Object[]{"e2", 5L});
        when(activities.countTouchesForLeads(anyList())).thenReturn(touches);

        // Excluding the unlogged two would report 5.0 and hide the fact that nothing was recorded.
        assertEquals(3.0, metric(metrics.pipelineMetrics(8), "follow_ups_before_enrolment").getValue(), 0.01);
    }

    // ---------------- attribution and people ----------------

    @Test
    @DisplayName("source performance ranks by volume but reports conversion separately")
    void sourcePerformance() {
        givenLeads(List.of());
        List<Object[]> bySource = new ArrayList<>();
        bySource.add(new Object[]{LeadSource.INSTAGRAM_AD, 60L});
        bySource.add(new Object[]{LeadSource.COLLEGE_SEMINAR, 20L});
        when(leads.countGroupedBySource()).thenReturn(bySource);

        List<Object[]> enrolledBySource = new ArrayList<>();
        enrolledBySource.add(new Object[]{LeadSource.INSTAGRAM_AD, 6L});
        enrolledBySource.add(new Object[]{LeadSource.COLLEGE_SEMINAR, 8L});
        when(leads.countEnrolledGroupedBySource()).thenReturn(enrolledBySource);

        var sources = metrics.pipelineMetrics(8).getSources();
        assertEquals("INSTAGRAM_AD", sources.get(0).getSource(), "ranked by lead volume");
        assertEquals(10.0, sources.get(0).getConversionRate(), 0.01);
        assertEquals(40.0, sources.get(1).getConversionRate(), 0.01);
        // The seminar brings a third of the leads and more of the students — the whole point
        // of measuring this.
    }

    @Test
    @DisplayName("the scorecard counts overdue touches and unworked losses per counsellor")
    void counsellorScorecard() {
        givenLeads(List.of());
        when(users.findAll()).thenReturn(List.of(
                User.builder().id("u1").email("sneha@x.com").displayName("Sneha").build()));
        Lead active = Lead.builder().id("a").assignedToId("u1").stage(Stage.CONTACTED)
                .nextTouchOn(java.time.LocalDate.now().minusDays(2)).optedOut(false).build();
        Lead won = Lead.builder().id("b").assignedToId("u1").stage(Stage.ENROLLED).optedOut(false).build();
        Lead dropped = Lead.builder().id("c").assignedToId("u1").stage(Stage.LOST)
                .lostUnworked(true).optedOut(false).build();
        when(leads.findAll()).thenReturn(List.of(active, won, dropped));

        var score = metrics.pipelineMetrics(8).getCounsellors().get(0);
        assertEquals("Sneha", score.getName());
        assertEquals(1, score.getActiveLeads());
        assertEquals(1, score.getEnrolled());
        assertEquals(1, score.getOverdueTouches());
        assertEquals(1, score.getLostUnworked(), "unworked losses are visible per person");
    }

    @Test
    @DisplayName("an empty database produces no numbers rather than zeroes that look like failure")
    void emptyDatabaseIsHonest() {
        givenLeads(List.of());
        var result = metrics.pipelineMetrics(8);

        assertEquals(0, result.getTotalLeads());
        assertNull(metric(result, "enquiry_to_enrolment").getValue());
        assertNull(metric(result, "first_response").getValue());
        assertTrue(result.getFunnel().stream().allMatch(f -> f.getReached() == 0));
    }
}
