package com.supportplatform.notification;

import com.supportplatform.auth.AuthenticatedPrincipal;
import com.supportplatform.notification.dto.NotificationUsageResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The dashboard's view of notification volume: what is going out on this
 * tenant's WhatsApp number, and to how many people.
 *
 * <p>Deliberately <em>not</em> mounted under {@code /api/v1/notifications}.
 * {@code ApiKeySecurityConfig} claims that whole prefix for its stateless
 * API-key chain, so an endpoint placed there would demand an API key from a
 * logged-in Owner — which is the wrong credential for a person at a
 * browser, and would mean handing a machine credential to a human to read
 * their own numbers.
 *
 * <p>The quantity worth watching here is sends against distinct
 * recipients. A day where one climbs and the other does not is not a busier
 * day; it is the same people being messaged more often.
 */
@RestController
@RequestMapping("/api/v1/notification-usage")
public class NotificationUsageController {

    private final NotificationUsageService usageService;

    public NotificationUsageController(NotificationUsageService usageService) {
        this.usageService = usageService;
    }

    /**
     * Dates are whole UTC days, both bounds inclusive. Omitting them gives
     * the last 30 days.
     */
    @GetMapping
    public NotificationUsageResponse usage(
            @AuthenticationPrincipal AuthenticatedPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return usageService.forDashboard(principal.getTenantId(), principal.getRole(), from, to);
    }
}
