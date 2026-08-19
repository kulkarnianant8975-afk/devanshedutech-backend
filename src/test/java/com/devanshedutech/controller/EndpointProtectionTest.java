package com.devanshedutech.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails the build if a state-changing endpoint is added without authorisation.
 *
 * <p>The usual way authorisation regresses is not a wrong rule but a missing one: someone adds
 * a POST, tests it while signed in as an admin, and never notices it is open to the world. This
 * scans every controller and forces each mutating handler to either carry a {@code @PreAuthorize}
 * or be listed below with a stated reason.</p>
 *
 * <p>Adding an entry to the allowlist is meant to feel deliberate. If a new endpoint appears
 * here without a good reason in its comment, that is the review conversation this test exists
 * to start.</p>
 */
class EndpointProtectionTest {

    /** Endpoints that are intentionally reachable without a permission check. */
    private static final Set<String> INTENTIONALLY_OPEN = Set.of(
            // Signing in cannot require being signed in.
            "AuthController#login",
            // Gated by app.auth.self-registration-enabled and always grants the NONE role.
            "AuthController#register",
            // Ends your own session; harmless without a session.
            "AuthController#logout",
            // Authenticated by the filter chain and scoped to the caller's own account.
            "AuthController#updateProfilePicture",
            // The public enquiry form. This is the front door of the whole business.
            "LeadController#createLead",
            // The public contact form.
            "MessageController#createMessage",
            // The public website chatbot.
            "ChatController#chat"
    );

    private static final List<Class<? extends Annotation>> MUTATING = List.of(
            PostMapping.class, PutMapping.class, PatchMapping.class, DeleteMapping.class);

    @Test
    @DisplayName("every state-changing endpoint is authorised or explicitly listed as open")
    void noUnprotectedMutatingEndpoints() throws Exception {
        List<String> unprotected = new ArrayList<>();

        for (Class<?> controller : controllers()) {
            for (Method m : controller.getDeclaredMethods()) {
                if (!isMutating(m)) continue;
                String key = controller.getSimpleName() + "#" + m.getName();
                boolean guarded = m.isAnnotationPresent(PreAuthorize.class)
                        || controller.isAnnotationPresent(PreAuthorize.class);
                if (!guarded && !INTENTIONALLY_OPEN.contains(key)) {
                    unprotected.add(key);
                }
            }
        }

        assertTrue(unprotected.isEmpty(),
                "These endpoints change state with no authorisation check. Add @PreAuthorize, "
                + "or add them to INTENTIONALLY_OPEN with a reason: " + unprotected);
    }

    @Test
    @DisplayName("the open list does not rot — every entry still refers to a real endpoint")
    void allowlistHasNoStaleEntries() throws Exception {
        List<String> found = new ArrayList<>();
        for (Class<?> controller : controllers()) {
            for (Method m : controller.getDeclaredMethods()) {
                if (isMutating(m)) found.add(controller.getSimpleName() + "#" + m.getName());
            }
        }
        List<String> stale = INTENTIONALLY_OPEN.stream().filter(e -> !found.contains(e)).toList();
        assertTrue(stale.isEmpty(),
                "These entries no longer match any endpoint and should be removed: " + stale);
    }

    private boolean isMutating(Method m) {
        if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) return false;
        for (Class<? extends Annotation> a : MUTATING) {
            if (m.isAnnotationPresent(a)) return true;
        }
        RequestMapping rm = m.getAnnotation(RequestMapping.class);
        if (rm != null) {
            for (RequestMethod method : rm.method()) {
                if (method != RequestMethod.GET && method != RequestMethod.HEAD
                        && method != RequestMethod.OPTIONS) return true;
            }
        }
        return false;
    }

    private List<Class<?>> controllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.devanshedutech")) {
            out.add(Class.forName(bd.getBeanClassName()));
        }
        assertTrue(out.size() >= 10, "controller scan found only " + out.size()
                + " controllers, which suggests the scan itself is broken");
        return out;
    }
}
