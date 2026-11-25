package com.cqrs.orderquery.application.dashboard;

import static com.cqrs.orderquery.application.dashboard.DashboardSnapshot.*;
import static com.cqrs.orderquery.application.dashboard.DashboardMath.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class DashboardInsights {
    public static DrillDown ordersLink(Map<String,String> query) {
        return new DrillDown("GET","/api/queries/orders",Map.copyOf(query),"cqrs.read");
    }
    public List<Insight> build(DashboardPeriod.Window window, OrderStats current, OrderStats previous,
            BuyerStats buyers, Products products, Portfolio portfolio, Rules rules) {
        var result=new ArrayList<Insight>();
        var link=ordersLink(Map.of("from",window.period().from().toString(),"to",window.period().to().toString()));
        if(previous!=null) {
            BigDecimal currentRate=rawPercent(number(current.cancelledOrderCount()),number(current.orderCount()));
            BigDecimal oldRate=rawPercent(number(previous.cancelledOrderCount()),number(previous.orderCount()));
            if(current.orderCount()>=rules.minimumOrderSample() && previous.orderCount()>=rules.minimumOrderSample()
                    && currentRate.compareTo(rules.cancellationRateThreshold())>=0
                    && currentRate.subtract(oldRate).compareTo(rules.cancellationIncreasePoints())>=0) {
                var query=new LinkedHashMap<>(link.query());query.put("status","CANCELLED");
                result.add(signal("CANCELLATION_INCREASE","HIGH","Cancelamento aumentou",
                        "Cancelados: "+current.cancelledOrderCount()+" de "+current.orderCount()+"; referência: "+previous.cancelledOrderCount()+" de "+previous.orderCount()+".",
                        "cancellationRate",current.cancellationRate(),previous.cancellationRate(),current.cancellationRate().subtract(previous.cancellationRate()),
                        current.orderCount(),previous.orderCount(),"rate >= "+rules.cancellationRateThreshold()+"; increase >= "+rules.cancellationIncreasePoints()+"pp; samples >= "+rules.minimumOrderSample(),
                        current.cancelledOrderCount(),current.cancelledOrderValue(),"Revisar os pedidos cancelados e investigar os casos.",ordersLink(query),
                        List.of("Não há motivo nem instante de cancelamento registrados; o conjunto usa criação do pedido.")));
            }
            if(previous.orderCount()>=rules.minimumOrderSample() &&
                    rawChange(number(current.orderCount()),number(previous.orderCount())).compareTo(rules.demandDropPercent().negate())<=0)
                result.add(signal("ORDER_DEMAND_DROP","MEDIUM","Volume de pedidos caiu","Comparação com a janela anterior equivalente.",
                        "orderCount",number(current.orderCount()),number(previous.orderCount()),changePercent(number(current.orderCount()),number(previous.orderCount())),
                        current.orderCount(),previous.orderCount(),"drop >= "+rules.demandDropPercent()+"%; previous sample >= "+rules.minimumOrderSample(),
                        current.orderCount(),current.requestedValue(),"Examinar as duas janelas e verificar o alcance da queda.",link,
                        List.of("A variação não demonstra causa e pode refletir sazonalidade.")));
            if(previous.activeOrderCount()>=rules.minimumOrderSample() && previous.retainedOrderValue().signum()>0 &&
                    rawChange(current.retainedOrderValue(),previous.retainedOrderValue()).compareTo(rules.retainedValueGrowthPercent())>=0)
                result.add(signal("RETAINED_VALUE_GROWTH","INFO","Valor comercial ativo cresceu","Conferir a decomposição entre volume e valor médio.",
                        "retainedOrderValue",current.retainedOrderValue(),previous.retainedOrderValue(),changePercent(current.retainedOrderValue(),previous.retainedOrderValue()),
                        current.activeOrderCount(),previous.activeOrderCount(),"growth >= "+rules.retainedValueGrowthPercent()+"%; previous sample >= "+rules.minimumOrderSample(),
                        current.activeOrderCount(),current.retainedOrderValue(),"Conferir os efeitos de quantidade e valor médio.",link,
                        List.of("Valor de pedido ativo não é receita recebida.")));
        }
        for(var p:products.declining()) result.add(signal("PRODUCT_VALUE_DROP","MEDIUM","Produto com queda de valor ativo",p.productName(),
                "productRetainedValue",p.retainedOrderValue(),p.previousRetainedOrderValue(),p.changePercent(),p.activeOrderCount(),p.previousActiveOrderCount(),
                "drop >= "+rules.productDropPercent()+"%; previous sample >= "+rules.minimumProductSample(),
                p.previousActiveOrderCount(),p.previousRetainedOrderValue().subtract(p.retainedOrderValue()),
                "Examinar o produto e o comparativo antes de alterar a oferta.",
                new DrillDown("GET","/api/queries/products/"+p.productId(),Map.of(),"cqrs.read"),
                List.of("Não existe filtro de pedidos por produto; estoque, custo e margem não estão modelados.")));
        if(buyers.activeBuyerCount()>=rules.minimumBuyerSample() && buyers.top5CustomerValueShare()!=null &&
                rawPercent(buyers.top5CustomerRetainedValue(),current.retainedOrderValue()).compareTo(rules.customerConcentrationPercent())>=0)
            result.add(signal("CUSTOMER_CONCENTRATION","MEDIUM","Valor ativo concentrado em poucos clientes","Participação dos cinco maiores clientes no valor ativo.",
                    "top5CustomerValueShare",buyers.top5CustomerValueShare(),null,null,buyers.activeBuyerCount(),0,
                    "share >= "+rules.customerConcentrationPercent()+"%; buyers >= "+rules.minimumBuyerSample(),
                    current.activeOrderCount(),current.retainedOrderValue(),"Avaliar a dependência de poucos clientes.",link,
                    List.of("Concentração não prova risco de inadimplência.")));
        if(portfolio.olderThanOrEqual48h().orderCount()>0) {
            var age=portfolio.olderThanOrEqual48h();
            result.add(signal("AGING_OPEN_PORTFOLIO","HIGH","Pedidos permanecem abertos há pelo menos 48 horas",
                    "Carteira global, independente do período comercial selecionado.","openOrdersAge48h",number(age.orderCount()),null,null,
                    age.orderCount(),0,"age >= 48h",age.orderCount(),age.value(),"Revisar a lista de pedidos ainda abertos.",age.drillDown(),
                    List.of("CREATED não comprova atraso de entrega, inadimplência ou SLA violado.")));
        }
        result.sort(Comparator.comparingInt((Insight i)->switch(i.priority()){case "HIGH"->0;case "MEDIUM"->1;default->2;})
                .thenComparing((Insight i)->i.exposure().value(),Comparator.reverseOrder()).thenComparing(Insight::code)
                .thenComparing(i->i.drillDown().path()));
        return List.copyOf(result);
    }
    private BigDecimal rawPercent(BigDecimal a,BigDecimal b){return b.signum()==0?null:a.multiply(number(100)).divide(b,MathContext.DECIMAL128);}
    private BigDecimal rawChange(BigDecimal a,BigDecimal b){return rawPercent(a.subtract(b),b);}
    private Insight signal(String code,String priority,String title,String description,String metric,BigDecimal now,
            BigDecimal old,BigDecimal delta,long count,long oldCount,String rule,long exposureCount,BigDecimal amount,
            String action,DrillDown link,List<String> limitations) {
        return new Insight(code,priority,title,description,new Evidence(metric,now,old,delta,count,oldCount,rule),
                new Exposure(exposureCount,amount),action,link,limitations);
    }
}
