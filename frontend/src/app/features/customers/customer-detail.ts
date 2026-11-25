import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TableModule, TablePageEvent } from 'primeng/table';
import { of } from 'rxjs';
import { ApiService } from '@/app/core/api.service';
import { dateTime, money, num, shortId } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { EmptyState, StatusTag } from '@/app/shared/tags';
import { injectRouteId, injectUrlState } from '@/app/shared/url-state';

const SORTS = [
    { label: 'Mais recentes', value: 'createdAt,desc' },
    { label: 'Maior valor', value: 'totalAmount,desc' }
];

@Component({
    selector: 'app-customer-detail',
    standalone: true,
    imports: [FormsModule, RouterLink, ButtonModule, MessageModule, SelectModule, TableModule, ProblemMessage, StatusTag, EmptyState],
    template: `
        <div class="card">
            <div class="flex items-center justify-between mb-4">
                <h2 class="text-2xl font-semibold m-0">Cliente</h2>
                <p-button label="Atualizar" icon="pi pi-refresh" severity="secondary" [loading]="detail.isLoading()" (onClick)="reload()" />
            </div>
            @if (!valid()) {
                <p-message severity="warn">Identificador de cliente inválido.</p-message>
            } @else {
                <app-problem [error]="detail.error()" [retryable]="true" (retry)="detail.reload()" />
                @if (detail.value(); as c) {
                    <div class="grid grid-cols-12 gap-4">
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Nome</div>{{ c.name }}</div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">E-mail</div>{{ c.email }}</div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Telefone</div>{{ c.phone ?? '—' }}</div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Cliente desde</div>{{ dateTime(c.createdAt) }}</div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Pedidos</div>{{ num(c.orderCount) }}</div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Valor total (LTV)</div>{{ money(c.lifetimeValue) }}</div>
                    </div>
                    @if (c.addresses.length) {
                        <div class="font-semibold mt-4 mb-1">Endereços</div>
                        @for (a of c.addresses; track $index) { <div class="text-sm text-muted-color">{{ addressText(a) }}</div> }
                    }
                }
                <div class="flex items-center justify-between mt-6 mb-3">
                    <h3 class="text-xl font-semibold m-0">Histórico de pedidos</h3>
                    <p-select [options]="sorts" optionLabel="label" optionValue="value" [ngModel]="url.sort()" (ngModelChange)="url.set({ sort: $event })" ariaLabel="Ordenação" styleClass="w-48" />
                </div>
                <app-problem [error]="orders.error()" [retryable]="true" (retry)="orders.reload()" />
                <p-table [value]="orders.value()?.content ?? []" [lazy]="true" [lazyLoadOnInit]="false" [paginator]="true" [rows]="url.size()" [first]="url.page() * url.size()" [totalRecords]="orders.value()?.totalElements ?? 0" [rowsPerPageOptions]="[10, 20, 50, 100]" [loading]="orders.isLoading()" (onPage)="onPage($event)">
                    <ng-template #header><tr><th>Pedido</th><th>Data</th><th>Primeiro produto</th><th class="text-right">Itens</th><th class="text-right">Total</th><th>Status</th></tr></ng-template>
                    <ng-template #body let-o>
                        <tr>
                            <td><a [routerLink]="['/orders', o.orderId]" class="text-primary">{{ shortId(o.orderId) }}</a></td>
                            <td>{{ dateTime(o.createdAt) }}</td>
                            <td>{{ o.firstProductName ?? '—' }}</td>
                            <td class="text-right">{{ o.itemCount }}</td>
                            <td class="text-right">{{ money(o.totalAmount, o.currency) }}</td>
                            <td><app-status-tag [status]="o.status" /></td>
                        </tr>
                    </ng-template>
                    <ng-template #emptymessage><tr><td colspan="6"><app-empty text="Este cliente ainda não tem pedidos." /></td></tr></ng-template>
                </p-table>
            }
        </div>
    `
})
export class CustomerDetailPage {
    private readonly api = inject(ApiService);
    private readonly route = injectRouteId('customerId');
    protected readonly valid = this.route.valid;
    protected readonly url = injectUrlState(SORTS.map((s) => s.value));
    protected readonly sorts = SORTS;
    protected readonly dateTime = dateTime;
    protected readonly money = money;
    protected readonly num = num;
    protected readonly shortId = shortId;

    protected readonly detail = rxResource({
        params: () => (this.route.valid() ? this.route.id() : undefined),
        stream: ({ params }) => (params ? this.api.customerDetail(params) : of(undefined))
    });
    protected readonly orders = rxResource({
        params: () => (this.route.valid() ? { id: this.route.id(), sort: this.url.sort(), page: this.url.page(), size: this.url.size() } : undefined),
        stream: ({ params }) => (params ? this.api.customerOrders(params.id, { sort: params.sort, page: params.page, size: params.size }) : of(undefined))
    });
    protected readonly hasAddresses = computed(() => (this.detail.value()?.addresses.length ?? 0) > 0);

    protected reload(): void {
        this.detail.reload();
        this.orders.reload();
    }
    protected addressText(a: Record<string, unknown>): string {
        return Object.values(a).filter((v) => typeof v === 'string' || typeof v === 'number').join(', ');
    }
    protected onPage(e: TablePageEvent): void {
        this.url.set({ page: e.first / e.rows, size: e.rows }, true);
    }
}

export default CustomerDetailPage;
