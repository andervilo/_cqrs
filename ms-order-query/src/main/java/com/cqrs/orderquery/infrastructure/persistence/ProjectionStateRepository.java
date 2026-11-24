package com.cqrs.orderquery.infrastructure.persistence;

import com.cqrs.orderquery.application.inbox.DecodedProjectionEvent;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class ProjectionStateRepository {
    public record State(long version,UUID eventId,byte[] contentHash) {}
    private final JdbcTemplate jdbc;
    public ProjectionStateRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public State lock(String consumer,DecodedProjectionEvent e) {
        jdbc.update("""
                INSERT INTO projection_state(consumer_name,event_type,projection_key,applied_version,deleted,updated_at)
                VALUES (?,?,?,0,false,UTC_TIMESTAMP(6)) AS incoming ON DUPLICATE KEY UPDATE projection_key=incoming.projection_key
                """,consumer,e.eventType().name(),e.projectionKey().toString());
        return jdbc.queryForObject("""
                SELECT applied_version,last_event_id,last_content_hash FROM projection_state
                WHERE consumer_name=? AND event_type=? AND projection_key=? FOR UPDATE
                """,(rs,n) -> new State(rs.getLong(1),rs.getBytes(2)==null?null:UuidBytes.to(rs.getBytes(2)),rs.getBytes(3)),
                consumer,e.eventType().name(),e.projectionKey().toString());
    }
    public void applied(String consumer,DecodedProjectionEvent e) {
        jdbc.update("""
                UPDATE projection_state SET applied_version=?,last_event_id=?,last_content_hash=?,last_operation=?,
                    deleted=?,updated_at=UTC_TIMESTAMP(6) WHERE consumer_name=? AND event_type=? AND projection_key=?
                """,e.projectionVersion(),UuidBytes.from(e.eventId()),e.contentHash(),e.operation().name(),
                e.operation()==com.cqrs.orderquery.domain.ProjectionOperation.DELETE,consumer,e.eventType().name(),e.projectionKey().toString());
    }
}
