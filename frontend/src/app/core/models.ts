export interface Page<T> {
    content: T[];
    page: number;
    size: number;
    totalElements: number;
    totalPages: number;
}

export type PeriodCode = 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'MONTH_TO_DATE' | 'PREVIOUS_MONTH';
export const PERIOD_CODES: readonly PeriodCode[] = ['TODAY', 'LAST_7_DAYS', 'LAST_30_DAYS', 'MONTH_TO_DATE', 'PREVIOUS_MONTH'];
export const DEFAULT_PERIOD: PeriodCode = 'LAST_30_DAYS';

// ---------- Dashboard ----------
export interface Metric {
    value: number | null;
    previousValue: number | null;
    changeAbsolute: number | null;
    changePercent: number | null;
    changePercentagePoints: number | null;
    status: string;
    previousStatus: string;
    comparisonStatus: string;
    unit: 'COUNT' | 'BRL' | 'PERCENT' | 'RATIO';
    favorableDirection: 'UP' | 'DOWN' | 'CONTEXT_DEPENDENT';
}
export interface DrillDown {
    method: string;
    path: string;
    query: Record<string, string>;
    requiredScope: string;
}
export interface DashboardProduct {
    productId: string;
    productName: string;
    retainedUnits: number;
    activeOrderCount: number;
    retainedOrderValue: number;
    cancelledOrderValue: number;
    changeAbsolute: number | null;
    changePercent: number | null;
    comparisonStatus: string;
    retainedValueShare: number | null;
    cancellationValueRate: number | null;
}
export interface DashboardDay {
    date: string;
    from: string;
    to: string;
    orderCount: number;
    activeOrderCount: number;
    cancelledOrderCount: number;
    requestedValue: number;
    retainedOrderValue: number;
    cancelledOrderValue: number;
    requestedUnits: number;
    retainedUnits: number;
    cancelledUnits: number;
    activeBuyerCount: number;
}
export interface AgeBucket {
    code: string;
    orderCount: number;
    value: number;
    drillDown: DrillDown;
}
export interface Insight {
    code: string;
    priority: 'HIGH' | 'MEDIUM' | 'INFO';
    title: string;
    description: string;
    evidence: { metric: string; currentValue: number | null; previousValue: number | null; change: number | null; currentSampleSize: number; previousSampleSize: number; rule: string };
    exposure: { orderCount: number; value: number };
    suggestedAction: string;
    drillDown: DrillDown | null;
    limitations: string[];
}
export interface OrderStats {
    orderCount: number;
    activeOrderCount: number;
    cancelledOrderCount: number;
    requestedValue: number;
    retainedOrderValue: number;
    cancelledOrderValue: number;
    cancellationRate: number | null;
    cancellationValueRate: number | null;
    averageActiveOrderValue: number | null;
    requestedUnits: number;
    retainedUnits: number;
    cancelledUnits: number;
}
export interface BuyerStats {
    activeBuyerCount: number;
    newActiveBuyerCount: number;
    returningActiveBuyerCount: number;
    returningBuyerShare: number | null;
    ordersPerActiveBuyer: number | null;
    top5CustomerValueShare: number | null;
    top5CustomerRetainedValue: number | null;
}
export interface DashboardView {
    dashboardId: string;
    periodCode: PeriodCode;
    timeZone: string;
    currency: string;
    sourceObservedAt: string;
    computedAt: string;
    definitionsVersion: number;
    rulesVersion: number;
    period: { from: string; to: string; inProgress: boolean };
    comparison: { mode: string; from: string | null; to: string | null; status: string; durationSeconds: number };
    summary: Record<string, Metric>;
    orders: OrderStats;
    previousOrders: OrderStats | null;
    customers: BuyerStats;
    previousCustomers: BuyerStats | null;
    valueChangeDrivers: { status: string; totalChange: number | null; volumeEffect: number | null; averageValueEffect: number | null } | null;
    products: {
        top5ProductValueShare: number | null;
        previousTop5ProductValueShare: number | null;
        otherProductsRetainedValue: number | null;
        byRetainedValue: DashboardProduct[];
        byRetainedUnits: DashboardProduct[];
        declining: DashboardProduct[];
    };
    daily: DashboardDay[];
    comparisonDaily: DashboardDay[];
    openPortfolio: { scope: string; ageBuckets: AgeBucket[]; orderCount: number; value: number; olderThanOrEqual48h: AgeBucket | null; oldestCreatedAt: string | null };
    insights: Insight[];
    additionalInsightCount: number;
    unavailableMetrics: { code: string; value: number | null; status: string; reason: string }[];
    appliedRules: Record<string, number | string>;
    dataCoverage: { source: string; generation: string; earliestOrderAt: string | null; completeness: string };
}
export interface DashboardResponse {
    dashboard: DashboardView;
    availability: { status: 'READY' | 'STALE'; ageSeconds: number; projectedAt: string | null; warning: string | null };
    servedAt: string;
}

