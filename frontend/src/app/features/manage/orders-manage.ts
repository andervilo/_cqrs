import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule, TablePageEvent } from 'primeng/table';
import { ApiService } from '@/app/core/api.service';
import { UUID_RE, dateTime, money, shortId } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { EmptyState, StatusTag } from '@/app/shared/tags';
import { injectUrlState } from '@/app/shared/url-state';

const STATUSES = [
    { label: 'Todos', value: null },
    { label: 'Criado', value: 'CREATED' },
    { label: 'Cancelado', value: 'CANCELLED' }
];

@Component({
    selector: 'app-orders-manage',
    standalone: true,
    imports: [FormsModule, RouterLink, TableModule, ButtonModule, InputTextModule, SelectModule, ProblemMessage, StatusTag, EmptyState],
    template: `
        <div class="card">
            <div class="flex items-center justify-between mb-4">
                <h2 class="text-2xl font-semibold m-0">Gestão de pedidos</h2>
                <p-button label="Novo pedido" icon="pi pi-plus" routerLink="/manage/orders/new" />
            </div>
            <div class="flex flex-wrap gap-3 items-end mb-4">
                <div class="flex flex-col gap-1"><label for="o-status">Status</label><p-select inputId="o-status" [options]="statuses" optionLabel="label" optionValue="value" [ngModel]="status()" (ngModelChange)="url.set({ status: $event })" styleClass="w-44" /></div>
                <div class="flex flex-col gap-1">
                    <label for="o-cust">Cliente (ID)</label>
                    <input id="o-cust" pInputText class="w-80" placeholder="UUID do cliente" [ngModel]="customerInput" (ngModelChange)="onCustomer($event)" [class.ng-invalid]="invalid" [class.ng-dirty]="invalid" />
                </div>
            </div>
            <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
            <p-table [value]="res.value()?.content ?? []" [lazy]="true" [lazyLoadOnInit]="false" [paginator]="true" [rows]="url.size()" [first]="url.page() * url.size()" [totalRecords]="res.value()?.totalElements ?? 0" [rowsPerPageOptions]="[10, 20, 50, 100]" [loading]="res.isLoading()" (onPage)="onPage($event)">
                <ng-template #header><tr><th>Pedido</th><th>Cliente</th><th>Criado</th><th class="text-right">Itens</th><th class="text-right">Total</th><th>Status</th></tr></ng-template>
                <ng-template #body let-o>
                    <tr>
                        <td><a [routerLink]="['/manage/orders', o.id]" class="text-primary font-medium">{{ shortId(o.id) }}</a></td>
                        <td>{{ shortId(o.customerId) }}</td>
                        <td>{{ dateTime(o.createdAt) }}</td>
                        <td class="text-right">{{ o.items.length }}</td>
                        <td class="text-right">{{ money(o.totalAmount) }}</td>
                        <td><app-status-tag [status]="o.status" /></td>
                    </tr>
                </ng-template>
                <ng-template #emptymessage><tr><td colspan="6"><app-empty text="Nenhum pedido." /></td></tr></ng-template>
            </p-table>
        </div>
    `
})
export class OrdersManagePage {
    private readonly api = inject(ApiService);
    protected readonly url = injectUrlState();
    protected readonly statuses = STATUSES;
    protected readonly dateTime = dateTime;
    protected readonly money = money;
    protected readonly shortId = shortId;
    protected readonly status = computed(() => {
        const v = this.url.get('status');
        return v === 'CREATED' || v === 'CANCELLED' ? v : null;
    });
    private readonly customerId = computed(() => {
        const v = this.url.get('customerId');
        return v && UUID_RE.test(v) ? v : null;
    });
    protected customerInput = this.url.get('customerId') ?? '';
    protected invalid = false;

    protected readonly res = rxResource({
        params: () => ({ status: this.status(), customerId: this.customerId(), page: this.url.page(), size: this.url.size() }),
        stream: ({ params }) => this.api.commandOrders(params)
    });

    protected onCustomer(v: string): void {
        this.customerInput = v;
        const t = v.trim();
        this.invalid = t !== '' && !UUID_RE.test(t);
        if (!this.invalid) this.url.set({ customerId: t || null });
    }
    protected onPage(e: TablePageEvent): void {
        this.url.set({ page: e.first / e.rows, size: e.rows }, true);
    }
}

export default OrdersManagePage;
