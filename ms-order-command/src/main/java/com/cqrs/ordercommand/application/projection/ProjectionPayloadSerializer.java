package com.cqrs.ordercommand.application.projection;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ProjectionPayloadSerializer {
    private final ObjectMapper json;
    public ProjectionPayloadSerializer(ObjectMapper json) { this.json=json; }
    public String serialize(Object snapshot) { return json.writeValueAsString(snapshot); }
}
