package com.cqrs.ordercommand.application.dashboard;

import java.math.*;
import java.util.*;
import static com.cqrs.ordercommand.application.dashboard.DashboardSnapshot.*;

public final class DashboardMath {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    private DashboardMath() {}
    public static BigDecimal number(long n) { return BigDecimal.valueOf(n); }
    public static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() == 0 ? null : numerator.divide(denominator,2,RoundingMode.HALF_UP);
    }
    public static BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        return ratio(numerator.multiply(BigDecimal.valueOf(100)),denominator);
    }
    public static BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        return current == null || previous == null ? null : percent(current.subtract(previous),previous);
    }
    public static Metric metric(BigDecimal current, BigDecimal previous, String unit, String direction,
                                boolean rate, boolean comparable) {
        String status = current == null ? "NO_DENOMINATOR" : "AVAILABLE";
        String oldStatus = !comparable ? "CALENDAR_ALIGNMENT_LIMIT" : previous == null ? "NO_DENOMINATOR" : "AVAILABLE";
        BigDecimal delta = current == null || previous == null || !comparable ? null : current.subtract(previous);
        String comparison = !comparable ? "CALENDAR_ALIGNMENT_LIMIT" : delta == null ? "NO_DENOMINATOR"
                : !rate && previous.signum() == 0 ? "NO_BASELINE" : "AVAILABLE";
        return new Metric(current,comparable?previous:null,rate?null:delta,
                !rate && comparable ? changePercent(current,previous) : null,rate?delta:null,
                status,oldStatus,comparison,unit,direction);
    }
    public static ValueDrivers drivers(OrderStats current, OrderStats previous) {
        if (previous == null || current.activeOrderCount() == 0 || previous.activeOrderCount() == 0)
            return new ValueDrivers("NO_DENOMINATOR",null,null,null);
        BigDecimal total = current.retainedOrderValue().subtract(previous.retainedOrderValue());
        BigDecimal volume = number(current.activeOrderCount()-previous.activeOrderCount())
                .multiply(previous.retainedOrderValue()).divide(number(previous.activeOrderCount()),2,RoundingMode.HALF_UP);
        // Assign the rounding residual to the second effect so the displayed cents reconcile exactly.
        return new ValueDrivers("AVAILABLE",total,volume,total.subtract(volume));
    }
    public static Map<String,Metric> summary(OrderStats now, OrderStats old, BuyerStats buyer, BuyerStats oldBuyer,
                                             Products products, boolean comparable) {
        var result=new LinkedHashMap<String,Metric>();
        var a=values(now,buyer,products.top5ProductValueShare());
        var b=old==null?Map.<String,BigDecimal>of():values(old,oldBuyer,products.previousTop5ProductValueShare());
        a.forEach((name,value)->{
            boolean rate=name.endsWith("Rate")||name.endsWith("Share");
            String unit=rate?"PERCENT":name.endsWith("Value")?"BRL":name.equals("ordersPerActiveBuyer")?"RATIO":"COUNT";
            String direction=name.startsWith("cancel")||name.startsWith("top5")?"DOWN":
                    name.equals("averageActiveOrderValue")||name.equals("ordersPerActiveBuyer")||name.equals("returningBuyerShare")?"CONTEXT_DEPENDENT":"UP";
            result.put(name,metric(value,b.get(name),unit,direction,rate,comparable));
        });
        return Collections.unmodifiableMap(result);
    }
    private static Map<String,BigDecimal> values(OrderStats o,BuyerStats b,BigDecimal productShare) {
        var map=new LinkedHashMap<String,BigDecimal>();
        map.put("orderCount",number(o.orderCount()));map.put("requestedValue",o.requestedValue());
        map.put("activeOrderCount",number(o.activeOrderCount()));map.put("cancelledOrderCount",number(o.cancelledOrderCount()));
        map.put("retainedOrderValue",o.retainedOrderValue());map.put("cancelledOrderValue",o.cancelledOrderValue());
        map.put("cancellationRate",o.cancellationRate());map.put("cancellationValueRate",o.cancellationValueRate());
        map.put("averageActiveOrderValue",o.averageActiveOrderValue());
        map.put("requestedUnits",number(o.requestedUnits()));map.put("retainedUnits",number(o.retainedUnits()));map.put("cancelledUnits",number(o.cancelledUnits()));
        map.put("activeBuyerCount",number(b.activeBuyerCount()));map.put("newActiveBuyerCount",number(b.newActiveBuyerCount()));
        map.put("returningActiveBuyerCount",number(b.returningActiveBuyerCount()));map.put("returningBuyerShare",b.returningBuyerShare());
        map.put("ordersPerActiveBuyer",b.ordersPerActiveBuyer());map.put("top5CustomerValueShare",b.top5CustomerValueShare());
        map.put("top5ProductValueShare",productShare);
        return map;
    }
}
