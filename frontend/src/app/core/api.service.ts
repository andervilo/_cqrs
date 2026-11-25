import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from './config.service';
import {
    Customer, CustomerDetail, CustomerInput, CustomerOrderHistoryItem, CustomerSummary, CustomerUpdate, DashboardRebuildAccepted, DashboardResponse, Order,
    OrderCreate, OrderDetail, OrderItemRequest, OrderSummary, OutboxEntry, Page, PeriodCode, Product, ProductCatalogItem, ProductDetail, ProductInput,
    ProductUpdate, RebuildResult
} from './models';
import { mapProblem } from './problem';

type Query = Record<string, string | number | boolean | null | undefined>;

function params(query: Query): HttpParams {
    let p = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
        if (value === null || value === undefined || value === '') continue;
        p = p.set(key, String(value));
    }
    return p;
}

/** Cliente HTTP único do gateway: consultas (`/api/queries`) e comandos (`/api/v1`). */
@Injectable({ providedIn: 'root' })
export class ApiService {
    private readonly http = inject(HttpClient);
    private readonly config = inject(ConfigService);

    private url(path: string): string {
        return this.config.value().apiBaseUrl + path;
    }
    private get<T>(path: string, query: Query = {}): Observable<T> {
        return this.http.get<T>(this.url(path), { params: params(query) }).pipe(mapProblem());
    }

    // ----- Consultas -----
    dashboard(period: PeriodCode) { return this.get<DashboardResponse>('/api/queries/dashboard/sales', { period }); }
    orders(q: Query) { return this.get<Page<OrderSummary>>('/api/queries/orders', q); }
    order(id: string) { return this.get<OrderDetail>(`/api/queries/orders/${id}`); }
    catalog(q: Query) { return this.get<Page<ProductCatalogItem>>('/api/queries/products', q); }
    productDetail(id: string) { return this.get<ProductDetail>(`/api/queries/products/${id}`); }
    customerSummaries(q: Query) { return this.get<Page<CustomerSummary>>('/api/queries/customers', q); }
    customerDetail(id: string) { return this.get<CustomerDetail>(`/api/queries/customers/${id}`); }
    customerOrders(id: string, q: Query) { return this.get<Page<CustomerOrderHistoryItem>>(`/api/queries/customers/${id}/orders`, q); }

    // ----- Comandos: clientes -----
    customers(q: Query) { return this.get<Page<Customer>>('/api/v1/customers', q); }
    customer(id: string) { return this.get<Customer>(`/api/v1/customers/${id}`); }
    createCustomer(body: CustomerInput) { return this.http.post<Customer>(this.url('/api/v1/customers'), body).pipe(mapProblem()); }
    updateCustomer(id: string, body: CustomerUpdate) { return this.http.put<Customer>(this.url(`/api/v1/customers/${id}`), body).pipe(mapProblem()); }
    deleteCustomer(id: string) { return this.http.delete<void>(this.url(`/api/v1/customers/${id}`)).pipe(mapProblem()); }

    // ----- Comandos: produtos -----
    products(q: Query) { return this.get<Page<Product>>('/api/v1/products', q); }
    product(id: string) { return this.get<Product>(`/api/v1/products/${id}`); }
    createProduct(body: ProductInput) { return this.http.post<Product>(this.url('/api/v1/products'), body).pipe(mapProblem()); }
    updateProduct(id: string, body: ProductUpdate) { return this.http.put<Product>(this.url(`/api/v1/products/${id}`), body).pipe(mapProblem()); }
    deleteProduct(id: string) { return this.http.delete<void>(this.url(`/api/v1/products/${id}`)).pipe(mapProblem()); }

    // ----- Comandos: pedidos -----
    commandOrders(q: Query) { return this.get<Page<Order>>('/api/v1/orders', q); }
    commandOrder(id: string) { return this.get<Order>(`/api/v1/orders/${id}`); }
    createOrder(body: OrderCreate) { return this.http.post<Order>(this.url('/api/v1/orders'), body).pipe(mapProblem()); }
    cancelOrder(id: string) { return this.http.post<Order>(this.url(`/api/v1/orders/${id}/cancel`), null).pipe(mapProblem()); }
    replaceItems(id: string, items: OrderItemRequest[]) { return this.http.put<Order>(this.url(`/api/v1/orders/${id}/items`), { items }).pipe(mapProblem()); }

    // ----- Operações -----
    outbox(q: Query) { return this.get<Page<OutboxEntry>>('/api/v1/order-outbox', q); }
    outboxEntry(id: string) { return this.get<OutboxEntry>(`/api/v1/order-outbox/${id}`); }
    retryOutbox(id: string) { return this.http.post<void>(this.url(`/api/v1/order-outbox/${id}/retry`), null).pipe(mapProblem()); }
    rebuild(resourceType: 'CUSTOMER' | 'PRODUCT', after: string | null, size: number) {
        return this.http.post<RebuildResult>(this.url('/api/v1/projections/rebuild'), null, { params: params({ resourceType, after, size }) }).pipe(mapProblem());
    }
    rebuildDashboard() {
        return this.http.post<DashboardRebuildAccepted>(this.url('/api/v1/projections/rebuild'), null, { params: params({ resourceType: 'DASHBOARD' }) }).pipe(mapProblem());
    }
}
