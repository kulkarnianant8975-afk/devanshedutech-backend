package com.devanshedutech.controller;

import com.devanshedutech.repository.AppSettingRepository;
import com.devanshedutech.repository.BrochureChunkRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/open")
public class PublicBrochureController {
    
    private final AppSettingRepository appSettingRepository;
    private final BrochureChunkRepository brochureChunkRepository;

    public PublicBrochureController(AppSettingRepository appSettingRepository, 
                                   BrochureChunkRepository brochureChunkRepository) {
        this.appSettingRepository = appSettingRepository;
        this.brochureChunkRepository = brochureChunkRepository;
    }

    @GetMapping("/brochure/{courseId}")
    public ResponseEntity<?> getInfo(@PathVariable String courseId) {
        return appSettingRepository.findById("COURSE_BROCHURE_" + courseId)
                .map(setting -> ResponseEntity.ok(Map.of("downloadUrl", "/api/open/brochure/download/" + courseId)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/brochure/download/{courseId}")
    public ResponseEntity<Resource> download(@PathVariable String courseId) {
        List<com.devanshedutech.model.BrochureChunk> chunks = brochureChunkRepository.findBySettingKeyOrderByChunkIndexAsc("COURSE_BROCHURE_" + courseId);
        if (chunks.isEmpty()) return ResponseEntity.notFound().build();
        
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            for (com.devanshedutech.model.BrochureChunk chunk : chunks) {
                baos.write(chunk.getData());
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Brochure.pdf\"")
                    .body(new ByteArrayResource(baos.toByteArray()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
