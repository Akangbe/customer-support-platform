package com.supportplatform.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * STOMP over WebSocket (realtime-domain.md §2), server-to-client only —
 * no {@code /app} destination prefix, since agents never publish over
 * this channel; replies still go through the REST API. In-memory broker
 * (ADR-018): fine for one instance, revisited only when a second one is
 * actually running.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final PrincipalHandshakeInterceptor handshakeInterceptor;
    private final TenantSubscriptionInterceptor subscriptionInterceptor;

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    public WebSocketConfig(PrincipalHandshakeInterceptor handshakeInterceptor, TenantSubscriptionInterceptor subscriptionInterceptor) {
        this.handshakeInterceptor = handshakeInterceptor;
        this.subscriptionInterceptor = subscriptionInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(allowedOrigins.toArray(new String[0]));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(subscriptionInterceptor);
    }
}
