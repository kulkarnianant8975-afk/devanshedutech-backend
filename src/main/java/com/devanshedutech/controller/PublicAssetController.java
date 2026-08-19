package com.devanshedutech.controller;

import com.devanshedutech.service.AssetTrackingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * The link a student actually taps.
 *
 * <p>Records the open against their lead and forwards them to the file. Deliberately short and
 * unauthenticated: this URL arrives on a phone over WhatsApp, and anything that asks a
 * prospective student to sign in before reading a syllabus loses the student.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/public/a")
public class PublicAssetController {

    private final AssetTrackingService tracking;

    public PublicAssetController(AssetTrackingService tracking) {
        this.tracking = tracking;
    }

    @GetMapping("/{token}")
    public ResponseEntity<Void> open(@PathVariable String token, HttpServletRequest request) {
        return tracking.open(token, request.getHeader(HttpHeaders.USER_AGENT))
                .map(opened -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(opened.targetUrl()))
                        .<Void>build())
                // An unknown token gets a plain 404. The destination comes from our own asset
                // list, never from the request, so this endpoint cannot be pointed at an
                // arbitrary address by anyone who guesses at tokens.
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
