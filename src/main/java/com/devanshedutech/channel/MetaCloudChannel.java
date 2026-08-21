package com.devanshedutech.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends through Meta's own WhatsApp Cloud API.
 *
 * <p>This is the channel to test on. Meta gives every developer app a test number with free
 * messages and no provider account in between, so the institute can watch a real message arrive
 * on a real phone before committing to anyone's pricing.</p>
 *
 * <p>It differs from a reseller in one way that matters here: the Cloud API sends
 * <strong>one message per request</strong>. A cover note with three attachments is four calls,
 * not one call with the media bolted onto a template header. That is more work but it is also
 * how a person sends things — the note arrives, then the syllabus, then the fee sheet — and it
 * means a single failed attachment does not take the message down with it.</p>
 *
 * <p>Only active when both a token and a phone number id are configured. Without them the
 * sender falls back, so a missing or expired token degrades to a counsellor sending by hand
 * rather than to messages silently vanishing.</p>
 */
@Slf4j
@Component
public class MetaCloudChannel implements WhatsAppChannel {

    private final RestTemplate http;

    @Value("${app.crm.whatsapp.meta.access-token:}")
    private String accessToken;

    /** Not the phone number itself — the numeric id Meta shows beside it in the dashboard. */
    @Value("${app.crm.whatsapp.meta.phone-number-id:}")
    private String phoneNumberId;

    @Value("${app.crm.whatsapp.meta.api-version:v25.0}")
    private String apiVersion;

    /**
     * The approved template used to open a conversation.
     *
     * <p>Defaults to {@code hello_world}, which Meta pre-approves on every test number. It is
     * the one template that is guaranteed to work before anything has been submitted for
     * review, which is exactly what a first test needs.</p>
     */
    @Value("${app.crm.whatsapp.meta.template:hello_world}")
    private String template;

    @Value("${app.crm.whatsapp.meta.template-language:en_US}")
    private String templateLanguage;

