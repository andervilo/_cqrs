package com.cqrs.ordercommand.infrastructure.persistence;

import com.cqrs.ordercommand.application.relay.OutboxMessage;
import com.cqrs.ordercommand.application.exception.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class OutboxRelayStore {
    private final JdbcTemplate jdbc;
    public OutboxRelayStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<OutboxMessage> lockNext(int maxAttempts) {
        return jdbc.query("""
                SELECT o.* FROM order_command.order_outbox o
                WHERE o.status IN ('PENDING','ERRO') AND o.attempt_count < ?
                  AND o.next_attempt_at <= CURRENT_TIMESTAMP
                  AND NOT EXISTS (
                    SELECT 1 FROM order_command.order_outbox previous
                    WHERE previous.event_type=o.event_type AND previous.projection_key=o.projection_key
                      AND previous.projection_version < o.projection_version AND previous.status <> 'SENDED'
                  )
                ORDER BY o.data_criacao,o.id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                """, (rs,n) -> new OutboxMessage(rs.getObject("id",UUID.class),rs.getString("event_type"),
                    rs.getString("operation"),rs.getString("projection_key"),rs.getLong("projection_version"),
                    rs.getInt("schema_version"),rs.getTimestamp("data_criacao").toInstant(),
                    rs.getString("payload"),rs.getInt("attempt_count")),maxAttempts).stream().findFirst();
    }
    public void sent(UUID id) {
        jdbc.update("""
                UPDATE order_command.order_outbox SET status='SENDED',
                  data_envio=greatest(clock_timestamp(),data_criacao), attempt_count=attempt_count+1,
                  next_attempt_at=NULL,last_error=NULL WHERE id=?
                """,id);
    }
    public void failed(UUID id,int attempts,Long retryDelayMs,String error) {
        jdbc.update("""
                UPDATE order_command.order_outbox SET status='ERRO',data_envio=NULL,attempt_count=?,
                  next_attempt_at=CASE WHEN ?::bigint IS NULL THEN NULL
                    ELSE clock_timestamp()+(? * INTERVAL '1 millisecond') END,last_error=? WHERE id=?
                """,attempts,retryDelayMs,retryDelayMs,error,id);
    }
    @Transactional
    public void retry(UUID id) {
        int updated=jdbc.update("""
                UPDATE order_command.order_outbox SET status='PENDING',attempt_count=0,
                  next_attempt_at=clock_timestamp(),last_error=NULL WHERE id=? AND status='ERRO'
                """,id);
        if(updated==0) {
            if(jdbc.queryForObject("SELECT count(*) FROM order_command.order_outbox WHERE id=?",Integer.class,id)==0)
                throw new NotFoundException("Outbox");
            throw new BusinessConflictException("Somente eventos ERRO podem ser reenfileirados.");
        }
    }
}
