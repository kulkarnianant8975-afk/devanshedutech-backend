package com.devanshedutech.service;

import com.devanshedutech.dto.MetricsDTOs.*;
import com.devanshedutech.model.Lead;
import com.devanshedutech.model.User;
import com.devanshedutech.model.crm.LeadSource;
import com.devanshedutech.model.crm.Stage;
import com.devanshedutech.repository.LeadActivityRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The one place any business metric is calculated.
 *
 * <p>The reason this is a single service rather than a query here and a sum there is that a
 * conversion rate computed two different ways will eventually disagree, and once two dashboards
 * show different numbers nobody trusts either. Every rate in the product comes from here.</p>
 *
 * <p>Two decisions shape the maths. Funnel rates are computed from the deepest stage a lead ever
 * reached, not from where it sits today, because a student who attended a demo and then chose
 * another institute did reach the demo stage. And a metric with too little data returns null
 * rather than a number: "0%" from two leads reads as a catastrophe when it means nothing yet.</p>
 */
@Service
public class MetricsService {

    /** Below this many records, a rate is noise rather than a measurement. */
    @Value("${app.crm.metrics.min-sample:5}")
    private int minSample;

    /** The playbook's rule: a human reply within five minutes during working hours. */
    private static final int SLA_MINUTES = 5;

    private final LeadRepository leadRepository;
    private final LeadActivityRepository activityRepository;
    private final UserRepository userRepository;
    private final BusinessCalendar calendar;

    public MetricsService(LeadRepository leadRepository,
                          LeadActivityRepository activityRepository,
                          UserRepository userRepository,
                          BusinessCalendar calendar) {
        this.leadRepository = leadRepository;
        this.activityRepository = activityRepository;
        this.userRepository = userRepository;
        this.calendar = calendar;
    }

    /** Everything the manager dashboard needs, in one pass over the aggregates. */
    public PipelineMetricsResponse pipelineMetrics(int weeks) {
        LocalDate today = LocalDate.now();
        LocalDateTime since = today.minusWeeks(weeks).atStartOfDay();

        // id -> deepest funnel stage ever reached, combining current stage with recorded history.
        Map<String, Integer> deepest = deepestStageReached();
        List<Object[]> rows = leadRepository.funnelProjection();
        long total = rows.size();

        return PipelineMetricsResponse.builder()
                .metrics(sixMetrics(rows, deepest, since, weeks))
                .funnel(funnel(rows, deepest))
                .sources(sourcePerformance())
                .weekly(weekly(weeks))
                .counsellors(counsellorScores())
                .totalLeads(total)
                .windowDescription("Last " + weeks + " weeks, to " + today)
                .build();
    }

    // ==================================================================
    // The six numbers
    // ==================================================================

