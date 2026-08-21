package com.devanshedutech.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns refusals into something a person can act on.
 *
 * <p>Spring's default error body carries only the status and the path, so every message written
 * for a user — "marking a lead lost needs a reason", "that batch has already started" — was
 * discarded before it reached the browser. A counsellor saw "Bad Request" and had no way to
 * know what to change. Every deliberate refusal in this application was silent in exactly the
 * moment it needed to speak.</p>
 *
 * <p>The alternative, {@code server.error.include-message=always}, would also expose the
 * message of an unexpected exception, which is where stack traces, SQL and provider responses
 * live. Handling the two cases separately means the intentional messages get through and the
 * accidental ones do not.</p>
 */
@Slf4j
@RestControllerAdvice
public class ApiErrorHandler {

    /** A refusal the application meant. Its reason was written to be read. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleDeliberate(ResponseStatusException e,
                                                                HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        return ResponseEntity.status(e.getStatusCode())
                .body(body(e.getStatusCode().value(),
                        status == null ? "Error" : status.getReasonPhrase(),
                        e.getReason() == null ? "That request could not be completed." : e.getReason(),
                        request));
    }

    /** A refusal from validation. The field message is the useful part. */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException e,
            HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getDefaultMessage() == null ? f.getField() + " is invalid" : f.getDefaultMessage())
                .findFirst()
                .orElse("Please check the details and try again.");
        return ResponseEntity.badRequest().body(body(400, "Bad Request", message, request));
    }

    /**
     * Authorisation and authentication failures belong to the security filter chain.
     *
     * <p>Rethrown rather than answered here. A catch-all advice sits inside the dispatcher and
     * runs before the security filter sees the exception, so handling these would have quietly
     * converted every 403 into a 500 — and worse, would have decided the response itself,
     * bypassing the rule that an anonymous caller gets 401 while a signed-in one gets 403.</p>
     */
    @ExceptionHandler({
            org.springframework.security.access.AccessDeniedException.class,
            org.springframework.security.core.AuthenticationException.class})
    public void rethrowSecurity(Exception e) throws Exception {
        throw e;
    }

    /**
     * A file too large for the framework to even parse.
     *
     * <p>This is thrown inside the dispatcher, before any controller method runs, so
     * AssetController's careful explanation — the size, the limit, whose limit it is, what to do
     * instead — never gets the chance to speak. Without this handler it lands in the catch-all
     * below and a counsellor is told "something went wrong at our end" about a perfectly ordinary
     * file that is simply too big.</p>
     *
     * <p>The multipart limits are deliberately set above every per-type limit so that the
     * controller normally answers first. This is the backstop for when it does not.</p>
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException e,
            HttpServletRequest request) {
        log.warn("Upload refused on {} — larger than the server accepts", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body(413, "Payload Too Large",
                        "That file is too large for the server to accept. Videos can be up to "
                        + "200 MB — for anything bigger, put it on YouTube or Drive and add it "
                        + "as a link instead.", request));
    }

    /**
     * Anything unplanned.
     *
     * <p>Logged in full and reported as a fixed sentence. The exception's own message is exactly
     * what must not travel: it carries SQL, file paths and, in the case of a messaging provider,
     * the request that was echoed back including its API key.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(500, "Internal Server Error",
                        "Something went wrong at our end. Please try again.", request));
    }

    private Map<String, Object> body(int status, String error, String message, HttpServletRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamp", LocalDateTime.now().toString());
        out.put("status", status);
        out.put("error", error);
        out.put("message", message);
        out.put("path", request == null ? "" : request.getRequestURI());
        return out;
    }
}
