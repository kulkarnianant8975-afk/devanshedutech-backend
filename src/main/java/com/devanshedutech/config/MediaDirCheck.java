package com.devanshedutech.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Checks the media folder can actually be written to, at startup.
 *
 * <p>Otherwise the first anyone knows is a counsellor uploading a video and being told the file
 * could not be saved — which sends them to re-export the video rather than to the server. A
 * docker volume arrives owned by root, and this application deliberately does not run as root,
 * so this is a real and silent way for uploads to be broken from the moment a volume is added.</p>
 *
 * <p>It warns rather than refusing to start. Uploads are one feature; a CRM that will not boot
 * because of them would be a worse trade for the counsellors working leads in it.</p>
 */
@Slf4j
@Configuration
public class MediaDirCheck {

    @Bean
    @Order(1)
    public ApplicationRunner checkMediaDir(@Value("${app.crm.media-dir:/var/lib/devansh/media}") String dir) {
        return args -> {
            Path path = Paths.get(dir);
            try {
                Files.createDirectories(path);
            } catch (Exception e) {
                log.warn("Media folder {} could not be created: {}. Uploads will fail until this "
                        + "is fixed — the volume is probably owned by root while this process is not.",
                        dir, e.getMessage());
                return;
            }
            if (Files.isWritable(path)) {
                log.info("Media folder {} is writable.", dir);
            } else {
                log.warn("Media folder {} exists but is not writable by this process. Uploads will "
                        + "fail. A docker volume arrives owned by root; the Dockerfile creates and "
                        + "chowns this path so a fresh volume inherits the right owner.", dir);
            }
        };
    }
}
