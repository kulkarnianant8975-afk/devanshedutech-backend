package com.devanshedutech.service;

import com.devanshedutech.dto.PlacedStudentDTOs;
import com.devanshedutech.model.PlacedStudent;
import com.devanshedutech.repository.PlacedStudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlacedStudentService {
    private final PlacedStudentRepository placedStudentRepository;

    public List<PlacedStudentDTOs.PlacedStudentResponse> getAllPlacedStudents() {
        return placedStudentRepository.findAll().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public PlacedStudentDTOs.PlacedStudentResponse createPlacedStudent(PlacedStudentDTOs.PlacedStudentRequest request) {
        PlacedStudent student = PlacedStudent.builder()
                .name(request.getName())
                .company(request.getCompany())
                .role(request.getRole())
                .salaryPackage(request.getSalaryPackage())
                .testimonial(request.getTestimonial())
                .imageUrl(request.getImageUrl())
                .build();
        return mapToResponse(placedStudentRepository.save(student));
    }

    @Transactional
    public PlacedStudentDTOs.PlacedStudentResponse updatePlacedStudent(String id, PlacedStudentDTOs.PlacedStudentRequest request) {
        PlacedStudent student = placedStudentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Placed student not found"));
        student.setName(request.getName());
        student.setCompany(request.getCompany());
        student.setRole(request.getRole());
        student.setSalaryPackage(request.getSalaryPackage());
        student.setTestimonial(request.getTestimonial());
        student.setImageUrl(request.getImageUrl());
        return mapToResponse(placedStudentRepository.save(student));
    }

    @Transactional
    public void deletePlacedStudent(String id) {
        placedStudentRepository.deleteById(id);
    }

    public org.springframework.http.ResponseEntity<byte[]> getPlacedStudentImage(String id) {
        PlacedStudent student = placedStudentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Placed student not found"));
        String imageUrl = student.getImageUrl();
        if (imageUrl != null && imageUrl.startsWith("data:image")) {
            String[] parts = imageUrl.split(",");
            if (parts.length == 2) {
                String meta = parts[0]; 
                String base64Data = parts[1];
                byte[] imageBytes = java.util.Base64.getDecoder().decode(base64Data);
                String mimeType = meta.substring(meta.indexOf(":") + 1, meta.indexOf(";"));
                
                return org.springframework.http.ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.parseMediaType(mimeType))
                        .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "max-age=31536000")
                        .body(imageBytes);
            }
        }
        return org.springframework.http.ResponseEntity.notFound().build();
    }

    private PlacedStudentDTOs.PlacedStudentResponse mapToResponse(PlacedStudent student) {
        String url = student.getImageUrl();
        if (url != null && url.startsWith("data:image")) {
            url = "/api/placed-students/" + student.getId() + "/image";
        }
        return PlacedStudentDTOs.PlacedStudentResponse.builder()
                .id(student.getId())
                .name(student.getName())
                .company(student.getCompany())
                .role(student.getRole())
                .salaryPackage(student.getSalaryPackage())
                .testimonial(student.getTestimonial())
                .imageUrl(url)
                .createdAt(student.getCreatedAt())
                .build();
    }
}
