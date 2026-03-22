package com.devanshedutech.controller;

import com.devanshedutech.dto.PlacedStudentDTOs;
import com.devanshedutech.service.PlacedStudentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/placed-students")
@RequiredArgsConstructor
public class PlacedStudentController {
    private final PlacedStudentService placedStudentService;

    @GetMapping
    public List<PlacedStudentDTOs.PlacedStudentResponse> getAllPlacedStudents() {
        return placedStudentService.getAllPlacedStudents();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('admin')")
    public PlacedStudentDTOs.PlacedStudentResponse createPlacedStudent(@RequestBody PlacedStudentDTOs.PlacedStudentRequest request) {
        return placedStudentService.createPlacedStudent(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('admin')")
    public PlacedStudentDTOs.PlacedStudentResponse updatePlacedStudent(@PathVariable String id, @RequestBody PlacedStudentDTOs.PlacedStudentRequest request) {
        return placedStudentService.updatePlacedStudent(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('admin')")
    public void deletePlacedStudent(@PathVariable String id) {
        placedStudentService.deletePlacedStudent(id);
    }
}