    public MetaCloudChannel(RestTemplateBuilder builder) {
        // Short timeouts on purpose: a counsellor waiting on a spinner needs an answer, and a
        // provider that is slow today is better reported than waited on.
        //
        // The JDK HTTP client, not the default HttpURLConnection one.
        //
        // Meta answers a bad token with 401 and a WWW-Authenticate header. HttpURLConnection
        // responds by trying to re-authenticate, cannot retry a body it has already streamed,
        // and throws an I/O error — discarding Meta's response entirely. The practical effect
        // was that an expired token reported "WhatsApp could not be reached", sending somebody
        // to look for a network fault when the fix was a new token. This client returns the 401
        // and its body like any other response.
        java.net.http.HttpClient jdkClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(jdkClient);
        factory.setReadTimeout(Duration.ofSeconds(10));

        // Errors are not thrown either, so the code that says what went wrong is read directly
        // rather than dug out of an exception.
        this.http = builder
                .requestFactory(() -> factory)
                .errorHandler(new org.springframework.web.client.ResponseErrorHandler() {
                    @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) {
                        return false;
                    }
                    @Override public void handleError(org.springframework.http.client.ClientHttpResponse r) {
                        // Never called: nothing is treated as an error at this layer.
                    }
                })
                .build();
    }

    @Override
    public String name() { return "WhatsApp Cloud API"; }

    @Override
    public boolean canSendAutomatically() {
        return notBlank(accessToken) && notBlank(phoneNumberId);
    }

    @Override
    public SendResult send(String toPhone, String studentName, String message, List<Attachment> attachments) {
        if (!canSendAutomatically()) {
            return SendResult.failed("WhatsApp is not connected. Add the access token and phone "
                    + "number id from Meta to enable sending.");
        }
        String to = PhoneNumbers.toWhatsApp(toPhone);
        if (to == null) {
            return SendResult.failed("This lead has no usable phone number.");
        }

        List<String> notes = new ArrayList<>();

        // The cover note first, so the student sees why the files are arriving before they do.
        SendResult text = post(to, textBody(to, message), "message");
        if (!text.sent()) return text;
        notes.add("Sent through WhatsApp.");

        // Media is fetched by WhatsApp from the URL we give it, so an address this server cannot
        // publish is worse than no attachment: it is accepted and then never arrives.
        int sentFiles = 0;
        int skipped = 0;
        for (Attachment a : attachments) {
            Map<String, Object> body = mediaBody(to, a);
            if (body == null) { skipped++; continue; }
            SendResult one = post(to, body, a.name());
            if (one.sent()) {
                sentFiles++;
            } else {
                // Reported rather than swallowed. A counsellor who believes the syllabus went and
                // discovers days later that it did not has lost the student's momentum.
                notes.add(a.name() + " did not send: " + one.detail());
            }
        }
        if (sentFiles > 0) notes.add(sentFiles + " attachment(s) delivered.");
        if (skipped > 0) {
            notes.add(skipped + " attachment(s) were skipped because they are not publicly "
                    + "reachable. Set app.crm.public-base-url to a public address.");
        }
        return SendResult.accepted(String.join(" ", notes));
    }

    @Override
    public boolean supportsMenus() { return true; }

    /**
     * Sends a tappable list of choices.
     *
     * <p>WhatsApp's limits are tight and silent — a row title over 24 characters, or more than
     * ten rows, and the whole message is rejected. Course names here run to 29 characters, so
     * the title is trimmed and the full name carried in the description, where there is room.</p>
     */
    @Override
    public SendResult sendMenu(String toPhone, String body, String buttonLabel, List<MenuRow> rows) {
        if (!canSendAutomatically()) {
            return SendResult.failed("WhatsApp is not connected.");
        }
        String to = PhoneNumbers.toWhatsApp(toPhone);
        if (to == null) return SendResult.failed("This lead has no usable phone number.");
        if (rows == null || rows.isEmpty()) return SendResult.failed("A menu needs at least one choice.");

        List<Map<String, Object>> items = new ArrayList<>();
        for (MenuRow row : rows.stream().limit(MAX_MENU_ROWS).toList()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", cut(row.id(), 200));
            item.put("title", cut(row.title(), 24));
            if (row.description() != null && !row.description().isBlank()) {
                item.put("description", cut(row.description(), 72));
            }
            items.add(item);
        }

        Map<String, Object> interactive = new LinkedHashMap<>();
        interactive.put("type", "list");
        interactive.put("body", Map.of("text", cut(body, 1024)));
        interactive.put("action", Map.of(
                "button", cut(buttonLabel, 20),
                "sections", List.of(Map.of("title", "Courses", "rows", items))));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", to);
        payload.put("type", "interactive");
        payload.put("interactive", interactive);

        SendResult result = post(to, payload, "course menu");
        return result.sent() ? SendResult.accepted("Course menu sent.") : result;
    }

    /** WhatsApp rejects a list with more rows than this — the whole message, not the extras. */
    private static final int MAX_MENU_ROWS = 10;

    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "\u2026";
    }

    /**
     * Opens a conversation with the approved template.
     *
     * <p>Needed because WhatsApp only allows a free-form message within twenty-four hours of the
     * student's last one. On a test number this is also the <em>only</em> thing that will send,
     * since a test recipient has never messaged the institute.</p>
     */
    public SendResult sendTemplate(String toPhone) {
        if (!canSendAutomatically()) {
            return SendResult.failed("WhatsApp is not connected. Add the access token and phone "
                    + "number id from Meta to enable sending.");
        }
        String to = PhoneNumbers.toWhatsApp(toPhone);
        if (to == null) return SendResult.failed("That is not a usable phone number.");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("to", to);
        body.put("type", "template");
        body.put("template", Map.of(
                "name", template,
                "language", Map.of("code", templateLanguage)));

        SendResult result = post(to, body, "template " + template);
        return result.sent()
                ? SendResult.accepted("Template \"" + template + "\" sent to " + to
                        + ". It should arrive within a few seconds.")
                : result;
    }

    private Map<String, Object> textBody(String to, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", to);
        body.put("type", "text");
        // preview_url on, because a counsellor's message often is a link to a form or a video
        // and a bare URL with no preview looks like spam.
        body.put("text", Map.of("preview_url", true, "body", message));
        return body;
    }

    /** Null when the attachment cannot be sent — an unreachable URL, or a plain link. */
    private Map<String, Object> mediaBody(String to, Attachment a) {
        if (a.url() == null || !a.url().startsWith("http")) return null;

        String kind = switch (a.type() == null ? "" : a.type().toUpperCase(Locale.ROOT)) {
            case "PDF" -> "document";
            case "VIDEO" -> "video";
            case "IMAGE" -> "image";
            // A LINK is already written into the message text; sending it again as a file would
            // be a second copy of the same thing.
            default -> null;
        };
        if (kind == null) return null;

        Map<String, Object> media = new LinkedHashMap<>();
        media.put("link", a.url());
        if ("document".equals(kind)) {
            // Without a filename WhatsApp shows the raw URL as the document's name, which for a
            // tracked link is a string of random characters.
            media.put("filename", a.name() == null ? "Document.pdf" : ensurePdf(a.name()));
        } else {
            media.put("caption", a.name());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messaging_product", "whatsapp");
        body.put("recipient_type", "individual");
        body.put("to", to);
        body.put("type", kind);
        body.put(kind, media);
        return body;
    }

    private String ensurePdf(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name : name + ".pdf";
    }

    private SendResult post(String to, Map<String, Object> body, String what) {
        String url = "https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId + "/messages";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(accessToken);

        try {
            var response = http.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return SendResult.accepted("Sent.");
            }
            String responseBody = response.getBody();
            // Logged in full server-side; only the translated sentence goes back to the browser.
            log.error("WhatsApp Cloud API refused {} to {} ({}): {}",
                    what, to, response.getStatusCode(), responseBody);
            return SendResult.failed(explain(responseBody));
        } catch (RuntimeException e) {
            log.error("WhatsApp Cloud API call failed for {} to {}", what, to, e);
            return SendResult.failed("WhatsApp could not be reached. Nothing has been sent.");
        }
    }

    /**
     * Turns Meta's error code into something a person can act on.
     *
     * <p>Only the numeric code is read; the provider's own text is logged and never returned.
     * The three translated here are the ones that account for almost every failed first test,
     * and each has a fix that is not guessable from "the message was not accepted".</p>
     */
    String explain(String responseBody) {
        Integer code = errorCode(responseBody);
        if (code == null) {
            return "WhatsApp did not accept the message. It has not been sent.";
        }
        return switch (code) {
            case 190 -> "The WhatsApp access token has expired. A test token from Meta lasts "
                    + "24 hours — generate a new one in the dashboard, or create a permanent "
                    + "token from a System User for anything beyond testing.";
            case 131030 -> "That number is not on the test number's allowed list. Meta only lets "
                    + "a test number message up to five recipients you have added and verified "
                    + "in the WhatsApp dashboard.";
            case 131026 -> "That number cannot receive WhatsApp messages. Check it has WhatsApp "
                    + "installed and the country code is right.";
            case 131047 -> "This student has not messaged in over 24 hours, so WhatsApp only "
                    + "allows an approved template until they reply.";
            case 132001 -> "That template does not exist or is not approved on this number. "
                    + "Check the name and language exactly match the dashboard.";
            case 100 -> "WhatsApp rejected the request as malformed. Check the phone number id "
                    + "is the id from the dashboard and not the phone number itself.";
            case 133010 -> "That phone number is not registered on the Cloud API yet.";
            default -> "WhatsApp did not accept the message (error " + code + "). "
                    + "It has not been sent.";
        };
    }

    /**
     * Reads only the numeric code out of the error body.
     *
     * <p>Deliberately not parsed into an object and passed around: nothing from a provider's
     * response should reach a browser, and taking one integer makes that impossible by
     * construction.</p>
     */
    private Integer errorCode(String body) {
        if (body == null || body.isBlank()) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"code\"\\s*:\\s*(\\d+)").matcher(body);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
