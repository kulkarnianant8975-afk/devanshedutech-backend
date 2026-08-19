package com.devanshedutech.controller;

import com.devanshedutech.model.Batch;
import com.devanshedutech.model.Course;
import com.devanshedutech.repository.BatchRepository;
import com.devanshedutech.repository.CourseRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Course intakes.
 *
 * <p>Readable by anyone who works the pipeline, because a counsellor mid-call needs the next
 * start date; editable only with the content permission, since a wrong date reaches every
 * student who is told it.</p>
 */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final BatchRepository batches;
    private final CourseRepository courses;

    public BatchController(BatchRepository batches, CourseRepository courses) {
        this.batches = batches;
        this.courses = courses;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public ResponseEntity<List<BatchResponse>> list(
            @RequestParam(defaultValue = "false") boolean upcomingOnly) {
        List<Batch> found = upcomingOnly
                ? batches.findUpcoming(LocalDate.now())
                : batches.findAllByOrderByStartDateAsc();
        return ResponseEntity.ok(found.stream().map(this::toResponse).toList());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_CONTENT_MANAGE')")
    public ResponseEntity<BatchResponse> create(@RequestBody BatchRequest request) {
        validate(request);
        Course course = courses.findById(request.getCourseId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a course for this batch."));

        Batch batch = batches.save(Batch.builder()
                .id(UUID.randomUUID().toString())
                .courseId(course.getId())
                .courseName(course.getName())
                .name(request.getName().trim())
                .startDate(request.getStartDate())
                .timing(request.getTiming())
                .capacity(request.getCapacity())
                .status(request.getStatus() == null ? "PLANNED" : request.getStatus())
                .build());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(batch));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_CONTENT_MANAGE')")
    public ResponseEntity<BatchResponse> update(@PathVariable String id, @RequestBody BatchRequest request) {
        Batch batch = batches.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That batch no longer exists."));

        if (request.getName() != null && !request.getName().isBlank()) batch.setName(request.getName().trim());
        if (request.getStartDate() != null) batch.setStartDate(request.getStartDate());
        if (request.getTiming() != null) batch.setTiming(request.getTiming());
        if (request.getCapacity() != null) batch.setCapacity(request.getCapacity());
        if (request.getStatus() != null) batch.setStatus(request.getStatus());

        return ResponseEntity.ok(toResponse(batches.save(batch)));
    }

    private void validate(BatchRequest r) {
        if (r.getName() == null || r.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the batch a name.");
        }
        if (r.getStartDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a start date.");
        }
        if (r.getCapacity() != null && r.getCapacity() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Leave the seat count empty for an uncapped batch rather than setting it to zero.");
        }
    }

    private BatchResponse toResponse(Batch b) {
        return BatchResponse.builder()
                .id(b.getId()).courseId(b.getCourseId()).courseName(b.getCourseName())
                .name(b.getName()).startDate(b.getStartDate()).timing(b.getTiming())
                .capacity(b.getCapacity()).status(b.getStatus())
                .takingEnrolments(b.isTakingEnrolments())
                .description(b.describe())
                .build();
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BatchRequest {
        private String courseId;
        private String name;
        private LocalDate startDate;
        private String timing;
        private Integer capacity;
        private String status;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BatchResponse {
        private String id;
        private String courseId;
        private String courseName;
        private String name;
        private LocalDate startDate;
        private String timing;
        private Integer capacity;
        private String status;
        /** Whether a counsellor can still put somebody in it. */
        private boolean takingEnrolments;
        /** Ready to read out on a call. */
        private String description;
    }
}
