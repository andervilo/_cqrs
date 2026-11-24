package com.cqrs.orderquery.application.query;

import com.cqrs.orderquery.application.exception.InvalidQueryException;
import java.util.*;
import org.springframework.data.domain.*;

public final class QueryPages {
    private QueryPages() {}
    public static Pageable of(int page,int size,String sort,Set<String> allowed,String key) {
        if(page<0 || size<1 || size>100) throw new InvalidQueryException("Invalid page or size");
        var parts=sort.split(",",-1);
        if(parts.length!=2 || !allowed.contains(parts[0]) || !(parts[1].equalsIgnoreCase("asc")||parts[1].equalsIgnoreCase("desc")))
            throw new InvalidQueryException("Unsupported sort; use an allowed field and asc/desc");
        return PageRequest.of(page,size,Sort.by(Sort.Direction.fromString(parts[1]),parts[0]).and(Sort.by(key)));
    }
}
