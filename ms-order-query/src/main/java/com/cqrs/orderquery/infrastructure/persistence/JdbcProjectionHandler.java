package com.cqrs.orderquery.infrastructure.persistence;

import com.cqrs.orderquery.application.inbox.*;
import com.cqrs.orderquery.domain.ProjectionEventType;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Identifiers and columns are application constants, never Kafka/HTTP input. */
public class JdbcProjectionHandler<T extends ProjectionSnapshot> implements ProjectionHandler {
    private final ProjectionEventType type;
    private final String table,key;
    private final Class<T> payloadType;
    private final Function<T,LinkedHashMap<String,Object>> values;
    private final Set<String> jsonFields;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcProjectionHandler(ProjectionEventType type,String table,String key,Class<T> payloadType,
            Function<T,LinkedHashMap<String,Object>> values,Set<String> jsonFields,JdbcTemplate jdbc,ObjectMapper json) {
        this.type=type;this.table=table;this.key=key;this.payloadType=payloadType;this.values=values;
        this.jsonFields=jsonFields;this.jdbc=jdbc;this.json=json;
    }
    public ProjectionEventType type() { return type; }
    public Class<T> payloadType() { return payloadType; }
    public void upsert(ProjectionSnapshot snapshot,long version) {
        var fields=values.apply(payloadType.cast(snapshot));
        var columns=new ArrayList<>(fields.keySet());
        var args=new ArrayList<Object>();
        args.add(UuidBytes.from(snapshot.identity()));
        fields.forEach((name,value) -> args.add(jsonFields.contains(name)?json.writeValueAsString(value):sqlValue(value)));
        args.add(version);
        String placeholders=columns.stream().map(name -> jsonFields.contains(name)?"CAST(? AS JSON)":"?").collect(Collectors.joining(","));
        var assignments=new ArrayList<String>();
        for(var column:columns) assignments.add(column+"=incoming."+column);
        assignments.add("projection_version=incoming.projection_version");
        assignments.add("projected_at=incoming.projected_at");
        String sql="INSERT INTO "+table+" ("+key+","+String.join(",",columns)+",projection_version,projected_at) VALUES (?,"+
                placeholders+",?,UTC_TIMESTAMP(6)) AS incoming ON DUPLICATE KEY UPDATE "+String.join(",",assignments);
        jdbc.update(sql,args.toArray());
    }
    public void delete(UUID id) { jdbc.update("DELETE FROM "+table+" WHERE "+key+"=?",UuidBytes.from(id)); }
    private Object sqlValue(Object value) {
        if(value instanceof UUID id) return UuidBytes.from(id);
        if(value instanceof Instant instant) return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);
        return value;
    }
}
