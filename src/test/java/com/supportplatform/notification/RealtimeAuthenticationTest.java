package com.supportplatform.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers realtime-domain.md §3: the /ws handshake is gated by the same session auth as every REST endpoint. */
class RealtimeAuthenticationTest extends AbstractRealtimeIntegrationTest {

    @Test
    void aHandshakeWithNoSessionCookieIsRejected() {
        assertThatThrownBy(() -> connectStomp("JSESSIONID=not-a-real-session"))
                .isInstanceOf(Exception.class);
    }
}
