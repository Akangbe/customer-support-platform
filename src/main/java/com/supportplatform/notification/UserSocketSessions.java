package com.supportplatform.notification;

import com.supportplatform.auth.UserSessionsRevokedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks open sockets by user, so that ending a user's sessions also stops the
 * live messages already flowing to them. The security filter only sees new
 * requests, and a socket that is already open makes none. Reconnecting then
 * fails at the handshake, because the session behind it has ended.
 */
@Component
public class UserSocketSessions implements WebSocketHandlerDecoratorFactory {

    private static final Logger log = LoggerFactory.getLogger(UserSocketSessions.class);

    private final Map<UUID, Set<WebSocketSession>> byUser = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                track(session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                untrack(session);
                super.afterConnectionClosed(session, status);
            }
        };
    }

    @EventListener
    void onSessionsRevoked(UserSessionsRevokedEvent event) {
        Set<WebSocketSession> sessions = byUser.remove(event.userId());
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            try {
                session.close(CloseStatus.POLICY_VIOLATION);
            } catch (IOException e) {
                log.warn("Could not close socket {} for revoked user {}", session.getId(), event.userId(), e);
            }
        }
    }

    private void track(WebSocketSession session) {
        UUID userId = userIdOf(session);
        if (userId != null) {
            byUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet()).add(session);
        }
    }

    private void untrack(WebSocketSession session) {
        UUID userId = userIdOf(session);
        if (userId != null) {
            byUser.computeIfPresent(userId, (id, sessions) -> {
                sessions.remove(session);
                return sessions.isEmpty() ? null : sessions;
            });
        }
    }

    private static UUID userIdOf(WebSocketSession session) {
        return (UUID) session.getAttributes().get(PrincipalHandshakeInterceptor.USER_ID_ATTRIBUTE);
    }
}
