package com.devanshedutech.controller;

import com.devanshedutech.service.CloudinaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Single unified endpoint for all image uploads across the platform.
 * Images are uploaded directly to Cloudinary CDN — nothing stored in the database.
 *
 * Usage: POST /api/upload/image?folder=mentors
 *        POST /api/upload/image?folder=courses
 *        POST /api/upload/image?folder=placed-students
 *
 * Returns: { "url": "https://res.cloudinary.com/..." }
 */
@Slf4j
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class ImageUploadController {

    private final CloudinaryService cloudinaryService;

    @PostMapping("/image")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "folder", defaultValue = "general") String folder) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }

        // Validate file type
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed"));
        }

        // Limit to 15MB for uploads
        if (file.getSize() > 15 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "Image must be under 15MB"));
        }

        try {
            String cdnUrl = cloudinaryService.uploadImage(file, folder);
            return ResponseEntity.ok(Map.of("url", cdnUrl));
        } catch (Exception e) {
            log.error("Cloudinary upload failed for folder={}: {}", folder, e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }
}
