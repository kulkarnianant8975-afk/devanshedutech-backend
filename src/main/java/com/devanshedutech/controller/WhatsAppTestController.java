package com.devanshedutech.controller;

import com.devanshedutech.channel.MetaCloudChannel;
import com.devanshedutech.channel.WhatsAppChannel;
import com.devanshedutech.channel.WhatsAppSender;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proving the WhatsApp connection works before any student is on the other end.
 *
 * <p>Testing a messaging integration by sending to a real lead is a bad trade: the failure modes
 * — an expired token, a number not on the allowed list, an unapproved template — are invisible
 * until a message does not arrive, and by then it has not arrived for somebody who matters.
 * These two endpoints make the whole thing checkable against your own phone.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/whatsapp")
public class WhatsAppTestController {

    private final WhatsAppSender sender;

    public WhatsAppTestController(WhatsAppSender sender) {
        this.sender = sender;
    }

    /**
     * What is connected, without sending anything.
     *
     * <p>Deliberately reports no part of any key. Whether a token exists is the useful fact;
     * the token itself on an admin screen is one screenshot away from being public.</p>
     */
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public Map<String, Object> status() {
        WhatsAppChannel active = sender.active();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", active.name());
        out.put("sendsAutomatically", active.canSendAutomatically());
        out.put("canTest", sender.metaChannel() != null);
        out.put("detail", active.canSendAutomatically()
                ? "Messages go to students directly through " + active.name() + "."
                : "Not connected. A swipe opens the message in the counsellor's own WhatsApp "
                  + "instead, which works but means a person presses send.");
        return out;
    }

    @Data
    public static class TestRequest {
        /** Your own number. On a Meta test number it must be one of the verified recipients. */
        private String phone;
    }

    /**
     * Sends the approved template to a number you control.
     *
     * <p>A template rather than a plain message on purpose: your own phone has never messaged
     * the institute, so the twenty-four hour window has never opened and a text would be
     * refused. This is also exactly the path a real first contact takes.</p>
     */
    @PostMapping("/test")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public Map<String, Object> test(@RequestBody TestRequest request) {
        MetaCloudChannel meta = sender.metaChannel();
        if (meta == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Test sending only works on Meta's Cloud API. Add the access token and "
                    + "phone number id, or set the channel to meta if another one is configured.");
        }
        String phone = request == null ? null : request.getPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter the number to test with, including the country code.");
        }

        // The channel owns what counts as a usable number, so there is one rule rather than a
        // second copy here that can drift from it.
        WhatsAppChannel.SendResult result = meta.sendTemplate(phone);
        log.info("WhatsApp test send: {}", result.status());

        return Map.of(
                "sent", result.sent(),
                "detail", result.detail(),
                "nextStep", result.sent()
                        ? "Reply to that message from your phone. That opens the 24-hour window, "
                          + "after which a normal message with attachments can be sent to you."
                        : "Nothing was sent. Fix the above and try again.");
    }

    /**
     * The full pack path, once the window is open.
     *
     * <p>Separate from the template test because it can only work after you have replied. Run
     * it second and it proves the part that actually matters day to day: a real message with
     * real attachments, fetched by WhatsApp from this server's public address.</p>
     */
    @PostMapping("/test-message")
    @PreAuthorize("hasAuthority('PERM_SETTINGS_MANAGE')")
    public Map<String, Object> testMessage(@RequestBody TestRequest request) {
        String phone = request == null ? null : request.getPhone();
        if (phone == null || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter the number to test with, including the country code.");
        }
        if (!sender.sendsAutomatically()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No WhatsApp channel is connected, so there is nothing to test.");
        }

        WhatsAppChannel.SendResult result = sender.send(phone, "Test",
                "This is a test message from the Devansh Edu-Tech CRM. "
                + "If you can read this, sending works.",
                List.of());

        return Map.of(
                "sent", result.sent(),
                "detail", result.detail(),
                "nextStep", result.sent()
                        ? "Sending works. Attachments are tested by sending a real pack from a lead."
                        : "If this says the window has closed, reply to the template message "
                          + "first and try again.");
    }
}
