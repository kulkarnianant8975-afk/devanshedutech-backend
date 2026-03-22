package com.devanshedutech.controller;

import com.devanshedutech.dto.StatsDTOs.MonthlyLead;
import com.devanshedutech.dto.StatsDTOs.StatsResponse;
import com.devanshedutech.repository.CourseRepository;
import com.devanshedutech.repository.HiringRepository;
import com.devanshedutech.repository.LeadRepository;
import com.devanshedutech.repository.MentorRepository;
import com.devanshedutech.repository.PlacedStudentRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final LeadRepository leadRepository;
    private final CourseRepository courseRepository;
    private final HiringRepository hiringRepository;
    private final MentorRepository mentorRepository;
    private final PlacedStudentRepository placedStudentRepository;

    public StatsController(LeadRepository leadRepository, 
                          CourseRepository courseRepository, 
                          HiringRepository hiringRepository,
                          MentorRepository mentorRepository,
                          PlacedStudentRepository placedStudentRepository) {
        this.leadRepository = leadRepository;
        this.courseRepository = courseRepository;
        this.hiringRepository = hiringRepository;
        this.mentorRepository = mentorRepository;
        this.placedStudentRepository = placedStudentRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('admin')")
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

        StatsResponse response = StatsResponse.builder()
                .totalLeads(totalLeads)
                .totalCourses(totalCourses)
                .totalHiring(totalHiring)
                .totalMentors(totalMentors)
                .totalPlacedStudents(totalPlacedStudents)
                .monthlyLeads(monthlyLeads)
                .build();

        return ResponseEntity.ok(response);
    }
}
