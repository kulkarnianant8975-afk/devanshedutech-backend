package com.devanshedutech.controller;

import com.devanshedutech.dto.StatsDTOs.CourseLead;
import com.devanshedutech.dto.StatsDTOs.MonthlyLead;
import com.devanshedutech.dto.MetricsDTOs.PipelineMetricsResponse;
import com.devanshedutech.dto.StatsDTOs.StatsResponse;
import com.devanshedutech.model.Permission;
import com.devanshedutech.security.AccessService;
import com.devanshedutech.service.MetricsService;
import com.devanshedutech.repository.CourseRepository;
import com.devanshedutech.repository.HiringRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.MentorRepository;
import com.devanshedutech.repository.PlacedStudentRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final LeadRepository leadRepository;
    private final CourseRepository courseRepository;
    private final HiringRepository hiringRepository;
    private final MentorRepository mentorRepository;
    private final PlacedStudentRepository placedStudentRepository;
    private final MetricsService metrics;
    private final AccessService access;

    public StatsController(MetricsService metrics,
                          AccessService access,
                          LeadRepository leadRepository, 
                          CourseRepository courseRepository, 
                          HiringRepository hiringRepository,
                          MentorRepository mentorRepository,
                          PlacedStudentRepository placedStudentRepository) {
        this.metrics = metrics;
        this.access = access;
        this.leadRepository = leadRepository;
        this.courseRepository = courseRepository;
        this.hiringRepository = hiringRepository;
        this.mentorRepository = mentorRepository;
        this.placedStudentRepository = placedStudentRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_REPORT_VIEW')")
    @Cacheable(value = "stats", key = "'dashboard'")
    public ResponseEntity<StatsResponse> getStats() {
        long totalLeads = leadRepository.count();
        long totalCourses = courseRepository.count();
        long totalHiring = hiringRepository.count();
        long totalMentors = mentorRepository.count();
        long totalPlacedStudents = placedStudentRepository.count();

        List<MonthlyLead> monthlyLeads = new ArrayList<>();
        YearMonth currentMonth = YearMonth.now();

        // Last 6 months including current
        for (int i = 5; i >= 0; i--) {
            YearMonth targetMonth = currentMonth.minusMonths(i);
            LocalDateTime start = targetMonth.atDay(1).atStartOfDay();
            LocalDateTime end = targetMonth.atEndOfMonth().atTime(23, 59, 59);

            long count = leadRepository.countByCreatedAtBetween(start, end);
            String monthName = targetMonth.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            
            monthlyLeads.add(new MonthlyLead(monthName, count));
        }

        // Leads per course, top 8 by volume. courseInterested is free text rather than a
        // foreign key, so the database groups on the stored value. This previously loaded every
        // lead into memory to count them, which was fine at a few hundred rows and would not be
        // at a few thousand.
        List<CourseLead> leadsByCourse = leadRepository.countGroupedByCourse().stream()
                .map(row -> new CourseLead(
                        row[0] == null || ((String) row[0]).isBlank()
                                ? "Not specified" : ((String) row[0]).trim(),
                        (Long) row[1]))
                .sorted((a, b) -> Long.compare(b.getLeads(), a.getLeads()))
                .limit(8)
                .collect(Collectors.toList());

        StatsResponse response = StatsResponse.builder()
                .totalLeads(totalLeads)
                .totalCourses(totalCourses)
                .totalHiring(totalHiring)
                .totalMentors(totalMentors)
                .totalPlacedStudents(totalPlacedStudents)
                .monthlyLeads(monthlyLeads)
                .leadsByCourse(leadsByCourse)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * The playbook's six numbers, plus the funnel, source performance and the team scorecard.
     *
     * <p>Every figure here comes from {@code MetricsService}, which is the only place any of them
     * is calculated. Two dashboards showing different conversion rates is how a team stops
     * trusting both.</p>
     */
    @GetMapping("/pipeline")
    @PreAuthorize("hasAuthority('PERM_REPORT_VIEW')")
    public ResponseEntity<PipelineMetricsResponse> pipeline(
            Authentication auth,
            @RequestParam(defaultValue = "8") int weeks) {

        int window = Math.min(Math.max(weeks, 1), 52);
        PipelineMetricsResponse body = metrics.pipelineMetrics(window);

        // Individual performance is a manager's view. A counsellor sees the institute's numbers
        // but not a ranking of their colleagues.
        if (!access.can(auth, Permission.REPORT_VIEW_TEAM)) {
            body.setCounsellors(List.of());
        }
        return ResponseEntity.ok(body);
    }
}
