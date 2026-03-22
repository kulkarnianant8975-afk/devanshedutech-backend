package com.devanshedutech.controller;

import com.devanshedutech.dto.HiringDTOs.HiringRequest;
import com.devanshedutech.dto.HiringDTOs.HiringResponse;
import com.devanshedutech.model.Hiring;
import com.devanshedutech.repository.HiringRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/hiring")
public class HiringController {

    private final HiringRepository hiringRepository;

    public HiringController(HiringRepository hiringRepository) {
        this.hiringRepository = hiringRepository;
    }

    @GetMapping
    public ResponseEntity<List<HiringResponse>> getAllHiringPosts() {
        return ResponseEntity.ok(
                hiringRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .stream().map(this::mapToResponse).collect(Collectors.toList())
        );
    }

    @PostMapping
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<HiringResponse> createHiringPost(@RequestBody HiringRequest request) {
        Hiring hiring = Hiring.builder()
                .id(UUID.randomUUID().toString())
                .title(request.getTitle())
                .company(request.getCompany())
                .location(request.getLocation())
                .type(request.getType())
                .description(request.getDescription())
                .requirements(request.getRequirements())
                .salary(request.getSalary())
                .link(request.getLink())
                .build();
        Hiring saved = hiringRepository.save(hiring);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<HiringResponse> updateHiringPost(@PathVariable String id, @RequestBody HiringRequest request) {
        return hiringRepository.findById(id).map(hiring -> {
            hiring.setTitle(request.getTitle());
            hiring.setCompany(request.getCompany());
            hiring.setLocation(request.getLocation());
            hiring.setType(request.getType());
            hiring.setDescription(request.getDescription());
            hiring.setRequirements(request.getRequirements());
            hiring.setSalary(request.getSalary());
            hiring.setLink(request.getLink());
            Hiring updated = hiringRepository.save(hiring);
            return ResponseEntity.ok(mapToResponse(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> deleteHiringPost(@PathVariable String id) {
        if (!hiringRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        hiringRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private HiringResponse mapToResponse(Hiring hiring) {
        return HiringResponse.builder()
                .id(hiring.getId())
                .title(hiring.getTitle())
                .company(hiring.getCompany())
                .location(hiring.getLocation())
                .type(hiring.getType())
                .description(hiring.getDescription())
                .requirements(hiring.getRequirements())
                .salary(hiring.getSalary())
                .link(hiring.getLink())
                .createdAt(hiring.getCreatedAt())
                .build();
    }
}
