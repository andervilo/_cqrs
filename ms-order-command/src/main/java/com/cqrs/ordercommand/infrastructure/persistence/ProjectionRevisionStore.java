package com.cqrs.ordercommand.infrastructure.persistence;

import com.cqrs.ordercommand.application.projection.ProjectionTarget;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ProjectionRevisionStore {
    private final JdbcTemplate jdbc;
    public ProjectionRevisionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Map<ProjectionTarget,Long> advance(Collection<ProjectionTarget> targets) {
        var revisions = new LinkedHashMap<ProjectionTarget,Long>();
        for (var target : new TreeSet<>(targets)) {
            long revision = jdbc.queryForObject("""
                    INSERT INTO order_command.projection_revision(event_type,projection_key,version)
                    VALUES (?,?,1)
                    ON CONFLICT (event_type,projection_key) DO UPDATE
                    SET version=order_command.projection_revision.version+1
                    RETURNING version
                    """, Long.class, target.type().name(), target.key());
            revisions.put(target, revision);
        }
        return revisions;
    }
}
