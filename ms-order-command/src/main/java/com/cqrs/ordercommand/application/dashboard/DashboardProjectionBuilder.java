package com.cqrs.ordercommand.application.dashboard;

import static com.cqrs.ordercommand.application.dashboard.DashboardSnapshot.*;
import com.cqrs.ordercommand.application.dashboard.DashboardSnapshot.Period;
import static com.cqrs.ordercommand.application.dashboard.DashboardMath.*;
import com.cqrs.ordercommand.infrastructure.persistence.DashboardReadRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DashboardProjectionBuilder {
    private final DashboardReadRepository repository;
    private final DashboardProperties properties;
    private final Clock clock;
    private final DashboardInsights insights;
    public DashboardProjectionBuilder(DashboardReadRepository repository, DashboardProperties properties,
            Clock clock, DashboardInsights insights) {
        this.repository=repository;this.properties=properties;this.clock=clock;this.insights=insights;
    }

    public DashboardSnapshot build(DashboardPeriod code, Instant reference) {
        var window=code.window(reference);
        boolean comparable=window.comparison().status().equals("AVAILABLE");
        var current=repository.orders(window.period());
        var previousPeriod=new Period(window.comparison().from(),window.comparison().to(),false);
        var previous=comparable?repository.orders(previousPeriod):null;
        var buyers=repository.buyers(window.period(),current);
        var oldBuyers=comparable?repository.buyers(previousPeriod,previous):null;
        var ranked=repository.products(window.period(),window.comparison(),current,properties.rules());
        var byValue=ranked.stream().filter(p->p.valueRank()<=10 && p.product().retainedOrderValue().signum()>0)
                .sorted(Comparator.comparingLong(DashboardReadRepository.RankedProduct::valueRank))
                .map(DashboardReadRepository.RankedProduct::product).toList();
        var byUnits=ranked.stream().filter(p->p.unitsRank()<=10 && p.product().retainedUnits()>0)
                .sorted(Comparator.comparingLong(DashboardReadRepository.RankedProduct::unitsRank))
                .map(DashboardReadRepository.RankedProduct::product).toList();
        var declining=ranked.stream().filter(p->p.declining() && p.dropRank()<=10)
                .sorted(Comparator.comparingLong(DashboardReadRepository.RankedProduct::dropRank))
                .map(DashboardReadRepository.RankedProduct::product).toList();
        BigDecimal top=ranked.isEmpty()?ZERO:ranked.getFirst().top5Value();
        BigDecimal oldTop=ranked.isEmpty()?ZERO:ranked.getFirst().previousTop5Value();
        var products=new Products(percent(top,current.retainedOrderValue()),
                comparable?percent(oldTop,previous.retainedOrderValue()):null,
                current.retainedOrderValue().subtract(byValue.stream().map(Product::retainedOrderValue).reduce(ZERO,BigDecimal::add)),
                byValue,byUnits,declining);
        var summary=summary(current,previous,buyers,oldBuyers,products,comparable);
        var portfolio=portfolio(reference);
        var signals=insights.build(window,current,previous,buyers,products,portfolio,properties.rules());
        return new DashboardSnapshot(code.id(),code,DashboardPeriod.ZONE.getId(),"BRL",reference,clock.instant(),1,1,
                window.period(),window.comparison(),summary,current,previous,buyers,oldBuyers,
                drivers(current,previous),products,repository.days(window.period()),
                comparable?repository.days(previousPeriod):List.of(),portfolio,
                signals.stream().limit(5).toList(),Math.max(0,signals.size()-5),
                List.of(new UnavailableMetric("receivedRevenue",null,"UNAVAILABLE","PAYMENT_NOT_MODELED"),
                        new UnavailableMetric("grossMargin",null,"UNAVAILABLE","COST_NOT_MODELED"),
                        new UnavailableMetric("profit",null,"UNAVAILABLE","COST_AND_PAYMENT_NOT_MODELED")),
                properties.rules(),new Coverage("order_command.orders","CURRENT_DATABASE",repository.earliestOrder(),"NOT_AUDITED"));
    }

    private Portfolio portfolio(Instant reference) {
        var rows=repository.portfolio(reference);
        var age=new HashMap<Integer,DashboardReadRepository.PortfolioRow>();rows.forEach(r->age.put(r.bucket(),r));
        String[] codes={"LT_24H","FROM_24H_TO_48H","FROM_48H_TO_72H","GTE_72H"};
        var buckets=new ArrayList<AgeBucket>();long all=0,older=0;BigDecimal value=ZERO,olderValue=ZERO;
        for(int i=0;i<4;i++) {
            var row=age.getOrDefault(i,new DashboardReadRepository.PortfolioRow(i,0,ZERO,null));
            var params=new LinkedHashMap<String,String>();params.put("status","CREATED");
            // API uses [from,to), while age bands use inclusive old cutoffs. Offset by one microsecond.
            if(i<3) params.put("from",reference.minus(Duration.ofHours((i+1L)*24)).plusNanos(1000).toString());
            params.put("to",i==0?reference.toString():reference.minus(Duration.ofHours(i*24L)).plusNanos(1000).toString());
            buckets.add(new AgeBucket(codes[i],row.count(),row.amount(),DashboardInsights.ordersLink(params)));
            all=Math.addExact(all,row.count());value=value.add(row.amount());
            if(i>=2){older=Math.addExact(older,row.count());olderValue=olderValue.add(row.amount());}
        }
        var oldLink=DashboardInsights.ordersLink(Map.of("status","CREATED","to",reference.minus(Duration.ofHours(48)).plusNanos(1000).toString()));
        return new Portfolio("GLOBAL_AS_OF_REFERENCE",List.copyOf(buckets),all,value,
                new AgeBucket("GTE_48H",older,olderValue,oldLink),
                rows.stream().map(DashboardReadRepository.PortfolioRow::oldest).filter(Objects::nonNull).min(Instant::compareTo).orElse(null));
    }
}
