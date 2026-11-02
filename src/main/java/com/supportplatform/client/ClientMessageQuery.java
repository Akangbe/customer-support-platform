package com.supportplatform.client;

import com.supportplatform.notification.NotificationLog;
import com.supportplatform.notification.NotificationStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One client's messages, newest first. Built as text because each filter is
 * optional, and Postgres cannot type a null bound to {@code :x IS NULL}. Every
 * value is a bound parameter, and the account condition is unconditional.
 *
 * <p>Paged by position rather than offset, so a send arriving between two
 * pages cannot repeat or skip a row, and a deep page costs the same as the
 * first.
 */
@Repository
public class ClientMessageQuery {

    private final EntityManager entityManager;

    public ClientMessageQuery(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public record Criteria(Instant from, Instant to, NotificationStatus status, String template,
                           List<String> recipients, String cursor, int limit) {
    }

    public List<NotificationLog> find(UUID clientAccountId, Criteria criteria) {
        StringBuilder jpql = new StringBuilder("""
                SELECT n FROM NotificationLog n
                 WHERE n.clientAccountId = :clientAccountId
                   AND n.createdAt >= :from
                   AND n.createdAt <  :to
                """);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("clientAccountId", clientAccountId);
        parameters.put("from", criteria.from());
        parameters.put("to", criteria.to());

        if (criteria.status() != null) {
            jpql.append(" AND n.status = :status");
            parameters.put("status", criteria.status());
        }
        if (criteria.template() != null) {
            jpql.append(" AND n.templateName = :template");
            parameters.put("template", criteria.template());
        }
        if (criteria.recipients() != null) {
            jpql.append(" AND n.recipient IN :recipients");
            parameters.put("recipients", criteria.recipients());
        }
        if (criteria.cursor() != null) {
            Position position = decode(criteria.cursor());
            jpql.append(" AND (n.createdAt < :cursorAt OR (n.createdAt = :cursorAt AND n.id < :cursorId))");
            parameters.put("cursorAt", position.createdAt());
            parameters.put("cursorId", position.id());
        }
        jpql.append(" ORDER BY n.createdAt DESC, n.id DESC");

        TypedQuery<NotificationLog> query = entityManager.createQuery(jpql.toString(), NotificationLog.class);
        parameters.forEach(query::setParameter);
        return new ArrayList<>(query.setMaxResults(criteria.limit()).getResultList());
    }

    public static String encode(NotificationLog last) {
        String raw = last.getCreatedAt() + "|" + last.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            return new Position(Instant.parse(raw.substring(0, separator)), UUID.fromString(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This page link is not valid. Start again from the first page.");
        }
    }

    private record Position(Instant createdAt, UUID id) {
    }
}
