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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-shot migration endpoint: reads all Base64 images from DB,
 * uploads them to Cloudinary CDN, and replaces the Base64 with CDN URLs.
 *
 * Call once from admin panel:  POST /api/admin/migrate-images
 * After migration, this endpoint can be removed.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class ImageMigrationController {

    private final MentorRepository mentorRepository;
    private final CourseRepository courseRepository;
    private final PlacedStudentRepository placedStudentRepository;
    private final CloudinaryService cloudinaryService;

    @PostMapping("/migrate-images")
    @PreAuthorize("hasRole('ADMIN')")
    @Caching(evict = {
        @CacheEvict(value = "mentors",        allEntries = true),
        @CacheEvict(value = "courses",        allEntries = true),
        @CacheEvict(value = "placedStudents", allEntries = true)
    })
    public ResponseEntity<?> migrateAllImages() {
        List<String> migrated = new ArrayList<>();
        List<String> failed   = new ArrayList<>();

        // ── 1. Migrate Mentor images ────────────────────────────────────────
        List<Mentor> mentors = mentorRepository.findAll();
        for (Mentor mentor : mentors) {
            if (isBase64(mentor.getImageUrl())) {
                try {
                    String cdnUrl = cloudinaryService.uploadBase64(mentor.getImageUrl(), "mentors");
                    mentor.setImageUrl(cdnUrl);
                    mentorRepository.save(mentor);
                    migrated.add("mentor:" + mentor.getName());
                    log.info("Migrated mentor image: {} → {}", mentor.getName(), cdnUrl);
                } catch (Exception e) {
                    failed.add("mentor:" + mentor.getName() + " (" + e.getMessage() + ")");
                    log.error("Failed to migrate mentor {}: {}", mentor.getName(), e.getMessage());
                }
            }
        }

        // ── 2. Migrate Course images ────────────────────────────────────────
        List<Course> courses = courseRepository.findAll();
        for (Course course : courses) {
            if (isBase64(course.getImage())) {
                try {
                    String cdnUrl = cloudinaryService.uploadBase64(course.getImage(), "courses");
                    course.setImage(cdnUrl);
                    courseRepository.save(course);
                    migrated.add("course:" + course.getName());
                    log.info("Migrated course image: {} → {}", course.getName(), cdnUrl);
                } catch (Exception e) {
                    failed.add("course:" + course.getName() + " (" + e.getMessage() + ")");
                    log.error("Failed to migrate course {}: {}", course.getName(), e.getMessage());
                }
            }
        }

        // ── 3. Migrate PlacedStudent images ─────────────────────────────────
        List<PlacedStudent> students = placedStudentRepository.findAll();
        for (PlacedStudent student : students) {
            if (isBase64(student.getImageUrl())) {
                try {
                    String cdnUrl = cloudinaryService.uploadBase64(student.getImageUrl(), "placed-students");
                    student.setImageUrl(cdnUrl);
                    placedStudentRepository.save(student);
                    migrated.add("student:" + student.getName());
                    log.info("Migrated placed student image: {} → {}", student.getName(), cdnUrl);
                } catch (Exception e) {
                    failed.add("student:" + student.getName() + " (" + e.getMessage() + ")");
                    log.error("Failed to migrate student {}: {}", student.getName(), e.getMessage());
                }
            }
        }

        return ResponseEntity.ok(Map.of(
                "status",   failed.isEmpty() ? "DONE" : "PARTIAL",
                "migrated", migrated.size(),
                "failed",   failed.size(),
                "details",  Map.of("migrated", migrated, "failed", failed)
        ));
    }

    /** Returns true if the URL is a raw Base64 data URL stored in the DB */
    private boolean isBase64(String url) {
        return url != null && url.startsWith("data:image");
    }
}
