package com.devanshedutech.controller;

import com.devanshedutech.model.Asset;
import com.devanshedutech.model.BrochureChunk;
import com.devanshedutech.repository.AssetRepository;
import com.devanshedutech.repository.BrochureChunkRepository;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The media library: everything a counsellor can attach to a message.
 *
 * <p>Until now these existed only as seeded rows, so adding a new brochure or a placement video
 * meant a deployment. That is the wrong shape for the thing that changes most often — a syllabus
 * is revised, a batch video is recorded, a form link moves — and it meant counsellors quietly
 * pasted links into the message text instead, where nothing could track or verify them.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    /** Enough for a syllabus or a fee sheet. Videos belong on a video host, not in a database. */
    private static final long MAX_UPLOAD_BYTES = 25L * 1024 * 1024;

    private static final int CHUNK_BYTES = 5 * 1024 * 1024;

    private static final List<String> TYPES = List.of("PDF", "VIDEO", "LINK", "IMAGE");

    private final AssetRepository assets;
    private final BrochureChunkRepository chunks;

    public AssetController(AssetRepository assets, BrochureChunkRepository chunks) {
        this.assets = assets;
        this.chunks = chunks;
    }

    /**
     * Everything available to attach.
     *
     * <p>Readable by anyone who works leads, because the person choosing what to send is the
     * counsellor, not an administrator.</p>
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_LEAD_VIEW_ALL','PERM_LEAD_VIEW_OWN')")
    public List<Asset> list(@RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return assets.findAll().stream()
                .filter(a -> includeInactive || a.isActive())
                .sorted(Comparator.comparing(Asset::getType).thenComparing(Asset::getName))
                .toList();
    }

    @Data
    public static class AssetRequest {
        private String name;
        private String type;
        private String url;
        private String courseId;
        private Boolean tracked;
        private Boolean active;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @Transactional
    public Asset create(@RequestBody AssetRequest request) {
        String name = trimmed(request.getName(), "Give it a name a counsellor will recognise.");
        String type = type(request.getType());
        String url = trimmed(request.getUrl(), "A link or file address is required.");

        if (!"PDF".equals(type) && !url.startsWith("http")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That does not look like a link. It should start with https://");
        }

        Asset asset = Asset.builder()
                .id(UUID.randomUUID().toString())
                .key(uniqueKey(name))
                .name(name)
                .type(type)
                .url(url)
                .courseId(blankToNull(request.getCourseId()))
                // Only links this application serves can be observed being opened. Claiming to
                // track a YouTube URL would put a number on the screen that means nothing.
                .tracked(Boolean.TRUE.equals(request.getTracked()) && url.startsWith("/"))
                .active(true)
                .build();

        log.info("Added asset '{}' ({})", name, type);
        return assets.save(asset);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @Transactional
    public Asset update(@PathVariable String id, @RequestBody AssetRequest request) {
        Asset asset = assets.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That item no longer exists."));

        if (request.getName() != null) asset.setName(trimmed(request.getName(), "A name is required."));
        if (request.getType() != null) asset.setType(type(request.getType()));
        if (request.getUrl() != null) asset.setUrl(trimmed(request.getUrl(), "A link is required."));
        if (request.getCourseId() != null) asset.setCourseId(blankToNull(request.getCourseId()));
        if (request.getTracked() != null) {
            asset.setTracked(request.getTracked() && asset.getUrl().startsWith("/"));
        }
        // Retired rather than deleted: packs reference assets by key, and a deletion would leave
        // a pack quietly sending one attachment fewer than it says it does.
        if (request.getActive() != null) asset.setActive(request.getActive());

        return assets.save(asset);
    }

    /**
     * Uploads a document and files it as an asset.
     *
     * <p>Stored in the database in chunks, like the course brochures already are. That is not
     * where files belong long term, but it is where this application's files already live, and
     * one storage mechanism that works beats a second one that is better in theory.</p>
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @Transactional
    public Asset upload(@RequestParam("file") MultipartFile file,
                        @RequestParam("name") String name,
                        @RequestParam(value = "courseId", required = false) String courseId) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That file is empty.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That file is " + human(file.getSize()) + ". The limit is 25 MB — a video "
                    + "belongs on YouTube with its link added here instead.");
        }

        String id = UUID.randomUUID().toString();
        try {
            byte[] bytes = file.getBytes();
            String key = "ASSET_" + id;
            chunks.deleteBySettingKey(key);
            for (int i = 0, n = 0; i < bytes.length; i += CHUNK_BYTES, n++) {
                chunks.save(BrochureChunk.builder()
                        .settingKey(key)
                        .chunkIndex(n)
                        .data(Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + CHUNK_BYTES)))
                        .build());
            }

            Asset asset = Asset.builder()
                    .id(id)
                    .key(uniqueKey(name))
                    .name(trimmed(name, "Give it a name a counsellor will recognise."))
                    .type("PDF")
                    // Served by this application, so opening it can be recorded against the lead.
                    .url("/api/public/assets/" + id + "/download")
                    .courseId(blankToNull(courseId))
                    .sizeLabel(human(bytes.length))
                    .tracked(true)
                    .active(true)
                    .build();

            log.info("Uploaded asset '{}' ({})", name, human(bytes.length));
            return assets.save(asset);

        } catch (java.io.IOException e) {
            log.error("Could not read the uploaded file", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The file could not be read. Try again.");
        }
    }

    /**
     * Serves an uploaded document.
     *
     * <p>Public because WhatsApp fetches attachments with its own servers and students open them
     * from a phone with no session. The id is a random UUID, which is what keeps it private
     * enough — the same posture the course brochures already take.</p>
     */
    @GetMapping("/{id}/download")
    @Transactional(readOnly = true)
    public ResponseEntity<Resource> download(@PathVariable String id) {
        Asset asset = assets.findById(id).orElse(null);
        if (asset == null || !asset.isActive()) return ResponseEntity.notFound().build();

        List<BrochureChunk> parts = chunks.findBySettingKeyOrderByChunkIndexAsc("ASSET_" + id);
        if (parts.isEmpty()) return ResponseEntity.notFound().build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (BrochureChunk part : parts) out.writeBytes(part.getData());

        String filename = asset.getName().replaceAll("[^A-Za-z0-9.-]+", "_") + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .body(new ByteArrayResource(out.toByteArray()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @Transactional
    public Map<String, String> retire(@PathVariable String id) {
        Asset asset = assets.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That item no longer exists."));
        asset.setActive(false);
        assets.save(asset);
        return Map.of("status", "retired",
                "detail", "It will no longer be offered, and packs that still name it are unaffected.");
    }

    // ---------------- helpers ----------------

    private String type(String raw) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(t)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose a type: " + String.join(", ", TYPES) + ".");
        }
        return t;
    }

    /**
     * A stable key derived from the name.
     *
     * <p>Packs reference assets by key rather than by id so a pack keeps working when something
     * is renamed, which means the key has to be unique and cannot change afterwards.</p>
     */
    private String uniqueKey(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("(^_+)|(_+$)", "");
        if (base.isEmpty()) base = "asset";
        if (base.length() > 50) base = base.substring(0, 50);

        String candidate = base;
        for (int n = 2; assets.countByKey(candidate) > 0; n++) candidate = base + "_" + n;
        return candidate;
    }

    private String trimmed(String s, String message) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        return t;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
