package com.cqrs.orderquery.infrastructure.persistence;

import com.cqrs.orderquery.application.inbox.DecodedProjectionEvent;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class InboxEventRepository {
    public record Seen(UUID id,byte[] hash) {}
    private final JdbcTemplate jdbc;
    public InboxEventRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Seen> byEventId(String consumer,DecodedProjectionEvent e) {
        return jdbc.query("SELECT event_id,content_hash FROM inbox_event WHERE consumer_name=? AND event_id=? FOR UPDATE",
                (rs,n) -> new Seen(UuidBytes.to(rs.getBytes(1)),rs.getBytes(2)),consumer,UuidBytes.from(e.eventId())).stream().findFirst();
    }
    public Optional<Seen> byRevision(String consumer,DecodedProjectionEvent e) {
        return jdbc.query("""
                SELECT event_id,content_hash FROM inbox_event WHERE consumer_name=? AND event_type=?
                  AND projection_key=? AND projection_version=? FOR UPDATE
                """,(rs,n) -> new Seen(UuidBytes.to(rs.getBytes(1)),rs.getBytes(2)),consumer,e.eventType().name(),
                e.projectionKey().toString(),e.projectionVersion()).stream().findFirst();
    }
    public void insert(String consumer,DecodedProjectionEvent e,String outcome) {
        jdbc.update("""
                INSERT INTO inbox_event(consumer_name,event_id,event_type,projection_key,projection_version,schema_version,
                    operation,content_hash,outcome,occurred_at,processed_at,source_topic,source_partition,source_offset)
                VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),?,?,?)
                """,consumer,UuidBytes.from(e.eventId()),e.eventType().name(),e.projectionKey().toString(),e.projectionVersion(),
                e.schemaVersion(),e.operation().name(),e.contentHash(),outcome,LocalDateTime.ofInstant(e.occurredAt(),ZoneOffset.UTC),
                e.topic(),e.partition(),e.offset());
    }
}
