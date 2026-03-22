package com.devanshedutech.controller;

import com.devanshedutech.dto.CourseDTOs.CourseRequest;
import com.devanshedutech.dto.CourseDTOs.CourseResponse;
import com.devanshedutech.model.Course;
import com.devanshedutech.repository.CourseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseRepository courseRepository;

    public CourseController(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    @GetMapping
    public ResponseEntity<List<CourseResponse>> getAllCourses(@RequestParam(required = false) Integer limit) {
        List<Course> courses = courseRepository.findAll();
        if (limit != null && limit > 0 && courses.size() > limit) {
            courses = courses.subList(0, limit);
        }
        List<CourseResponse> response = courses.stream().map(this::mapToResponse).collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<CourseResponse> createCourse(@RequestBody CourseRequest request) {
        Course course = Course.builder()
                .id(UUID.randomUUID().toString())
                .name(request.getName())
                .description(request.getDescription())
                .duration(request.getDuration())
                .price(request.getPrice())
                .category(request.getCategory())
                .image(request.getImage())
                .build();
        Course saved = courseRepository.save(course);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<CourseResponse> updateCourse(@PathVariable String id, @RequestBody CourseRequest request) {
        return courseRepository.findById(id).map(course -> {
            course.setName(request.getName());
            course.setDescription(request.getDescription());
            course.setDuration(request.getDuration());
            course.setPrice(request.getPrice());
            course.setCategory(request.getCategory());
            course.setImage(request.getImage());
            Course updated = courseRepository.save(course);
            return ResponseEntity.ok(mapToResponse(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> deleteCourse(@PathVariable String id) {
        if (!courseRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        courseRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private CourseResponse mapToResponse(Course course) {
        return CourseResponse.builder()
                .id(course.getId())
                .name(course.getName())
                .description(course.getDescription())
                .duration(course.getDuration())
                .price(course.getPrice())
                .category(course.getCategory())
                .image(course.getImage())
                .build();
    }
}
