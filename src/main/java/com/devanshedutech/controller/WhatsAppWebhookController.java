package com.devanshedutech.controller;

import com.devanshedutech.service.InboundWhatsAppService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Where WhatsApp delivers messages students send to the institute.
 *
 * <p>Two jobs. Meta calls it once with a challenge to prove we own the address, and thereafter
 * posts every incoming message to it.</p>
 *
 * <p><strong>Always answers 200, quickly.</strong> Meta retries anything else, and a webhook that
 * fails slowly turns one student's message into a queue of redeliveries. Whatever goes wrong
 * inside is logged and swallowed; the message id is recorded so a retry we do receive is
 * recognised rather than handled twice.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/webhooks/whatsapp")
public class WhatsAppWebhookController {

    private final InboundWhatsAppService inbound;

    /** The string you type into Meta's webhook form. Any value, as long as both sides match. */
    @Value("${app.crm.whatsapp.verify-token:}")
    private String verifyToken;

    /**
     * The Meta app secret, used to prove a request really came from Meta.
     *
     * <p>Not optional. This endpoint is public, creates leads, and causes the institute to send
     * WhatsApp messages to whatever number appears in the payload. Without a signature check,
     * anyone who learns the URL could make the institute message arbitrary people at its own
     * expense. With no secret configured the endpoint accepts the handshake and then refuses to
     * process anything, which fails visibly rather than dangerously.</p>
     */
    @Value("${app.crm.whatsapp.app-secret:}")
    private String appSecret;

    public WhatsAppWebhookController(InboundWhatsAppService inbound) {
        this.inbound = inbound;
    }

    /**
     * Meta's one-time ownership check.
     *
     * <p>It sends the verify token and a challenge; echoing the challenge back as plain text is
     * what completes the subscription.</p>
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (verifyToken == null || verifyToken.isBlank()) {
            log.error("WhatsApp webhook verification attempted, but no verify token is configured.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            log.info("WhatsApp webhook verified by Meta.");
            return ResponseEntity.ok(challenge);
        }
        // Deliberately no detail. A wrong token here is either a typo or somebody probing.
        log.warn("Rejected a WhatsApp webhook verification with a bad token.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                        @RequestBody(required = false) byte[] rawBody) {
        try {
            if (!signatureValid(signature, rawBody)) {
                // 200 on purpose. A rejected request is not Meta's to retry, and answering 403
                // tells anyone probing the URL that they have found something real.
                return ResponseEntity.ok().build();
            }

            Map<String, Object> payload = parseJson(rawBody);
            List<InboundWhatsAppService.Incoming> messages = inbound.parse(payload);
            for (InboundWhatsAppService.Incoming message : messages) {
                try {
                    inbound.handle(message);
                } catch (RuntimeException e) {
                    // One bad message must not cost the others in the same batch.
                    log.error("Could not handle inbound WhatsApp message {}", message.messageId(), e);
                }
            }
        } catch (Exception e) {
            log.error("WhatsApp webhook failed", e);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Checks Meta's HMAC over the exact bytes received.
     *
     * <p>Over the raw body rather than a re-serialised object, because any difference in key
     * order or spacing changes the hash. Compared in constant time so the comparison itself does
     * not leak how much of a forged signature was correct.</p>
     */
    boolean signatureValid(String header, byte[] body) {
        if (appSecret == null || appSecret.isBlank()) {
            log.error("Refusing a WhatsApp webhook: no app secret is configured, so the request "
                    + "cannot be proven to come from Meta. Set WHATSAPP_APP_SECRET.");
            return false;
        }
        if (header == null || !header.startsWith("sha256=") || body == null) {
            log.warn("Refusing a WhatsApp webhook with a missing or malformed signature.");
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);

            StringBuilder hex = new StringBuilder(expected.length * 2);
            for (byte b : expected) hex.append(String.format("%02x", b));

            boolean ok = java.security.MessageDigest.isEqual(
                    hex.toString().getBytes(StandardCharsets.UTF_8),
                    header.substring("sha256=".length()).getBytes(StandardCharsets.UTF_8));
            if (!ok) log.warn("Refusing a WhatsApp webhook whose signature does not match.");
            return ok;
        } catch (Exception e) {
            log.error("Could not verify a WhatsApp webhook signature", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(byte[] body) throws java.io.IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
    }
}
