package com.devanshedutech.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which channel is used, and what happens to phone numbers and attachment URLs on the way out.
 *
 * <p>The URL handling is the part worth guarding. WhatsApp fetches media from its own servers, so
 * a relative path or a localhost address is accepted by the API and then never arrives — a
 * failure that looks like success, which is the worst kind to debug days later.</p>
 */
class WhatsAppSenderTest {

    private WhatsAppSender sender(String apiKey, String baseUrl) {
        AiSensyChannel provider = new AiSensyChannel(new RestTemplateBuilder());
        ReflectionTestUtils.setField(provider, "apiKey", apiKey);
        ReflectionTestUtils.setField(provider, "endpoint", "http://localhost:1/never-called");
        ReflectionTestUtils.setField(provider, "campaign", "crm_message");

        WhatsAppSender s = new WhatsAppSender(noMeta(), provider, new ManualWhatsAppChannel());
        ReflectionTestUtils.setField(s, "publicBaseUrl", baseUrl);
        return s;
    }

    @Test
    @DisplayName("without an API key the manual hand-off is used, so the product still works")
    void fallsBackToManual() {
        WhatsAppSender s = sender("", "");
        assertFalse(s.sendsAutomatically());
        assertEquals("Manual WhatsApp", s.active().name());

        var result = s.send("9876543210", "Rohit", "Hello Rohit", List.of());
        assertFalse(result.sent());
        assertEquals("manual", result.status());
        assertTrue(result.handoffUrl().startsWith("https://wa.me/919876543210?text="));
    }

    @Test
    @DisplayName("with an API key the provider takes over")
    void usesProviderWhenConfigured() {
        WhatsAppSender s = sender("some-key", "https://crm.example.com");
        assertTrue(s.sendsAutomatically());
        assertEquals("AiSensy", s.active().name());
    }

    @Test
    @DisplayName("a ten-digit Indian number gets its country code, and one that has it does not get two")
    void countryCodeHandling() {
        assertTrue(sender("", "").send("9876543210", "R", "Hi", List.of())
                .handoffUrl().contains("wa.me/919876543210"));

        String already = sender("", "").send("919876543210", "R", "Hi", List.of()).handoffUrl();
        assertTrue(already.contains("wa.me/919876543210"));
        assertFalse(already.contains("9191"));
    }

    @Test
    @DisplayName("a number as a student typed it is normalised, not passed through raw")
    void rawNumbersAreNormalised() {
        // The stored number is whatever was typed. My earlier test passed clean digits and so
        // missed that the caller was handing over "+91 98765 43210", which produces a link with
        // a space and a plus in it — broken, and only visible by actually opening it.
        for (String typed : new String[]{"+91 98765 43210", "098765 43210", "9876543210", "91-98765-43210"}) {
            var result = sender("", "").send(typed, "Rohit", "Hi", List.of());
            assertNotNull(result.handoffUrl(), typed + " produced no link");
            assertTrue(result.handoffUrl().contains("wa.me/919876543210"),
                    typed + " did not normalise to the same number");
            assertFalse(result.handoffUrl().contains(" "), "a space breaks the link");
            assertFalse(result.handoffUrl().contains("+"), "a plus is not valid in a wa.me path");
        }
    }

    @Test
    @DisplayName("the message is encoded, so punctuation does not truncate the link")
    void messageIsEncoded() {
        String url = sender("", "").send("9876543210", "Rohit",
                "Hi Rohit! Fees & timings — details?", List.of()).handoffUrl();

        assertFalse(url.contains(" "));
        assertTrue(url.contains("%26"), "an unescaped ampersand would cut the message short");
    }

    @Test
    @DisplayName("a missing phone number fails clearly rather than sending nowhere")
    void missingPhoneFails() {
        var result = sender("", "").send(null, "Rohit", "Hi", List.of());
        assertFalse(result.sent());
        assertNull(result.handoffUrl());
        assertTrue(result.detail().toLowerCase().contains("phone"));
    }

    @Test
    @DisplayName("attachments ride along in a manual hand-off rather than being silently dropped")
    void manualHandoffCarriesAttachments() {
        // This used to assert the opposite: that the counsellor was TOLD what to attach. That
        // reads as reasonable and is not — the files are on the server, so acting on it means
        // downloading each one to a phone mid-conversation, and the brochure never arrived.
        // A deep link still cannot carry a file, so the file goes in as a link instead.
        var result = sender("", "").send("9876543210", "Rohit", "Hi",
                List.of(new WhatsAppChannel.Attachment("Data Analytics — syllabus", "PDF",
                        "https://www.devanshedutech.com/api/public/a/tok")));

        String text = java.net.URLDecoder.decode(
                result.handoffUrl().substring(result.handoffUrl().indexOf("?text=") + 6),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("Data Analytics — syllabus"), "the file is named in the message");
        assertTrue(text.contains("https://www.devanshedutech.com/api/public/a/tok"),
                "and reachable, which is the only form a deep link can carry");
    }

    @Test
    @DisplayName("the provider refuses rather than pretending, when it has no key")
    void providerWithoutKeyDoesNotClaimSuccess() {
        AiSensyChannel provider = new AiSensyChannel(new RestTemplateBuilder());
        ReflectionTestUtils.setField(provider, "apiKey", "");

        var result = provider.send("9876543210", "Rohit", "Hi", List.of());
        assertFalse(result.sent());
        assertEquals("failed", result.status());
    }

    @Test
    @DisplayName("a provider that cannot be reached reports failure without leaking the key")
    void unreachableProviderFailsSafely() {
        AiSensyChannel provider = new AiSensyChannel(new RestTemplateBuilder());
        ReflectionTestUtils.setField(provider, "apiKey", "secret-key-value");
        ReflectionTestUtils.setField(provider, "endpoint", "http://127.0.0.1:1/nothing-here");
        ReflectionTestUtils.setField(provider, "campaign", "crm_message");

        var result = provider.send("9876543210", "Rohit", "Hi", List.of());
        assertFalse(result.sent());
        assertEquals("failed", result.status());
        assertFalse(result.detail().contains("secret-key-value"),
                "the provider echoes the request back on error; the key must not reach the browser");
        assertTrue(result.detail().contains("not been sent"),
                "a counsellor must know the message did not go");
    }

    /** A Cloud API channel with no credentials, so these tests exercise the other channels. */
    private static MetaCloudChannel noMeta() {
        return new MetaCloudChannel(new org.springframework.boot.web.client.RestTemplateBuilder());
    }
}