    private List<Metric> sixMetrics(List<Object[]> rows, Map<String, Integer> deepest,
                                    LocalDateTime since, int weeks) {
        List<Metric> out = new ArrayList<>();
        long total = rows.size();

        // 1 — leads per week
        long recent = leadRepository.countByCreatedAtBetween(since, LocalDateTime.now());
        out.add(Metric.builder()
                .key("leads_per_week").label("Leads per week")
                .value(weeks == 0 ? null : round((double) recent / weeks))
                .unit("").target("Know your top two sources")
                .explanation("Which channels are actually filling the pipeline.")
                .sampleSize(recent)
                .build());

        // 2 — first response time
        List<Object[]> responses = leadRepository.firstResponseTimestampsSince(since);
        List<Long> minutes = responses.stream()
                .filter(r -> r[0] != null && r[1] != null)
                // Working minutes, not wall-clock. See BusinessCalendar: the old figure mostly
                // measured what time of day students fill in forms.
                .map(r -> calendar.workingMinutesBetween((LocalDateTime) r[0], (LocalDateTime) r[1]))
                .filter(m -> m >= 0)
                .toList();
        Double avgResponse = minutes.isEmpty() ? null
                : round(minutes.stream().mapToLong(Long::longValue).average().orElse(0));
        out.add(Metric.builder()
                .key("first_response").label("First response time")
                .value(enough(minutes.size()) ? avgResponse : null)
                .unit("min").target("Under 5 minutes")
                .explanation("How long a student waits for a human reply, counting only opening "
                        + "hours. The institute that replies first usually controls the "
                        + "conversation.")
                .healthy(avgResponse == null ? null : avgResponse <= SLA_MINUTES)
                .sampleSize(minutes.size())
                .build());

        // 3 — enquiry to demo
        long reachedDemo = countReaching(rows, deepest, Stage.DEMO_BOOKED);
        out.add(rate("enquiry_to_demo", "Enquiry → demo", reachedDemo, total, "Rising",
                "How well qualifying and the guidance call are working."));

        // 4 — demo to enrolment
        long reachedEnrol = countReaching(rows, deepest, Stage.ENROLLED);
        out.add(rate("demo_to_enrolment", "Demo → enrolment", reachedEnrol, reachedDemo, "Rising",
                "How well the counselling itself is working, once a student has seen a class."));

        // 5 — the headline number
        Metric headline = rate("enquiry_to_enrolment", "Enquiry → enrolment", reachedEnrol, total,
                "From ~10% toward 18–24%",
                "The headline number. Everything else exists to move this one.");
        headline.setHealthy(headline.getValue() == null ? null : headline.getValue() >= 18);
        out.add(headline);

        // 6 — persistence
        List<String> enrolledIds = rows.stream()
                .filter(r -> r[1] == Stage.ENROLLED)
                .map(r -> (String) r[0]).toList();
        Double avgTouches = null;
        if (!enrolledIds.isEmpty()) {
            List<Object[]> counts = activityRepository.countTouchesForLeads(enrolledIds);
            Map<String, Long> byLead = counts.stream()
                    .collect(Collectors.toMap(c -> (String) c[0], c -> (Long) c[1]));
            // Enrolments with no recorded touches still count as zero: excluding them would
            // flatter the average by hiding exactly the cases where nothing was logged.
            avgTouches = round(enrolledIds.stream()
                    .mapToLong(id -> byLead.getOrDefault(id, 0L)).average().orElse(0));
        }
        out.add(Metric.builder()
                .key("follow_ups_before_enrolment").label("Follow-ups before enrolment")
                .value(enough(enrolledIds.size()) ? avgTouches : null)
                .unit("avg").target("Usually 3–5, not 1")
                .explanation("Whether counsellors are persisting. Most admissions happen on the "
                        + "third to fifth follow-up.")
                .healthy(avgTouches == null ? null : avgTouches >= 3)
                .sampleSize(enrolledIds.size())
                .build());
        return out;
    }

    private Metric rate(String key, String label, long numerator, long denominator,
                        String target, String explanation) {
        Double value = (denominator == 0 || !enough(denominator))
                ? null
                : round(100.0 * numerator / denominator);
        return Metric.builder()
                .key(key).label(label).value(value).unit("%")
                .target(target).explanation(explanation)
                .sampleSize(denominator)
                .build();
    }

    // ==================================================================
    // Funnel
    // ==================================================================

    private List<FunnelStep> funnel(List<Object[]> rows, Map<String, Integer> deepest) {
        List<Stage> ordered = Arrays.stream(Stage.values())
                .filter(Stage::isInFunnel)
                .sorted(java.util.Comparator.comparingInt(Stage::getFunnelDepth))
                .toList();

        long total = rows.size();
        List<FunnelStep> out = new ArrayList<>();
        Long previous = null;

        for (Stage stage : ordered) {
            long reached = countReaching(rows, deepest, stage);
            Double drop = (previous == null || previous == 0)
                    ? null
                    : round(100.0 * (previous - reached) / previous);
            out.add(FunnelStep.builder()
                    .stage(stage.name()).label(stage.getLabel())
                    .reached(reached)
                    .percentOfTotal(total == 0 ? 0 : round(100.0 * reached / total))
                    .dropFromPrevious(drop)
                    .build());
            previous = reached;
        }
        return out;
    }

    /**
     * How many leads ever reached a stage.
     *
     * <p>Takes the deeper of where the lead is now and the deepest stage its history records, so
     * leads that predate the activity log still count from their current position rather than
     * vanishing from the funnel entirely.</p>
     */
    private long countReaching(List<Object[]> rows, Map<String, Integer> deepest, Stage stage) {
        int target = stage.getFunnelDepth();
        return rows.stream().filter(r -> {
            String id = (String) r[0];
            Stage current = (Stage) r[1];
            int now = current == null ? 0 : Math.max(current.getFunnelDepth(), 0);
            int ever = Math.max(now, deepest.getOrDefault(id, -1));
            return ever >= target;
        }).count();
    }

