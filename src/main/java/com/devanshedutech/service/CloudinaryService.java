package com.devanshedutech.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudinaryService {

    private final Cloudinary cloudinary;

    /**
     * Uploads a file to Cloudinary and returns the secure HTTPS CDN URL.
     * Cloudinary automatically converts to WebP and applies quality=auto.
     *
     * @param file   MultipartFile from the upload request
     * @param folder Sub-folder under "devanshedutech/" in Cloudinary
     * @return Secure HTTPS URL served from Cloudinary's global CDN
     */
    public String uploadImage(MultipartFile file, String folder) throws IOException {
        log.info("Uploading image to Cloudinary folder: devanshedutech/{}", folder);
        Map<?, ?> result = cloudinary.uploader().upload(file.getBytes(), ObjectUtils.asMap(
                "folder",        "devanshedutech/" + folder,
                "transformation", "q_auto,f_auto,w_900,c_limit", // Auto WebP/AVIF, quality, max 900px
                "resource_type", "image"
        ));
        String url = (String) result.get("secure_url");
        log.info("Upload successful. CDN URL: {}", url);
        return url;
    }

    /**
     * Uploads a Base64 data URL to Cloudinary.
     * Used for migrating existing Base64 images that are already stored in the DB.
     *
     * @param base64DataUrl data:image/jpeg;base64,/9j/4AAQ...
     * @param folder        Sub-folder in Cloudinary
     * @return Secure HTTPS CDN URL
     */
    public String uploadBase64(String base64DataUrl, String folder) throws IOException {
        log.info("Uploading Base64 image to Cloudinary folder: devanshedutech/{}", folder);
        Map<?, ?> result = cloudinary.uploader().upload(base64DataUrl, ObjectUtils.asMap(
                "folder",        "devanshedutech/" + folder,
                "transformation", "q_auto,f_auto,w_900,c_limit",
                "resource_type", "image"
        ));
        String url = (String) result.get("secure_url");
        log.info("Base64 upload successful. CDN URL: {}", url);
        return url;
    }
}
