package com.devanshedutech.controller;

import com.devanshedutech.model.AppSetting;
import com.devanshedutech.model.Course;
import com.devanshedutech.repository.AppSettingRepository;
import com.devanshedutech.repository.CourseRepository;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class SettingsController {
    
    private final AppSettingRepository appSettingRepository;
    private final CourseRepository courseRepository;
    private final com.devanshedutech.repository.BrochureChunkRepository brochureChunkRepository;

    public SettingsController(AppSettingRepository repository, 
                              CourseRepository courseRepository,
                              com.devanshedutech.repository.BrochureChunkRepository brochureChunkRepository) {
        this.appSettingRepository = repository;
        this.courseRepository = courseRepository;
        this.brochureChunkRepository = brochureChunkRepository;
    }

    @GetMapping("/public/brochure")
    public ResponseEntity<?> getBrochureInfo() {
        return appSettingRepository.findById("GLOBAL_BROCHURE")
                .map(setting -> ResponseEntity.ok(Map.of("downloadUrl", "/api/public/brochure/download")))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/public/brochure/download")
    public ResponseEntity<Resource> downloadBrochure() {
        return downloadFile("GLOBAL_BROCHURE", "Devansh_EduTech_Brochure.pdf");
    }

    @PostMapping("/settings/brochure/upload")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> uploadBrochureFile(@RequestParam("file") MultipartFile file) {
        return saveFile("GLOBAL_BROCHURE", file);
    }

    @GetMapping("/public/brochure/{courseId}")
    public ResponseEntity<?> getCourseBrochureInfo(@PathVariable String courseId) {
        return appSettingRepository.findById("COURSE_BROCHURE_" + courseId)
                .map(setting -> ResponseEntity.ok(Map.of("downloadUrl", "/api/public/brochure/download/" + courseId)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/public/brochure/download/{courseId}")
    public ResponseEntity<Resource> downloadCourseBrochure(@PathVariable String courseId) {
        String fileName = "Course_Brochure.pdf";
        Optional<Course> course = courseRepository.findById(courseId);
        if (course.isPresent()) {
            fileName = course.get().getName().replace(" ", "_") + "_Brochure.pdf";
        }
        return downloadFile("COURSE_BROCHURE_" + courseId, fileName);
    }

    @PostMapping("/settings/brochure/upload/{courseId}")
    @PreAuthorize("hasRole('admin')")
    public ResponseEntity<?> uploadCourseBrochureFile(@PathVariable String courseId, @RequestParam("file") MultipartFile file) {
        return saveFile("COURSE_BROCHURE_" + courseId, file);
    }

    private ResponseEntity<Resource> downloadFile(String key, String downloadName) {
        Optional<AppSetting> setting = appSettingRepository.findById(key);
        if (setting.isPresent()) {
            try {
                // Fetch all chunks ordered by index
                List<com.devanshedutech.model.BrochureChunk> chunks = brochureChunkRepository.findBySettingKeyOrderByChunkIndexAsc(key);
                
                if (chunks.isEmpty()) {
                    return ResponseEntity.notFound().build();
                }

                // Reassemble chunks
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                for (com.devanshedutech.model.BrochureChunk chunk : chunks) {
                    baos.write(chunk.getData());
                }
                
                byte[] pdfBytes = baos.toByteArray();
                Resource resource = new org.springframework.core.io.ByteArrayResource(pdfBytes);
                
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + "\"")
                        .body(resource);
            } catch (Exception e) {
                e.printStackTrace();
                return ResponseEntity.internalServerError().build();
            }
        }
        return ResponseEntity.notFound().build();
    }

    @org.springframework.transaction.annotation.Transactional
    protected void saveInChunks(String key, byte[] fileBytes) {
        // Delete old chunks first
        brochureChunkRepository.deleteBySettingKey(key);
        
        // Save new chunks (10MB each)
        int chunkSize = 10 * 1024 * 1024;
        int totalChunks = (int) Math.ceil((double) fileBytes.length / chunkSize);
        
        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(fileBytes.length, (i + 1) * chunkSize);
            byte[] chunkData = java.util.Arrays.copyOfRange(fileBytes, start, end);
            
            com.devanshedutech.model.BrochureChunk chunk = com.devanshedutech.model.BrochureChunk.builder()
                    .settingKey(key)
                    .chunkIndex(i)
                    .data(chunkData)
                    .build();
            brochureChunkRepository.save(chunk);
        }
        
        // Update AppSetting to mark as present
        AppSetting setting = AppSetting.builder()
                .settingKey(key)
                .settingValue("CHUNKED".getBytes()) // Placeholder to satisfy BYTEA type
                .build();
        appSettingRepository.save(setting);
    }

    public ResponseEntity<?> saveFile(String key, MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
            }
            
            // Limit to 60MB
            if (file.getSize() > 60 * 1024 * 1024) {
                return ResponseEntity.badRequest().body(Map.of("error", "File is too large. Maximum size is 60MB."));
            }
            
            byte[] fileBytes = file.getBytes();
            
            // Use chunked save
            saveInChunks(key, fileBytes);
            
            return ResponseEntity.ok(Map.of("message", "Brochure updated successfully (stored in " + 
                (int) Math.ceil((double) fileBytes.length / (10 * 1024 * 1024)) + " chunks)"));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not store file: " + e.getMessage()));
        }
    }
}
