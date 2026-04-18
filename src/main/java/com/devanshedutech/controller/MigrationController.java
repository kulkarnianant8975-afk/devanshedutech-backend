package com.devanshedutech.controller;

import com.devanshedutech.model.Course;
import com.devanshedutech.model.Mentor;
import com.devanshedutech.model.PlacedStudent;
import com.devanshedutech.repository.CourseRepository;
import com.devanshedutech.repository.MentorRepository;
import com.devanshedutech.repository.PlacedStudentRepository;
import com.devanshedutech.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class MigrationController {

    private final CourseRepository courseRepository;
    private final MentorRepository mentorRepository;
    private final PlacedStudentRepository placedStudentRepository;
    private final CloudinaryService cloudinaryService;

    @GetMapping("/migrate-images")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> migrateImages() {
        Map<String, Object> results = new HashMap<>();
        int courseCount = 0;
        int mentorCount = 0;
        int studentCount = 0;

        try {
            // Migrate Courses
            List<Course> courses = courseRepository.findAll();
            for (Course course : courses) {
                if (course.getImage() != null && course.getImage().startsWith("data:image")) {
                    String optimizedUrl = cloudinaryService.uploadBase64(course.getImage(), "courses");
                    course.setImage(optimizedUrl);
                    courseRepository.save(course);
                    courseCount++;
                }
            }

            // Migrate Mentors
            List<Mentor> mentors = mentorRepository.findAll();
            for (Mentor mentor : mentors) {
                if (mentor.getImageUrl() != null && mentor.getImageUrl().startsWith("data:image")) {
                    String optimizedUrl = cloudinaryService.uploadBase64(mentor.getImageUrl(), "mentors");
                    mentor.setImageUrl(optimizedUrl);
                    mentorRepository.save(mentor);
                    mentorCount++;
                }
            }

            // Migrate Placed Students
            List<PlacedStudent> students = placedStudentRepository.findAll();
            for (PlacedStudent student : students) {
                if (student.getImageUrl() != null && student.getImageUrl().startsWith("data:image")) {
                    String optimizedUrl = cloudinaryService.uploadBase64(student.getImageUrl(), "placed-students");
                    student.setImageUrl(optimizedUrl);
                    placedStudentRepository.save(student);
                    studentCount++;
                }
            }

            results.put("success", true);
            results.put("message", "Migration completed successfully.");
            results.put("coursesMigrated", courseCount);
            results.put("mentorsMigrated", mentorCount);
            results.put("studentsMigrated", studentCount);

            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("Migration failed", e);
            results.put("success", false);
            results.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(results);
        }
    }
}
