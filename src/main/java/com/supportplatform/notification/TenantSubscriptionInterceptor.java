package com.supportplatform.notification;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The WebSocket-world equivalent of Rule 3 (realtime-domain.md §3): a
 * {@code SUBSCRIBE} to {@code /topic/tenants/{tenantId}/...} is only
 * allowed when {@code tenantId} matches the caller's own tenant, read
 * from the session attributes {@link PrincipalHandshakeInterceptor} set
 * at handshake time. Never trust the destination string alone — it's
 * client-supplied.
 */
@Component
public class TenantSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern TENANT_TOPIC = Pattern.compile("^/topic/tenants/([0-9a-fA-F-]{36})(/.*)?$");

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() != StompCommand.SUBSCRIBE) {
            return message;
        }

        String destination = accessor.getDestination();
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        Object callerTenantId = sessionAttributes == null ? null : sessionAttributes.get(PrincipalHandshakeInterceptor.TENANT_ID_ATTRIBUTE);

        if (!(callerTenantId instanceof UUID tenantId) || !isAuthorized(destination, tenantId)) {
            throw new AccessDeniedException("Not authorized to subscribe to this destination");
        }
        return message;
    }

    private boolean isAuthorized(String destination, UUID callerTenantId) {
        if (destination == null) {
            return false;
        }
        Matcher matcher = TENANT_TOPIC.matcher(destination);
        return matcher.matches() && callerTenantId.toString().equalsIgnoreCase(matcher.group(1));
    }
}
