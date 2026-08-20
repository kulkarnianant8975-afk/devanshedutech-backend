package com.devanshedutech.controller;

import com.devanshedutech.service.InboundWhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WhatsAppWebhookSignatureTest {

    private static final String SECRET = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
    private static final String BODY =
            "{\"object\":\"whatsapp_business_account\",\"entry\":[{\"id\":\"973850178987794\"}]}";

    private WhatsAppWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new WhatsAppWebhookController(mock(InboundWhatsAppService.class));
        ReflectionTestUtils.setField(controller, "appSecret", SECRET);
        ReflectionTestUtils.setField(controller, "verifyToken", "devansh-verify");
    }

    /** The header Meta would send for this body and secret. */
    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(body.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
        return "sha256=" + hex;
    }

    @Test
    @DisplayName("a genuine Meta request is accepted")
    void genuineSignaturesPass() throws Exception {
        assertTrue(controller.signatureValid(sign(BODY, SECRET), BODY.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a request signed with the wrong secret is refused")
    void forgedSignaturesFail() throws Exception {
        // This is the attack that matters. The endpoint is public, creates leads, and makes the
        // institute send WhatsApp messages to whatever number is in the payload — so without
        // this check anyone who learns the URL could message arbitrary people at its expense.
        assertFalse(controller.signatureValid(sign(BODY, "not-the-real-secret"),
                BODY.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a body altered after signing is refused")
    void tamperedBodiesFail() throws Exception {
        String header = sign(BODY, SECRET);
        String tampered = BODY.replace("973850178987794", "000000000000000");
        assertFalse(controller.signatureValid(header, tampered.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a missing or malformed signature is refused")
    void missingSignaturesFail() {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        assertFalse(controller.signatureValid(null, body));
        assertFalse(controller.signatureValid("", body));
        assertFalse(controller.signatureValid("sha1=deadbeef", body), "wrong algorithm prefix");
        assertFalse(controller.signatureValid("sha256=", body));
        assertFalse(controller.signatureValid("sha256=notvalidhex", body));
        assertFalse(controller.signatureValid(sign_safe(), null));
    }

    private String sign_safe() {
        try { return sign(BODY, SECRET); } catch (Exception e) { return "sha256=x"; }
    }

    @Test
    @DisplayName("with no app secret configured, nothing is processed")
    void anUnconfiguredSecretRefusesEverything() throws Exception {
        // Failing closed. An endpoint that cannot prove who is calling it must not act, and the
        // log says exactly which setting is missing.
        ReflectionTestUtils.setField(controller, "appSecret", "");
        assertFalse(controller.signatureValid(sign(BODY, SECRET), BODY.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------- the ownership handshake ----------------

    @Test
    @DisplayName("Meta's challenge is echoed back when the token matches")
    void verificationEchoesTheChallenge() {
        var response = controller.verify("subscribe", "devansh-verify", "1158201444");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("1158201444", response.getBody());
    }

    @Test
    @DisplayName("a wrong verify token is refused")
    void wrongVerifyTokensAreRefused() {
        assertEquals(403, controller.verify("subscribe", "guessed", "1158201444").getStatusCode().value());
        assertEquals(403, controller.verify("subscribe", null, "1158201444").getStatusCode().value());
        assertEquals(403, controller.verify("unsubscribe", "devansh-verify", "x").getStatusCode().value());
    }

    @Test
    @DisplayName("with no verify token configured the handshake is refused, not guessed at")
    void anUnconfiguredVerifyTokenRefuses() {
        ReflectionTestUtils.setField(controller, "verifyToken", "");
        assertEquals(503, controller.verify("subscribe", "anything", "x").getStatusCode().value());
    }
}
