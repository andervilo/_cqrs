package com.cqrs.orderquery.application.dashboard;

import static com.cqrs.orderquery.application.dashboard.DashboardSnapshot.*;
import com.cqrs.orderquery.application.dashboard.DashboardSnapshot.Period;
import static com.cqrs.orderquery.application.dashboard.DashboardMath.*;
import com.cqrs.orderquery.application.inbox.InvalidProjectionEvent;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import java.lang.reflect.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Validates the complete wire model, including cross-section invariants; never queries command. */
@Component
public class DashboardContractValidator {
    private static final ObjectMapper JSON=JsonMapper.builder().build();
    private static final Pattern PRODUCT_PATH=Pattern.compile("/api/queries/products/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** Do not accept scalar coercion (e.g. numeric strings or null primitive counts) on the wire. */
    public void validateStructure(JsonNode node) { shape(node,DashboardSnapshot.class,false); }
    private void shape(JsonNode node,Type expected,boolean allowNull) {
        require(node!=null,"missing field");
        if(node.isNull()){require(allowNull,"null field");return;}
        if(expected instanceof ParameterizedType p) {
            if(p.getRawType()==List.class){require(node.isArray()&&node.size()<=128,"array shape");node.forEach(n->shape(n,p.getActualTypeArguments()[0],false));}
            else {require(node.isObject()&&node.size()<=32,"map shape");node.properties().forEach(e->shape(e.getValue(),p.getActualTypeArguments()[1],false));}
            return;
        }
        Class<?> type=(Class<?>)expected;
        if(type.isRecord()) {
            require(node.isObject()&&node.size()==type.getRecordComponents().length,"record shape");
            for(var field:type.getRecordComponents())shape(node.get(field.getName()),field.getGenericType(),nullable(type.getSimpleName(),field.getName()));
        } else if(type==BigDecimal.class)require(node.isNumber(),"numeric shape");
        else if(type==long.class||type==Long.class||type==int.class)require(node.isIntegralNumber(),"integer shape");
        else if(type==boolean.class)require(node.isBoolean(),"boolean shape");
        else require(node.isString(),"text shape");
    }

    public void validate(DashboardSnapshot s) {
        try { validateSnapshot(s); }
        catch(InvalidProjectionEvent e){throw e;}
        catch(RuntimeException e){throw new InvalidProjectionEvent("Invalid dashboard contract");}
    }
    private void validateSnapshot(DashboardSnapshot s) {
        require(s!=null && s.dashboardId()!=null && s.periodCode()!=null,"identity");
        inspect(s);
        require(s.dashboardId().equals(s.periodCode().id()) && s.timeZone().equals(DashboardPeriod.ZONE.getId())
                && s.currency().equals("BRL") && s.rulesVersion()==1 && s.definitionsVersion()==1,"metadata");
        require(!s.computedAt().isBefore(s.sourceObservedAt()) && s.sourceObservedAt().getNano()%1000==0,"timestamps");
        var window=s.periodCode().window(s.sourceObservedAt());
        require(s.period().equals(window.period()) && s.comparison().equals(window.comparison()),"window");
        boolean comparable=s.comparison().status().equals("AVAILABLE");
        order(s.orders());buyer(s.customers(),s.orders());
        if(comparable){order(s.previousOrders());buyer(s.previousCustomers(),s.previousOrders());}
        else require(s.previousOrders()==null && s.previousCustomers()==null && s.comparisonDaily().isEmpty(),"unavailable comparison");
        require(same(s.summary(),summary(s.orders(),s.previousOrders(),s.customers(),s.previousCustomers(),s.products(),comparable)),"summary");
        require(same(s.valueChangeDrivers(),drivers(s.orders(),s.previousOrders())),"drivers");
        days(s.daily(),s.period(),s.orders());
        if(comparable) days(s.comparisonDaily(),new Period(s.comparison().from(),s.comparison().to(),false),s.previousOrders());
        products(s.products(),s.orders(),s.previousOrders(),s.appliedRules(),comparable);
        portfolio(s.openPortfolio(),s.sourceObservedAt());
        require(s.insights().size()<=5 && s.additionalInsightCount()>=0,"insight count");
        for(var i:s.insights()) {
            require(Set.of("CANCELLATION_INCREASE","ORDER_DEMAND_DROP","RETAINED_VALUE_GROWTH","PRODUCT_VALUE_DROP","CUSTOMER_CONCENTRATION","AGING_OPEN_PORTFOLIO").contains(i.code()),"insight code");
            require(Set.of("HIGH","MEDIUM","INFO").contains(i.priority()),"priority");
            nonNegative(i.exposure().value()); require(i.exposure().orderCount()>=0,"exposure");link(i.drillDown());
            require(!i.limitations().isEmpty(),"limitations");
            if(!comparable)require(Set.of("CUSTOMER_CONCENTRATION","AGING_OPEN_PORTFOLIO").contains(i.code()),"comparison signal");
            validateEvidence(i,s);
        }
        var expectedSignals=new DashboardInsights().build(window,s.orders(),s.previousOrders(),s.customers(),
                s.products(),s.openPortfolio(),s.appliedRules());
        require(s.insights().size()==Math.min(5,expectedSignals.size())
                && s.additionalInsightCount()==Math.max(0,expectedSignals.size()-5),"signal coverage");
        for(int i=0;i<s.insights().size();i++) {
            var a=s.insights().get(i);var b=expectedSignals.get(i);
            require(a.code().equals(b.code()) && a.priority().equals(b.priority()) && same(a.evidence(),b.evidence())
                    && same(a.exposure(),b.exposure()) && same(a.drillDown(),b.drillDown()),"signal rules/order");
        }
        require(s.unavailableMetrics().size()==3,"unavailable metrics");
        var reasons=Map.of("receivedRevenue","PAYMENT_NOT_MODELED","grossMargin","COST_NOT_MODELED","profit","COST_AND_PAYMENT_NOT_MODELED");
        require(s.unavailableMetrics().stream().map(UnavailableMetric::code).distinct().count()==3,"unavailable duplicates");
        for(var u:s.unavailableMetrics())require(u.value()==null && u.status().equals("UNAVAILABLE") && Objects.equals(reasons.get(u.code()),u.reason()),"unavailable metric");
        require(s.dataCoverage().source().equals("order_command.orders") && s.dataCoverage().generation().equals("CURRENT_DATABASE")
                && s.dataCoverage().completeness().equals("NOT_AUDITED"),"coverage");
        rules(s.appliedRules());
    }

    private void order(OrderStats o) {
        require(o!=null && o.orderCount()>=0 && o.activeOrderCount()>=0 && o.cancelledOrderCount()>=0
                && o.requestedUnits()>=0 && o.retainedUnits()>=0 && o.cancelledUnits()>=0,"counts");
        require(Math.addExact(o.activeOrderCount(),o.cancelledOrderCount())==o.orderCount()
                && Math.addExact(o.retainedUnits(),o.cancelledUnits())==o.requestedUnits(),"order sums");
        nonNegative(o.requestedValue());nonNegative(o.retainedOrderValue());nonNegative(o.cancelledOrderValue());
        equal(o.requestedValue(),o.retainedOrderValue().add(o.cancelledOrderValue()),"order values");
        equal(o.cancellationRate(),percent(number(o.cancelledOrderCount()),number(o.orderCount())),"cancellation rate");
        equal(o.cancellationValueRate(),percent(o.cancelledOrderValue(),o.requestedValue()),"cancelled value rate");
        equal(o.averageActiveOrderValue(),ratio(o.retainedOrderValue(),number(o.activeOrderCount())),"average");
        require(o.retainedUnits()>=o.activeOrderCount() && o.cancelledUnits()>=o.cancelledOrderCount(),"units");
        require((o.activeOrderCount()==0)==(o.retainedOrderValue().signum()==0)
                && (o.cancelledOrderCount()==0)==(o.cancelledOrderValue().signum()==0),"empty sums");
    }
    private void buyer(BuyerStats b,OrderStats o) {
        require(b!=null && b.activeBuyerCount()>=0 && b.newActiveBuyerCount()>=0 && b.returningActiveBuyerCount()>=0
                && b.activeBuyerCount()<=o.activeOrderCount(),"buyers");
        require(Math.addExact(b.newActiveBuyerCount(),b.returningActiveBuyerCount())==b.activeBuyerCount(),"buyer sums");
        require((b.activeBuyerCount()==0)==(o.activeOrderCount()==0),"empty buyers");
        equal(b.returningBuyerShare(),percent(number(b.returningActiveBuyerCount()),number(b.activeBuyerCount())),"returning share");
        equal(b.ordersPerActiveBuyer(),ratio(number(o.activeOrderCount()),number(b.activeBuyerCount())),"frequency");
        share(b.top5CustomerValueShare(),o.retainedOrderValue());
        nonNegative(b.top5CustomerRetainedValue());
        require(b.top5CustomerRetainedValue().compareTo(o.retainedOrderValue())<=0,"top customer value");
        equal(b.top5CustomerValueShare(),percent(b.top5CustomerRetainedValue(),o.retainedOrderValue()),"top customer share");
        if(b.activeBuyerCount()>0 && b.activeBuyerCount()<=5)equal(b.top5CustomerValueShare(),number(100),"customer concentration");
    }
    private void days(List<Day> days,Period period,OrderStats totals) {
        require(days.size()<=32,"day count");
        Instant cursor=period.from();long all=0,active=0,cancelled=0,units=0,retained=0,cancelledUnits=0;
        BigDecimal requested=ZERO,value=ZERO,cancelledValue=ZERO;
        for(var d:days) {
            require(d.from().equals(cursor) && d.to().isAfter(d.from()) && !d.to().isAfter(period.to())
                    && d.date().equals(d.from().atZone(DashboardPeriod.ZONE).toLocalDate()),"day bounds");
            Instant naturalEnd=d.date().plusDays(1).atStartOfDay(DashboardPeriod.ZONE).toInstant();
            require(d.to().equals(naturalEnd.isBefore(period.to())?naturalEnd:period.to()),"day end");
            require(d.orderCount()>=0 && d.activeOrderCount()>=0 && d.cancelledOrderCount()>=0
                    && d.activeBuyerCount()>=0 && d.activeBuyerCount()<=d.activeOrderCount()
                    && d.requestedUnits()>=0 && d.retainedUnits()>=0 && d.cancelledUnits()>=0,"daily numbers");
            require(Math.addExact(d.activeOrderCount(),d.cancelledOrderCount())==d.orderCount()
                    && Math.addExact(d.retainedUnits(),d.cancelledUnits())==d.requestedUnits(),"daily sums");
            nonNegative(d.requestedValue());nonNegative(d.retainedOrderValue());nonNegative(d.cancelledOrderValue());
            equal(d.requestedValue(),d.retainedOrderValue().add(d.cancelledOrderValue()),"daily values");
            all=Math.addExact(all,d.orderCount());active=Math.addExact(active,d.activeOrderCount());cancelled=Math.addExact(cancelled,d.cancelledOrderCount());
            units=Math.addExact(units,d.requestedUnits());retained=Math.addExact(retained,d.retainedUnits());cancelledUnits=Math.addExact(cancelledUnits,d.cancelledUnits());
            requested=requested.add(d.requestedValue());value=value.add(d.retainedOrderValue());cancelledValue=cancelledValue.add(d.cancelledOrderValue());cursor=d.to();
        }
        require(cursor.equals(period.to()) && all==totals.orderCount() && active==totals.activeOrderCount()
                && cancelled==totals.cancelledOrderCount() && units==totals.requestedUnits()
                && retained==totals.retainedUnits() && cancelledUnits==totals.cancelledUnits(),"series totals");
        equal(requested,totals.requestedValue(),"series requested");equal(value,totals.retainedOrderValue(),"series active");equal(cancelledValue,totals.cancelledOrderValue(),"series cancelled");
    }
    private void products(Products products,OrderStats current,OrderStats previous,Rules rules,boolean comparable) {
        var seen=new HashMap<UUID,Product>();
        var valueOrder=Comparator.comparing(Product::retainedOrderValue).reversed()
                .thenComparing(Comparator.comparingLong(Product::retainedUnits).reversed()).thenComparing(p->p.productId().toString());
        var unitOrder=Comparator.comparingLong(Product::retainedUnits).reversed()
                .thenComparing(Product::retainedOrderValue,Comparator.reverseOrder()).thenComparing(p->p.productId().toString());
        var dropOrder=Comparator.comparing((Product p)->p.previousRetainedOrderValue().subtract(p.retainedOrderValue())).reversed()
                .thenComparing(p->p.productId().toString());
        for(var list:List.of(products.byRetainedValue(),products.byRetainedUnits(),products.declining())) {
            require(list.size()<=10 && list.stream().map(Product::productId).distinct().count()==list.size(),"ranking limit/duplicates");
            for(var p:list) {
                require(p.productId()!=null && !p.productName().isBlank() && p.productName().length()<=160
                        && p.retainedUnits()>=p.activeOrderCount() && p.activeOrderCount()>=0 && p.activeOrderCount()<=current.activeOrderCount(),"product");
                nonNegative(p.retainedOrderValue());nonNegative(p.cancelledOrderValue());
                require((p.activeOrderCount()==0)==(p.retainedOrderValue().signum()==0),"product counts");
                equal(p.retainedValueShare(),percent(p.retainedOrderValue(),current.retainedOrderValue()),"product share");
                equal(p.cancellationValueRate(),percent(p.cancelledOrderValue(),p.cancelledOrderValue().add(p.retainedOrderValue())),"product cancellation");
                if(comparable) {
                    require(p.previousActiveOrderCount()!=null && p.previousRetainedUnits()!=null
                            && p.previousActiveOrderCount()>=0 && p.previousRetainedUnits()>=p.previousActiveOrderCount(),"previous product counts");
                    nonNegative(p.previousRetainedOrderValue());nonNegative(p.previousCancelledOrderValue());
                    equal(p.changeAbsolute(),p.retainedOrderValue().subtract(p.previousRetainedOrderValue()),"product delta");
                    equal(p.changePercent(),changePercent(p.retainedOrderValue(),p.previousRetainedOrderValue()),"product change");
                    require(p.comparisonStatus().equals(p.previousRetainedOrderValue().signum()==0?"NO_BASELINE":"AVAILABLE"),"product comparison status");
                } else require(p.previousRetainedUnits()==null && p.previousActiveOrderCount()==null
                        && p.previousRetainedOrderValue()==null && p.previousCancelledOrderValue()==null
                        && p.changeAbsolute()==null && p.changePercent()==null && p.comparisonStatus().equals("CALENDAR_ALIGNMENT_LIMIT"),"product comparison");
                var other=seen.putIfAbsent(p.productId(),p);require(other==null||same(other,p),"inconsistent product across rankings");
            }
        }
        require(products.byRetainedValue().equals(products.byRetainedValue().stream().sorted(valueOrder).toList())
                && products.byRetainedUnits().equals(products.byRetainedUnits().stream().sorted(unitOrder).toList()),"ranking order");
        require(products.byRetainedValue().stream().allMatch(p->p.retainedOrderValue().signum()>0)
                && products.byRetainedUnits().stream().allMatch(p->p.retainedUnits()>0),"empty product rank");
        require(current.retainedOrderValue().signum()==0 || !products.byRetainedValue().isEmpty(),"missing products");
        BigDecimal top=products.byRetainedValue().stream().limit(5).map(Product::retainedOrderValue).reduce(ZERO,BigDecimal::add);
        equal(products.top5ProductValueShare(),percent(top,current.retainedOrderValue()),"product concentration");
        equal(products.otherProductsRetainedValue(),current.retainedOrderValue().subtract(products.byRetainedValue().stream().map(Product::retainedOrderValue).reduce(ZERO,BigDecimal::add)),"product tail");
        nonNegative(products.otherProductsRetainedValue());
        if(comparable)share(products.previousTop5ProductValueShare(),previous.retainedOrderValue());else require(products.previousTop5ProductValueShare()==null,"old product share");
        require(comparable||products.declining().isEmpty(),"declining comparison");
        for(var p:products.declining())require(p.previousActiveOrderCount()>=rules.minimumProductSample()
                && p.previousRetainedOrderValue().signum()>0 && p.previousRetainedOrderValue().subtract(p.retainedOrderValue()).multiply(number(100))
                .compareTo(p.previousRetainedOrderValue().multiply(rules.productDropPercent()))>=0,"declining eligibility");
        require(products.declining().equals(products.declining().stream().sorted(dropOrder).toList()),"drop order");
    }

    private void portfolio(Portfolio p,Instant reference) {
        require(p.scope().equals("GLOBAL_AS_OF_REFERENCE") && p.ageBuckets().size()==4,"portfolio");
        var names=List.of("LT_24H","FROM_24H_TO_48H","FROM_48H_TO_72H","GTE_72H");
        long count=0,old=0;BigDecimal value=ZERO,oldValue=ZERO;
        for(int i=0;i<4;i++) {
            var b=p.ageBuckets().get(i);require(b.code().equals(names.get(i)) && b.orderCount()>=0,"age bucket");
            nonNegative(b.value());link(b.drillDown());
            var query=new LinkedHashMap<String,String>();query.put("status","CREATED");
            if(i<3)query.put("from",reference.minus(Duration.ofHours((i+1L)*24)).plusNanos(1000).toString());
            query.put("to",i==0?reference.toString():reference.minus(Duration.ofHours(i*24L)).plusNanos(1000).toString());
            require(b.drillDown().query().equals(query),"age drilldown");
            count=Math.addExact(count,b.orderCount());value=value.add(b.value());
            if(i>=2){old=Math.addExact(old,b.orderCount());oldValue=oldValue.add(b.value());}
        }
        require(count==p.orderCount() && old==p.olderThanOrEqual48h().orderCount(),"portfolio counts");
        equal(value,p.value(),"portfolio value");equal(oldValue,p.olderThanOrEqual48h().value(),"old portfolio value");
        require((count==0)==(p.oldestCreatedAt()==null),"oldest order");
        if(p.oldestCreatedAt()!=null)require(p.oldestCreatedAt().isBefore(reference),"oldest date");
        require(p.olderThanOrEqual48h().code().equals("GTE_48H"),"old cutoff");link(p.olderThanOrEqual48h().drillDown());
        require(p.olderThanOrEqual48h().drillDown().query().equals(Map.of("status","CREATED","to",reference.minus(Duration.ofHours(48)).plusNanos(1000).toString())),"old drilldown");
    }
    private void validateEvidence(Insight i,DashboardSnapshot s) {
        var e=i.evidence();
        require(e.currentSampleSize()>=0 && e.previousSampleSize()>=0 && !i.title().isBlank() && !i.suggestedAction().isBlank(),"evidence");
        if(i.code().equals("CANCELLATION_INCREASE")) {
            equal(e.currentValue(),s.orders().cancellationRate(),"cancellation evidence");
            equal(e.previousValue(),s.previousOrders().cancellationRate(),"old cancellation evidence");
            require(e.currentSampleSize()==s.orders().orderCount() && e.previousSampleSize()==s.previousOrders().orderCount(),"signal sample");
            require(s.orders().orderCount()>=s.appliedRules().minimumOrderSample() && s.previousOrders().orderCount()>=s.appliedRules().minimumOrderSample(),"signal minimum");
        }
        if(i.code().equals("AGING_OPEN_PORTFOLIO"))require(s.openPortfolio().olderThanOrEqual48h().orderCount()>0,"age signal");
    }
    private void link(DrillDown link) {
        require(link!=null && link.method().equals("GET") && link.requiredScope().equals("cqrs.read"),"drilldown");
        if(link.path().equals("/api/queries/orders")) {
            require(Set.of("status","from","to","customerId").containsAll(link.query().keySet()),"drilldown parameters");
            for(var entry:link.query().entrySet())switch(entry.getKey()) {
                case "status" -> require(Set.of("CREATED","CANCELLED").contains(entry.getValue()),"link status");
                case "from","to" -> Instant.parse(entry.getValue());
                case "customerId" -> UUID.fromString(entry.getValue());
                default -> throw new InvalidProjectionEvent("Invalid link field");
            }
        } else require(PRODUCT_PATH.matcher(link.path()).matches() && link.query().isEmpty(),"drilldown path");
    }
    private void rules(Rules r) {
        require(r.minimumOrderSample()>0 && r.minimumProductSample()>0 && r.minimumBuyerSample()>0 && r.agingHours()==48,"rules");
        for(var n:List.of(r.cancellationRateThreshold(),r.cancellationIncreasePoints(),r.demandDropPercent(),r.retainedValueGrowthPercent(),r.productDropPercent(),r.customerConcentrationPercent()))
            require(n.signum()>=0 && n.compareTo(number(100))<=0,"rule percentage");
    }

    /** Bound every nested string/list/number. Missing creator properties are separately rejected by Jackson. */
    private void inspect(Object value) {
        if(value==null)return;
        if(value instanceof BigDecimal n) {require(n.precision()<=38 && n.scale()<=2,"decimal precision");return;}
        if(value instanceof String text){require(text.length()<=1024,"text size");return;}
        if(value instanceof Map<?,?> map){require(map.size()<=32,"map size");map.forEach((k,v)->{require(k!=null,"map key");inspect(k);inspect(v);});return;}
        if(value instanceof List<?> list){require(list.size()<=128 && list.stream().noneMatch(Objects::isNull),"list size/null");list.forEach(this::inspect);return;}
        if(value.getClass().isRecord())for(var c:value.getClass().getRecordComponents()){
            try {
                Object field=c.getAccessor().invoke(value);
                if(field==null)require(nullable(value.getClass().getSimpleName(),c.getName()),"missing "+c.getName());
                inspect(field);
            }catch(ReflectiveOperationException e){throw new InvalidProjectionEvent("Invalid dashboard record");}
        }
    }
    private boolean nullable(String record,String field) {
        return switch(record) {
            case "DashboardSnapshot" -> Set.of("previousOrders","previousCustomers").contains(field);
            case "Metric" -> Set.of("value","previousValue","changeAbsolute","changePercent","changePercentagePoints").contains(field);
            case "OrderStats" -> Set.of("cancellationRate","cancellationValueRate","averageActiveOrderValue").contains(field);
            case "BuyerStats" -> Set.of("returningBuyerShare","ordersPerActiveBuyer","top5CustomerValueShare").contains(field);
            case "ValueDrivers" -> Set.of("totalChange","volumeEffect","averageValueEffect").contains(field);
            case "Product" -> field.startsWith("previous")||Set.of("changeAbsolute","changePercent","retainedValueShare","cancellationValueRate").contains(field);
            case "Products" -> Set.of("top5ProductValueShare","previousTop5ProductValueShare").contains(field);
            case "Portfolio" -> field.equals("oldestCreatedAt");
            case "Evidence" -> Set.of("previousValue","change").contains(field);
            case "UnavailableMetric" -> field.equals("value");
            case "Coverage" -> field.equals("earliestOrderAt");
            default -> false;
        };
    }
    private boolean same(Object a,Object b){return canonical(JSON.valueToTree(a)).equals(canonical(JSON.valueToTree(b)));}
    private Object canonical(JsonNode n){
        if(n.isObject()){var m=new TreeMap<String,Object>();n.properties().forEach(e->m.put(e.getKey(),canonical(e.getValue())));return m;}
        if(n.isArray()){var a=new ArrayList<Object>();n.forEach(v->a.add(canonical(v)));return a;}
        if(n.isNumber())return n.decimalValue().stripTrailingZeros();
        if(n.isNull())return "<null>";
        return n.asText();
    }
    private void equal(BigDecimal a,BigDecimal b,String field){require(a==null?b==null:b!=null&&a.compareTo(b)==0,field);}
    private void nonNegative(BigDecimal n){require(n!=null && n.signum()>=0,"negative value");}
    private void share(BigDecimal n,BigDecimal total){require(total.signum()==0?n==null:n!=null&&n.signum()>=0&&n.compareTo(number(100))<=0,"share range");}
    private void require(boolean valid,String field){if(!valid)throw new InvalidProjectionEvent("Invalid dashboard "+field);}
}
