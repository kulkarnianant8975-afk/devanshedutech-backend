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

    /**
     * WhatsApp's own limits, not ours.
     *
     * <p>Meta rejects anything larger outright, so accepting a bigger file would only mean
     * storing something that can never be sent. A counsellor discovering that at the moment they
     * try to send it — rather than at the moment they upload it — is the failure worth avoiding.</p>
     *
     * <p>Sixteen megabytes is roughly a minute of decent 720p. Anything longer has to be a link,
     * and that is a property of WhatsApp rather than a decision made here.</p>
     */
    private static final Map<String, Long> LIMITS = Map.of(
            "VIDEO", 200L * 1024 * 1024,
            "IMAGE",   5L * 1024 * 1024,
            "PDF",   100L * 1024 * 1024);

    /**
     * The per-type limits, for anything that needs to stay consistent with them.
     *
     * <p>Exposed so a test can assert the framework's multipart limit sits above every one of
     * these. If it does not, files between the two limits are refused by the dispatcher before
     * this class runs, and the careful explanation below is unreachable.</p>
     */
    public static Map<String, Long> limits() {
        return LIMITS;
    }

    /**
     * The largest video WhatsApp will carry inside a message.
     *
     * <p>Meta's limit, and not one that can be worked around. A larger file is still perfectly
     * useful — it is hosted here and sent as a link the student taps, which streams from this
     * server. The only difference is whether it appears as a video bubble in the chat or as a
     * link, and a two-hundred megabyte film was never going to be a bubble.</p>
     */
    public static final long WHATSAPP_INLINE_VIDEO = 16L * 1024 * 1024;

    /**
     * WhatsApp plays only these, and silently fails on the rest.
     *
     * <p>H.264 with a "High" profile and B-frames does not play on Android clients — worth
     * knowing when a video uploads cleanly and then arrives unwatchable for half the students.</p>
     */
    private static final Map<String, List<String>> ACCEPTS = Map.of(
            "VIDEO", List.of("video/mp4", "video/3gpp"),
            "IMAGE", List.of("image/jpeg", "image/png"),
            "PDF",   List.of("application/pdf"));

    private static final List<String> TYPES = List.of("PDF", "VIDEO", "LINK", "IMAGE");

    private final AssetRepository assets;
    private final BrochureChunkRepository chunks;

    /** On disk, on a docker volume, so uploads survive a redeploy without living in the database. */
    @org.springframework.beans.factory.annotation.Value("${app.crm.media-dir:/var/lib/devansh/media}")
    private String mediaDir;

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
     * Uploads a document, video or image and files it as an asset.
     *
     * <p>Written to a docker volume rather than the database. A 16 MB video per row would bloat
     * every backup and every restore of a database whose real job is leads and their history —
     * and the restore is the moment you least want to be waiting on video.</p>
     *
     * <p>Both the size and the format are checked here rather than left to WhatsApp. A file
     * rejected at upload costs somebody thirty seconds; the same file rejected at send time costs
     * them a conversation with a student who is waiting.</p>
     */
    @PostMapping("/upload")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    @Transactional
    public Asset upload(@RequestParam("file") MultipartFile file,
                        @RequestParam("name") String name,
                        @RequestParam(value = "type", required = false, defaultValue = "PDF") String rawType,
                        @RequestParam(value = "courseId", required = false) String courseId) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That file is empty.");
        }
        String type = type(rawType);
        if (!LIMITS.containsKey(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only documents, videos and images can be uploaded. A link is added, not uploaded.");
        }

        long limit = LIMITS.get(type);
        if (file.getSize() > limit) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is " + human(file.getSize()) + ". WhatsApp refuses any " + type.toLowerCase(Locale.ROOT)
                    + " over " + human(limit) + " — this is Meta's limit, not ours, so a larger file "
                    + "could be stored but never sent. "
                    + ("VIDEO".equals(type)
                        ? "Either shorten it, export it smaller, or put it on YouTube and add the link."
                        : "Compress it and try again."));
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        List<String> allowed = ACCEPTS.get(type);
        if (allowed != null && allowed.stream().noneMatch(contentType::startsWith)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WhatsApp only accepts " + String.join(" or ", allowed) + " for a "
                    + type.toLowerCase(Locale.ROOT) + ". That file is " + (contentType.isEmpty() ? "of an unknown type" : contentType) + ".");
        }

        String id = UUID.randomUUID().toString();
        String extension = "VIDEO".equals(type) ? ".mp4" : "IMAGE".equals(type) ? ".jpg" : ".pdf";

        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(mediaDir);
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path target = dir.resolve(id + extension);
            file.transferTo(target);

            Asset asset = Asset.builder()
                    .id(id)
                    .key(uniqueKey(name))
                    .name(trimmed(name, "Give it a name a counsellor will recognise."))
                    .type(type)
                    // Served by this application, so opening it is recorded against the lead.
                    .url("/api/assets/" + id + "/download")
                    .courseId(blankToNull(courseId))
                    .sizeLabel(human(file.getSize()))
                    .sizeBytes(file.getSize())
                    .tracked(true)
                    .active(true)
                    .build();

            if ("VIDEO".equals(type) && file.getSize() > WHATSAPP_INLINE_VIDEO) {
                log.info("Uploaded {} '{}' ({}) — too large for a WhatsApp video message, so it "
                        + "will be sent as a streaming link.", type, name, human(file.getSize()));
            } else {
                log.info("Uploaded {} '{}' ({})", type, name, human(file.getSize()));
            }
            return assets.save(asset);

        } catch (java.io.IOException e) {
            log.error("Could not store the uploaded file in {}", mediaDir, e);
            // Named rather than generic. "Try again" is advice that cannot work when the storage
            // is unwritable, and it sends somebody to re-export their video instead of to the
            // one line of configuration that is actually wrong.
            boolean writable = java.nio.file.Files.isWritable(java.nio.file.Paths.get(mediaDir).getParent());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, writable
                    ? "The file could not be saved. Try again."
                    : "The media folder on the server is not writable, so nothing can be uploaded "
                      + "until that is fixed. This is a server setting, not a problem with your file.");
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

        String extension = "VIDEO".equals(asset.getType()) ? ".mp4"
                         : "IMAGE".equals(asset.getType()) ? ".jpg" : ".pdf";
        MediaType contentType = "VIDEO".equals(asset.getType()) ? MediaType.parseMediaType("video/mp4")
                              : "IMAGE".equals(asset.getType()) ? MediaType.IMAGE_JPEG
                              : MediaType.APPLICATION_PDF;
        String filename = asset.getName().replaceAll("[^A-Za-z0-9.-]+", "_") + extension;

        java.nio.file.Path onDisk = java.nio.file.Paths.get(mediaDir).resolve(id + extension);
        if (java.nio.file.Files.exists(onDisk)) {
            return ResponseEntity.ok()
                    .contentType(contentType)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(new org.springframework.core.io.FileSystemResource(onDisk));
        }

        // Anything uploaded before media moved to disk is still in the chunked rows.
        List<BrochureChunk> parts = chunks.findBySettingKeyOrderByChunkIndexAsc("ASSET_" + id);
        if (parts.isEmpty()) return ResponseEntity.notFound().build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (BrochureChunk part : parts) out.writeBytes(part.getData());

        return ResponseEntity.ok()
                .contentType(contentType)
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
