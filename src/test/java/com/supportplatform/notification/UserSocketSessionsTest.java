package com.supportplatform.notification;

import com.supportplatform.auth.UserSessionsRevokedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSocketSessionsTest {

    private final UserSocketSessions sockets = new UserSocketSessions();
    private final WebSocketHandler inner = mock(WebSocketHandler.class);
    private final WebSocketHandler decorated = sockets.decorate(inner);

    @Test
    void revokingAUserClosesEveryOneOfTheirSockets() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession laptop = open(user);
        WebSocketSession phone = open(user);

        sockets.onSessionsRevoked(new UserSessionsRevokedEvent(user));

        verify(laptop).close(CloseStatus.POLICY_VIOLATION);
        verify(phone).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void revokingAUserLeavesOtherUsersConnected() throws Exception {
        WebSocketSession other = open(UUID.randomUUID());

        sockets.onSessionsRevoked(new UserSessionsRevokedEvent(UUID.randomUUID()));

        verify(other, never()).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void aSocketThatAlreadyClosedIsNotClosedAgain() throws Exception {
        UUID user = UUID.randomUUID();
        WebSocketSession socket = open(user);
        decorated.afterConnectionClosed(socket, CloseStatus.NORMAL);

        sockets.onSessionsRevoked(new UserSessionsRevokedEvent(user));

        verify(socket, never()).close(CloseStatus.POLICY_VIOLATION);
        verify(inner).afterConnectionClosed(socket, CloseStatus.NORMAL);
    }

    private WebSocketSession open(UUID userId) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(PrincipalHandshakeInterceptor.USER_ID_ATTRIBUTE, userId);
        when(session.getAttributes()).thenReturn(attributes);
        decorated.afterConnectionEstablished(session);
        verify(inner).afterConnectionEstablished(session);
        return session;
    }
}
