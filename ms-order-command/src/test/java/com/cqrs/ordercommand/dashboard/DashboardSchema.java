package com.cqrs.ordercommand.dashboard;

import com.cqrs.ordercommand.application.dashboard.DashboardSnapshot;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Test/export helper: schema/fixtures derive from the actual complete producer record. */
public final class DashboardSchema {
    private final Map<String,Object> definitions=new LinkedHashMap<>();
    public static void export(DashboardSnapshot snapshot,ObjectMapper json,String name) {
        try {
            Path folder=Path.of("target","dashboard-contract");Files.createDirectories(folder);
            var generator=new DashboardSchema();
            var root=new LinkedHashMap<String,Object>();
            root.put("$schema","https://json-schema.org/draft/2020-12/schema");
            root.put("$id","https://cqrs.example/contracts/sales-dashboard-v1.schema.json");
            root.put("title","SALES_DASHBOARD v1 - aggregated commercial dashboard");
            root.putAll(generator.type(DashboardSnapshot.class));root.put("$defs",generator.definitions);
            Files.writeString(folder.resolve("sales-dashboard-v1.schema.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            Files.writeString(folder.resolve(name+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
        }catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> type(Type type) {
        if(type instanceof ParameterizedType p) {
            if(p.getRawType()==List.class)return Map.of("type","array","maxItems",128,"items",type(p.getActualTypeArguments()[0]));
            return Map.of("type","object","maxProperties",32,"additionalProperties",type(p.getActualTypeArguments()[1]));
        }
        var c=(Class<?>)type;
        if(c.isRecord()) {
            String name=c.getSimpleName();
            if(!definitions.containsKey(name)) {
                definitions.put(name,Map.of());
                var fields=new LinkedHashMap<String,Object>();var required=new ArrayList<String>();
                for(var field:c.getRecordComponents()) {
                    Map<String,Object> schema=type(field.getGenericType());
                    if(nullable(name,field.getName()))schema=Map.of("anyOf",List.of(schema,Map.of("type","null")));
                    fields.put(field.getName(),schema);required.add(field.getName());
                }
                definitions.put(name,Map.of("type","object","additionalProperties",false,"required",required,"properties",fields));
            }
            return Map.of("$ref","#/$defs/"+name);
        }
        if(c.isEnum())return Map.of("type","string","enum",Arrays.stream(c.getEnumConstants()).map(Object::toString).toList());
        if(c==UUID.class)return Map.of("type","string","format","uuid");
        if(c==Instant.class)return Map.of("type","string","format","date-time");
        if(c==LocalDate.class)return Map.of("type","string","format","date");
        if(c==String.class)return Map.of("type","string","maxLength",1024);
        if(c==boolean.class)return Map.of("type","boolean");
        if(c==BigDecimal.class)return Map.of("type","number","multipleOf",new BigDecimal("0.01"));
        return Map.of("type","integer","minimum",0,"maximum",Long.MAX_VALUE);
    }
    private boolean nullable(String record,String field) {
        return switch(record) {
            case "DashboardSnapshot"->Set.of("previousOrders","previousCustomers").contains(field);
            case "Metric"->Set.of("value","previousValue","changeAbsolute","changePercent","changePercentagePoints").contains(field);
            case "OrderStats"->Set.of("cancellationRate","cancellationValueRate","averageActiveOrderValue").contains(field);
            case "BuyerStats"->Set.of("returningBuyerShare","ordersPerActiveBuyer","top5CustomerValueShare").contains(field);
            case "ValueDrivers"->Set.of("totalChange","volumeEffect","averageValueEffect").contains(field);
            case "Product"->field.startsWith("previous")||Set.of("changeAbsolute","changePercent","retainedValueShare","cancellationValueRate").contains(field);
            case "Products"->Set.of("top5ProductValueShare","previousTop5ProductValueShare").contains(field);
            case "Portfolio"->field.equals("oldestCreatedAt");
            case "Evidence"->Set.of("previousValue","change").contains(field);
            case "UnavailableMetric"->field.equals("value");
            case "Coverage"->field.equals("earliestOrderAt");
            default->false;
        };
    }
}
