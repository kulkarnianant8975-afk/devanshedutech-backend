package com.devanshedutech.channel;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetaCloudChannelTest {

    private MetaCloudChannel channel;

    @BeforeEach
    void setUp() {
        channel = new MetaCloudChannel(new RestTemplateBuilder());
    }

    private void configured() {
        ReflectionTestUtils.setField(channel, "accessToken", "EAAG-test-token");
        ReflectionTestUtils.setField(channel, "phoneNumberId", "123456789012345");
    }

    // ---------------- when it is on at all ----------------

    @Test
    @DisplayName("both the token and the phone number id are needed, not either")
    void bothCredentialsAreRequired() {
        assertFalse(channel.canSendAutomatically(), "nothing configured");

        ReflectionTestUtils.setField(channel, "accessToken", "EAAG-test-token");
        assertFalse(channel.canSendAutomatically(),
                "a token with no phone number id cannot address anything");

        ReflectionTestUtils.setField(channel, "phoneNumberId", "123456789012345");
        assertTrue(channel.canSendAutomatically());
    }

    @Test
    @DisplayName("with nothing configured it says what is missing rather than failing obscurely")
    void unconfiguredExplainsItself() {
        var result = channel.send("9876543210", "Omkar", "Hello", List.of());
        assertFalse(result.sent());
        assertTrue(result.detail().contains("access token"), result.detail());
    }

    @Test
    @DisplayName("an unusable number is refused before any call is made")
    void unusableNumbersAreRefused() {
        configured();
        var result = channel.send("12", "Omkar", "Hello", List.of());
        assertFalse(result.sent());
        assertTrue(result.detail().contains("phone number"), result.detail());
    }

    // ---------------- the error translations ----------------

    @ParameterizedTest
    @DisplayName("Meta's error codes become something a person can act on")
    @CsvSource({
            "190,    'token has expired'",
            "131030, 'allowed list'",
            "131026, 'cannot receive WhatsApp'",
            "131047, 'approved template'",
            "132001, 'not approved on this number'",
            "100,    'not the phone number itself'",
    })
    void errorCodesAreTranslated(int code, String expected) {
        // These six account for almost every failed first test, and each has a fix that is not
        // guessable from "the message was not accepted".
        String body = "{\"error\":{\"message\":\"...\",\"type\":\"OAuthException\",\"code\":" + code + "}}";
        assertTrue(channel.explain(body).contains(expected),
                "code " + code + " gave: " + channel.explain(body));
    }

    @Test
    @DisplayName("the expired-token message says how long a test token lasts")
    void theTokenMessageSaysWhatToDo() {
        // The single most common confusion: a Meta test token lasts 24 hours, so the integration
        // works one day and is silently broken the next.
        String explained = channel.explain("{\"error\":{\"code\":190}}");
        assertTrue(explained.contains("24 hours"), explained);
        assertTrue(explained.contains("System User"), explained);
    }

    @Test
    @DisplayName("an unrecognised code still produces a sentence, with the code to look up")
    void unknownCodesDegradeGracefully() {
        String explained = channel.explain("{\"error\":{\"code\":999999}}");
        assertTrue(explained.contains("999999"), explained);
        assertTrue(explained.contains("not been sent"), explained);
    }

    @Test
    @DisplayName("an unreadable error body does not become an exception")
    void unparseableBodiesAreSafe() {
        for (String body : new String[]{null, "", "   ", "<html>502 Bad Gateway</html>", "{}"}) {
            String explained = channel.explain(body);
            assertNotNull(explained);
            assertTrue(explained.contains("not been sent"), "body " + body + " gave: " + explained);
        }
    }

    @Test
    @DisplayName("the provider's own words are never repeated back")
    void providerTextIsNeverEchoed() {
        // Only the numeric code is read out of the response. Nothing a provider says should
        // reach a browser, and taking one integer makes that impossible by construction.
        String body = "{\"error\":{\"message\":\"Invalid OAuth access token EAAG-secret-abc123\","
                + "\"code\":190,\"fbtrace_id\":\"AbCdEf\"}}";
        String explained = channel.explain(body);

        assertFalse(explained.contains("EAAG-secret-abc123"), explained);
        assertFalse(explained.contains("AbCdEf"), explained);
        assertFalse(explained.contains("Invalid OAuth"), explained);
    }
}