    private Map<String, Integer> deepestStageReached() {
        Map<String, Integer> out = new HashMap<>();
        for (Object[] row : activityRepository.maxStageReachedPerLead()) {
            String leadId = (String) row[0];
            Stage stage = (Stage) row[1];
            if (stage == null) continue;
            out.merge(leadId, Math.max(stage.getFunnelDepth(), -1), Math::max);
        }
        return out;
    }

    // ==================================================================
    // Attribution and people
    // ==================================================================

    private List<SourcePerformance> sourcePerformance() {
        Map<LeadSource, Long> leads = toCountMap(leadRepository.countGroupedBySource());
        Map<LeadSource, Long> enrolled = toCountMap(leadRepository.countEnrolledGroupedBySource());

        return leads.entrySet().stream()
                .map(e -> {
                    long n = e.getValue();
                    long won = enrolled.getOrDefault(e.getKey(), 0L);
                    return SourcePerformance.builder()
                            .source(e.getKey() == null ? "UNKNOWN" : e.getKey().name())
                            .label(e.getKey() == null ? "Not recorded" : e.getKey().getLabel())
                            .leads(n).enrolled(won)
                            .conversionRate(enough(n) ? round(100.0 * won / n) : null)
                            .build();
                })
                .sorted(java.util.Comparator.comparingLong(SourcePerformance::getLeads).reversed())
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<LeadSource, Long> toCountMap(List<Object[]> rows) {
        Map<LeadSource, Long> out = new HashMap<>();
        for (Object[] r : rows) {
            out.merge((LeadSource) r[0], (Long) r[1], Long::sum);
        }
        return out;
    }

    private List<WeeklyCount> weekly(int weeks) {
        List<WeeklyCount> out = new ArrayList<>();
        LocalDate monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
        for (int i = weeks - 1; i >= 0; i--) {
            LocalDate start = monday.minusWeeks(i);
            long n = leadRepository.countByCreatedAtBetween(
                    start.atStartOfDay(), start.plusWeeks(1).atStartOfDay());
            out.add(new WeeklyCount(start, n));
        }
        return out;
    }

    private List<CounsellorScore> counsellorScores() {
        List<Lead> all = leadRepository.findAll();
        Map<String, String> names = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::displayNameOrEmail, (a, b) -> a));
        LocalDate today = LocalDate.now();

        Map<String, List<Lead>> byOwner = all.stream()
                .filter(l -> l.getAssignedToId() != null)
                .collect(Collectors.groupingBy(Lead::getAssignedToId));

        return byOwner.entrySet().stream().map(e -> {
            List<Lead> owned = e.getValue();
            long enrolled = owned.stream().filter(l -> l.getStage() == Stage.ENROLLED).count();
            long active = owned.stream().filter(Lead::isActive).count();
            return CounsellorScore.builder()
                    .userId(e.getKey())
                    .name(names.getOrDefault(e.getKey(), "Removed account"))
                    .activeLeads(active)
                    .enrolled(enrolled)
                    .conversionRate(enough(owned.size()) ? round(100.0 * enrolled / owned.size()) : null)
                    .overdueTouches(owned.stream().filter(l -> l.isActive()
                            && l.getNextTouchOn() != null && l.getNextTouchOn().isBefore(today)).count())
                    .blankNextTouch(owned.stream().filter(Lead::hasBlankNextTouch).count())
                    .lostUnworked(owned.stream().filter(l -> Boolean.TRUE.equals(l.getLostUnworked())).count())
                    .build();
        }).sorted(java.util.Comparator.comparingLong(CounsellorScore::getActiveLeads).reversed()).toList();
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** Whether there is enough data for a rate to mean anything. */
    private boolean enough(long sample) {
        return sample >= minSample;
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Days since a date, used by callers that need a simple age. */
    public static long daysSince(LocalDateTime from) {
        return from == null ? 0 : ChronoUnit.DAYS.between(from.toLocalDate(), LocalDate.now());
    }
}
