package com.devanshedutech.controller;

import com.devanshedutech.dto.LeadDTOs.LeadRequest;
import com.devanshedutech.dto.LeadDTOs.LeadResponse;
import com.devanshedutech.dto.LeadDTOs.LeadStatusUpdate;
import com.devanshedutech.model.Lead;
import com.devanshedutech.repository.LeadRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private final LeadRepository leadRepository;

    public LeadController(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<List<LeadResponse>> getAllLeads() {
        return ResponseEntity.ok(
                leadRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                        .stream().map(this::mapToResponse).collect(Collectors.toList())
        );
    }

    @PostMapping
    public ResponseEntity<LeadResponse> createLead(@RequestBody LeadRequest request) {
        Lead lead = Lead.builder()
                .id(UUID.randomUUID().toString())
                .fullName(request.getFullName())
                .email(request.getEmail())
                .mobileNumber(request.getMobileNumber())
                .education(request.getEducation())
                .cityName(request.getCityName())
                .courseInterested(request.getCourseInterested())
                .status("New")
                .build();
        Lead saved = leadRepository.save(lead);
        return ResponseEntity.status(HttpStatus.CREATED).body(mapToResponse(saved));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<LeadResponse> updateLeadStatus(@PathVariable String id, @RequestBody LeadStatusUpdate request) {
        return leadRepository.findById(id).map(lead -> {
            lead.setStatus(request.getStatus());
            Lead updated = leadRepository.save(lead);
            return ResponseEntity.ok(mapToResponse(updated));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<Void> deleteLead(@PathVariable String id) {
        if (!leadRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        leadRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private LeadResponse mapToResponse(Lead lead) {
        return LeadResponse.builder()
                .id(lead.getId())
                .fullName(lead.getFullName())
                .email(lead.getEmail())
                .mobileNumber(lead.getMobileNumber())
                .education(lead.getEducation())
                .cityName(lead.getCityName())
                .courseInterested(lead.getCourseInterested())
                .status(lead.getStatus())
                .createdAt(lead.getCreatedAt())
                .build();
    }
}
