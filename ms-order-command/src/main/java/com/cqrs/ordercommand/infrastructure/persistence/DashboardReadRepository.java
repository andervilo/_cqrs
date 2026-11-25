package com.cqrs.ordercommand.infrastructure.persistence;

import com.cqrs.ordercommand.application.dashboard.*;
import static com.cqrs.ordercommand.application.dashboard.DashboardSnapshot.*;
import com.cqrs.ordercommand.application.dashboard.DashboardSnapshot.Period;
import static com.cqrs.ordercommand.application.dashboard.DashboardMath.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

/** All large scans/groupings stay in PostgreSQL; Java receives scalars, <=32 days, <=35 products. */
@Repository
public class DashboardReadRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public DashboardReadRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }
    private MapSqlParameterSource bounds(Instant from, Instant to) {
        return new MapSqlParameterSource().addValue("start",Timestamp.from(from)).addValue("end",Timestamp.from(to));
    }

    public OrderStats orders(Period period) {
        var p = bounds(period.from(),period.to());
        return jdbc.queryForObject("""
                WITH o AS MATERIALIZED (
                  SELECT id,status,total_amount FROM order_command.orders WHERE created_at>=:start AND created_at<:end
                ), totals AS (
                  SELECT count(*) AS total, count(*) FILTER (WHERE status='CREATED') AS active,
                    count(*) FILTER (WHERE status='CANCELLED') AS cancelled,
                    coalesce(sum(total_amount),0) AS requested_value,
                    coalesce(sum(total_amount) FILTER (WHERE status='CREATED'),0) AS active_value,
                    coalesce(sum(total_amount) FILTER (WHERE status='CANCELLED'),0) AS cancelled_value FROM o
                ), units AS (
                  SELECT coalesce(sum(i.quantity),0) AS requested_units,
                    coalesce(sum(i.quantity) FILTER (WHERE o.status='CREATED'),0) AS active_units,
                    coalesce(sum(i.quantity) FILTER (WHERE o.status='CANCELLED'),0) AS cancelled_units
                  FROM o JOIN order_command.item i ON i.order_id=o.id
                ) SELECT * FROM totals CROSS JOIN units
                """,p,(rs,n) -> new OrderStats(rs.getLong("total"),rs.getLong("active"),rs.getLong("cancelled"),
                rs.getBigDecimal("requested_value").setScale(2),rs.getBigDecimal("active_value").setScale(2),
                rs.getBigDecimal("cancelled_value").setScale(2),percent(number(rs.getLong("cancelled")),number(rs.getLong("total"))),
                percent(rs.getBigDecimal("cancelled_value"),rs.getBigDecimal("requested_value")),
                ratio(rs.getBigDecimal("active_value"),number(rs.getLong("active"))),
                rs.getLong("requested_units"),rs.getLong("active_units"),rs.getLong("cancelled_units")));
    }

    public BuyerStats buyers(Period period, OrderStats orders) {
        return jdbc.queryForObject("""
                WITH buyers AS (
                  SELECT customer_id,sum(total_amount) AS amount,count(*) AS orders
                  FROM order_command.orders WHERE status='CREATED' AND created_at>=:start AND created_at<:end
                  GROUP BY customer_id
                ), ranked AS (
                  SELECT *,row_number() OVER (ORDER BY amount DESC,customer_id) AS rank,
                    EXISTS (SELECT 1 FROM order_command.orders old WHERE old.customer_id=buyers.customer_id
                      AND old.status='CREATED' AND old.created_at<:start) AS is_returning FROM buyers
                ) SELECT count(*) AS total,count(*) FILTER (WHERE is_returning) AS returning_count,
                  coalesce(sum(amount) FILTER (WHERE rank<=5),0) AS top_value FROM ranked
                """,bounds(period.from(),period.to()),(rs,n) -> {
            long all=rs.getLong("total"), returning=rs.getLong("returning_count");
            return new BuyerStats(all,all-returning,returning,percent(number(returning),number(all)),
                    ratio(number(orders.activeOrderCount()),number(all)),percent(rs.getBigDecimal("top_value"),orders.retainedOrderValue()),
                    rs.getBigDecimal("top_value").setScale(2));
        });
    }

    public record RankedProduct(Product product, long valueRank, long unitsRank, long dropRank, boolean declining,
                                BigDecimal top5Value, BigDecimal previousTop5Value) {}

    public List<RankedProduct> products(Period current, Comparison previous, OrderStats totals, Rules rules) {
        boolean comparable=previous.status().equals("AVAILABLE");
        var p=bounds(current.from(),current.to()).addValue("previousStart",Timestamp.from(previous.from()))
                .addValue("previousEnd",Timestamp.from(comparable?previous.to():previous.from()))
                .addValue("minimumSample",rules.minimumProductSample()).addValue("dropPercent",rules.productDropPercent());
        return jdbc.query("""
                WITH base AS MATERIALIZED (
                  SELECT o.id,o.created_at,o.status,i.product_id,i.product_name,i.quantity,
                    i.unit_price*i.quantity AS amount,
                    o.created_at>=:start AND o.created_at<:end AS current
                  FROM order_command.orders o JOIN order_command.item i ON i.order_id=o.id
                  WHERE (o.created_at>=:start AND o.created_at<:end)
                     OR (o.created_at>=:previousStart AND o.created_at<:previousEnd)
                ), names AS (
                  SELECT DISTINCT ON (product_id) product_id,product_name FROM base
                  ORDER BY product_id,created_at DESC,id DESC
                ), grouped AS (
                  SELECT product_id,
                    coalesce(sum(quantity) FILTER (WHERE current AND status='CREATED'),0)::bigint AS units,
                    count(*) FILTER (WHERE current AND status='CREATED') AS orders,
                    coalesce(sum(amount) FILTER (WHERE current AND status='CREATED'),0) AS amount,
                    coalesce(sum(amount) FILTER (WHERE current AND status='CANCELLED'),0) AS cancelled,
                    coalesce(sum(quantity) FILTER (WHERE NOT current AND status='CREATED'),0)::bigint AS old_units,
                    count(*) FILTER (WHERE NOT current AND status='CREATED') AS old_orders,
                    coalesce(sum(amount) FILTER (WHERE NOT current AND status='CREATED'),0) AS old_amount,
                    coalesce(sum(amount) FILTER (WHERE NOT current AND status='CANCELLED'),0) AS old_cancelled
                  FROM base GROUP BY product_id
                ), candidates AS (
                  SELECT *,old_orders>=:minimumSample AND old_amount>0
                    AND (old_amount-amount)*100>=old_amount*:dropPercent AS declining FROM grouped
                ), ranked AS (
                  SELECT *,row_number() OVER (ORDER BY amount DESC,units DESC,product_id) AS value_rank,
                    row_number() OVER (ORDER BY units DESC,amount DESC,product_id) AS units_rank,
                    row_number() OVER (ORDER BY old_amount DESC,old_units DESC,product_id) AS old_rank,
                    row_number() OVER (ORDER BY declining DESC,(old_amount-amount) DESC,product_id) AS drop_rank
                  FROM candidates
                ), with_totals AS (
                  SELECT *,coalesce(sum(amount) FILTER (WHERE value_rank<=5) OVER (),0) AS top_value,
                    coalesce(sum(old_amount) FILTER (WHERE old_rank<=5) OVER (),0) AS old_top_value FROM ranked
                ) SELECT r.*,n.product_name FROM with_totals r JOIN names n USING(product_id)
                  WHERE (value_rank<=10 AND amount>0) OR (units_rank<=10 AND units>0)
                    OR (declining AND drop_rank<=10) OR old_rank<=5
                """,p,(rs,n) -> {
            BigDecimal amount=rs.getBigDecimal("amount").setScale(2), old=rs.getBigDecimal("old_amount").setScale(2);
            BigDecimal cancelled=rs.getBigDecimal("cancelled").setScale(2);
            var product=new Product(rs.getObject("product_id",UUID.class),rs.getString("product_name"),rs.getLong("units"),
                    rs.getLong("orders"),amount,cancelled,comparable?rs.getLong("old_units"):null,
                    comparable?rs.getLong("old_orders"):null,comparable?old:null,
                    comparable?rs.getBigDecimal("old_cancelled").setScale(2):null,comparable?amount.subtract(old):null,
                    comparable?changePercent(amount,old):null,!comparable?"CALENDAR_ALIGNMENT_LIMIT":old.signum()==0?"NO_BASELINE":"AVAILABLE",
                    percent(amount,totals.retainedOrderValue()),percent(cancelled,amount.add(cancelled)));
            return new RankedProduct(product,rs.getLong("value_rank"),rs.getLong("units_rank"),rs.getLong("drop_rank"),
                    comparable&&rs.getBoolean("declining"),rs.getBigDecimal("top_value"),rs.getBigDecimal("old_top_value"));
        });
    }

    public List<Day> days(Period period) {
        var rows=jdbc.query("""
                WITH o AS MATERIALIZED (
                  SELECT * FROM order_command.orders WHERE created_at>=:start AND created_at<:end
                ), units AS (
                  SELECT i.order_id,sum(i.quantity)::bigint AS units FROM order_command.item i JOIN o ON o.id=i.order_id
                  GROUP BY i.order_id
                ) SELECT (o.created_at AT TIME ZONE 'America/Sao_Paulo')::date AS day,
                  count(*) AS total,count(*) FILTER (WHERE status='CREATED') AS active,
                  count(*) FILTER (WHERE status='CANCELLED') AS cancelled,
                  sum(total_amount) AS requested_value,
                  coalesce(sum(total_amount) FILTER (WHERE status='CREATED'),0) AS active_value,
                  coalesce(sum(total_amount) FILTER (WHERE status='CANCELLED'),0) AS cancelled_value,
                  coalesce(sum(units),0)::bigint AS requested_units,
                  coalesce(sum(units) FILTER (WHERE status='CREATED'),0)::bigint AS active_units,
                  coalesce(sum(units) FILTER (WHERE status='CANCELLED'),0)::bigint AS cancelled_units,
                  count(DISTINCT customer_id) FILTER (WHERE status='CREATED') AS buyers
                FROM o LEFT JOIN units ON units.order_id=o.id GROUP BY day ORDER BY day
                """,bounds(period.from(),period.to()),(rs,n) -> new Day(rs.getDate("day").toLocalDate(),null,null,
                rs.getLong("total"),rs.getLong("active"),rs.getLong("cancelled"),
                rs.getBigDecimal("requested_value").setScale(2),rs.getBigDecimal("active_value").setScale(2),
                rs.getBigDecimal("cancelled_value").setScale(2),rs.getLong("requested_units"),rs.getLong("active_units"),
                rs.getLong("cancelled_units"),rs.getLong("buyers")));
        var byDate=new HashMap<LocalDate,Day>(); rows.forEach(d -> byDate.put(d.date(),d));
        var result=new ArrayList<Day>();
        for(LocalDate date=period.from().atZone(DashboardPeriod.ZONE).toLocalDate();
            date.atStartOfDay(DashboardPeriod.ZONE).toInstant().isBefore(period.to());date=date.plusDays(1)) {
            Instant from=date.atStartOfDay(DashboardPeriod.ZONE).toInstant();
            Instant to=date.plusDays(1).atStartOfDay(DashboardPeriod.ZONE).toInstant();
            if(from.isBefore(period.from())) from=period.from();
            if(to.isAfter(period.to())) to=period.to();
            Day d=byDate.getOrDefault(date,new Day(date,null,null,0,0,0,ZERO,ZERO,ZERO,0,0,0,0));
            result.add(new Day(date,from,to,d.orderCount(),d.activeOrderCount(),d.cancelledOrderCount(),d.requestedValue(),
                    d.retainedOrderValue(),d.cancelledOrderValue(),d.requestedUnits(),d.retainedUnits(),d.cancelledUnits(),d.activeBuyerCount()));
        }
        return List.copyOf(result);
    }

    public record PortfolioRow(int bucket, long count, BigDecimal amount, Instant oldest) {}
    public List<PortfolioRow> portfolio(Instant reference) {
        var p=new MapSqlParameterSource().addValue("reference",Timestamp.from(reference));
        return jdbc.query("""
                SELECT CASE WHEN created_at>CAST(:reference AS timestamptz)-interval '24 hours' THEN 0
                    WHEN created_at>CAST(:reference AS timestamptz)-interval '48 hours' THEN 1
                    WHEN created_at>CAST(:reference AS timestamptz)-interval '72 hours' THEN 2 ELSE 3 END AS bucket,
                  count(*) AS total,sum(total_amount) AS amount,min(created_at) AS oldest
                FROM order_command.orders WHERE status='CREATED' AND created_at<:reference GROUP BY bucket
                """,p,(rs,n)->new PortfolioRow(rs.getInt("bucket"),rs.getLong("total"),
                rs.getBigDecimal("amount").setScale(2),rs.getTimestamp("oldest").toInstant()));
    }
    public Instant earliestOrder() {
        return jdbc.queryForObject("SELECT min(created_at) FROM order_command.orders",Map.of(),
                (rs,n)->rs.getTimestamp(1)==null?null:rs.getTimestamp(1).toInstant());
    }
}
