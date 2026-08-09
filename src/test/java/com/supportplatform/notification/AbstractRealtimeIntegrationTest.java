package com.supportplatform.notification;

import com.supportplatform.AbstractIntegrationTest;
import com.supportplatform.auth.dto.LoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared setup for realtime tests. {@code AbstractIntegrationTest}'s
 * {@code MockHttpSession} objects are MockMvc's own in-process
 * construct — they were never registered with the real embedded
 * server's session manager, so they can't authenticate a real WebSocket
 * handshake. A WebSocket client needs a session cookie the real
 * embedded Tomcat (RANDOM_PORT) actually recognizes, which means
 * logging in over a real HTTP call, not through MockMvc.
 */
abstract class AbstractRealtimeIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;

    @BeforeEach
    void setUpStompClient() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new JacksonJsonMessageConverter());
    }

    /** Real login over the embedded server, returning the "JSESSIONID=..." cookie value the handshake needs. */
    protected String realLoginSessionCookie(String email, String password) {
        ResponseEntity<Void> response = RestClient.create("http://localhost:" + port).post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new LoginRequest(email, password))
                .retrieve()
                .toBodilessEntity();

        List<String> setCookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        return setCookies.stream()
                .filter(c -> c.startsWith("JSESSIONID="))
                .findFirst()
                .map(c -> c.split(";", 2)[0])
                .orElseThrow(() -> new IllegalStateException("Login did not set a session cookie"));
    }

    protected StompSession connectStomp(String sessionCookie) throws InterruptedException, ExecutionException, TimeoutException {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("Cookie", sessionCookie);
        return stompClient.connectAsync("ws://localhost:" + port + "/ws", headers, new StompSessionHandlerAdapter() {
                })
                .get(5, TimeUnit.SECONDS);
    }

    protected <T> BlockingQueue<T> subscribe(StompSession stompSession, String destination, Class<T> payloadType) {
        BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        stompSession.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return payloadType;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((T) payload);
            }
        });
        return queue;
    }
}