// ---------- Projections de consulta ----------
export interface OrderSummary { orderId: string; customerId: string; customerName: string; createdAt: string; totalAmount: number; currency: string; status: string; itemCount: number }
export interface OrderDetail {
    orderId: string;
    createdAt: string;
    customer: { customerId: string; name: string };
    items: { productId: string; productName: string; quantity: number; unitPrice: number; subtotal: number }[];
    subtotalAmount: number;
    discountAmount: number;
    shippingAmount: number;
    totalAmount: number;
    currency: string;
    status: string;
}
export interface ProductCatalogItem { productId: string; name: string; price: number; currency: string; thumbnailUrl: string | null; available: boolean; category: string | null }
export interface ProductDetail { productId: string; name: string; description: string; price: number; currency: string; imageUrls: string[]; available: boolean; attributes: Record<string, string> }
export interface CustomerSummary { customerId: string; name: string; email: string; orderCount: number; lastOrderAt: string | null }
export interface CustomerDetail { customerId: string; name: string; email: string; phone: string | null; addresses: Record<string, unknown>[]; createdAt: string; orderCount: number; lifetimeValue: number }
export interface CustomerOrderHistoryItem { orderId: string; customerId: string; createdAt: string; status: string; totalAmount: number; currency: string; itemCount: number; firstProductName: string | null }

// ---------- Comandos ----------
export interface Customer { id: string; name: string; email: string; phone: string | null; active: boolean; createdAt: string; updatedAt: string; version: number }
export interface CustomerInput { name: string; email: string; phone: string | null }
export interface CustomerUpdate extends CustomerInput { active: boolean }
export interface Product { id: string; sku: string; name: string; description: string | null; unitPrice: number; active: boolean; createdAt: string; updatedAt: string; version: number }
export interface ProductInput { sku: string; name: string; description: string | null; unitPrice: number }
export interface ProductUpdate extends ProductInput { active: boolean }
export interface OrderItemRequest { productId: string; quantity: number }
export interface OrderCreate { customerId: string; items: OrderItemRequest[]; notes: string | null }
export interface OrderItem { id: string; productId: string; productSku: string; productName: string; quantity: number; unitPrice: number; subtotal: number }
export interface Order { id: string; customerId: string; status: 'CREATED' | 'CANCELLED'; totalAmount: number; notes: string | null; items: OrderItem[]; createdAt: string; updatedAt: string; version: number }

export type OutboxStatus = 'PENDING' | 'SENDED' | 'ERRO';
export const EVENT_TYPES = ['CUSTOMER_SUMMARY', 'CUSTOMER_DETAIL', 'PRODUCT_CATALOG', 'PRODUCT_DETAIL', 'ORDER_SUMMARY', 'ORDER_DETAIL', 'CUSTOMER_ORDER_HISTORY', 'SALES_DASHBOARD'] as const;
export interface OutboxEntry {
    id: string;
    orderId: string | null;
    dataCriacao: string;
    dataEnvio: string | null;
    status: OutboxStatus;
    eventType: string;
    operation: string;
    projectionKey: string;
    projectionVersion: number;
    schemaVersion: number;
    attemptCount: number;
    nextAttemptAt: string | null;
    lastError: string | null;
    payload: unknown;
}
export interface RebuildResult { resourceType: string; processed: number; nextCursor: string | null; hasMore: boolean }
export interface DashboardRebuildAccepted { requestId: string; periodCodes: string[] }
