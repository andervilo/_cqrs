package com.cqrs.orderquery.application.inbox;

import com.cqrs.orderquery.domain.ProjectionEventType;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ProjectionHandlerRegistry {
    private final Map<ProjectionEventType,ProjectionHandler> handlers=new EnumMap<>(ProjectionEventType.class);
    public ProjectionHandlerRegistry(List<ProjectionHandler> handlers) {
        for(var handler:handlers) if(this.handlers.put(handler.type(),handler)!=null)
            throw new IllegalStateException("Duplicate projection handler");
    }
    public ProjectionHandler require(ProjectionEventType type) {
        var handler=handlers.get(type);
        if(handler==null) throw new InvalidProjectionEvent("Unsupported projection type");
        return handler;
    }
}
