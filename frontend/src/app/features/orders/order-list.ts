import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DatePickerModule } from 'primeng/datepicker';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule, TablePageEvent } from 'primeng/table';
import { ChipModule } from 'primeng/chip';
import { ApiService } from '@/app/core/api.service';
import { UUID_RE, dateTime, money, shortId } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { EmptyState, StatusTag } from '@/app/shared/tags';
import { injectUrlState } from '@/app/shared/url-state';

const SORTS = [
    { label: 'Mais recentes', value: 'createdAt,desc' },
    { label: 'Maior valor', value: 'totalAmount,desc' }
];
const STATUSES = [
    { label: 'Todos', value: null },
    { label: 'Criado', value: 'CREATED' },
    { label: 'Cancelado', value: 'CANCELLED' }
];
const validDate = (v: string | null) => (v && !Number.isNaN(Date.parse(v)) ? v : null);

@Component({
    selector: 'app-order-list',
    standalone: true,
    imports: [FormsModule, RouterLink, TableModule, SelectModule, DatePickerModule, InputTextModule, ButtonModule, ChipModule, ProblemMessage, StatusTag, EmptyState],
    template: `
        <div class="card">
            <h2 class="text-2xl font-semibold mb-1">Pedidos</h2>
            <p class="text-muted-color mb-4">Consulta (projeção <code>ORDER_SUMMARY</code>). Dados podem estar alguns instantes defasados.</p>

            <div class="flex flex-wrap gap-3 items-end mb-4">
                <div class="flex flex-col gap-1">
                    <label for="f-status">Status</label>
                    <p-select inputId="f-status" [options]="statuses" optionLabel="label" optionValue="value" [ngModel]="status()" (ngModelChange)="url.set({ status: $event })" styleClass="w-44" />
                </div>
                <div class="flex flex-col gap-1">
                    <label for="f-from">De</label>
                    <p-datepicker inputId="f-from" [ngModel]="fromDate()" (ngModelChange)="url.set({ from: $event ? $event.toISOString() : null })" [showTime]="true" [showButtonBar]="true" dateFormat="dd/mm/yy" />
                </div>
                <div class="flex flex-col gap-1">
                    <label for="f-to">Até (exclusivo)</label>
                    <p-datepicker inputId="f-to" [ngModel]="toDate()" (ngModelChange)="url.set({ to: $event ? $event.toISOString() : null })" [showTime]="true" [showButtonBar]="true" dateFormat="dd/mm/yy" />
                </div>
                <div class="flex flex-col gap-1">
                    <label for="f-customer">Cliente (ID)</label>
                    <input id="f-customer" pInputText [ngModel]="customerInput" (ngModelChange)="onCustomer($event)" placeholder="UUID do cliente" class="w-80" [class.ng-invalid]="customerInvalid" [class.ng-dirty]="customerInvalid" />
                </div>
                <div class="flex flex-col gap-1">
                    <label for="f-sort">Ordenação</label>
                    <p-select inputId="f-sort" [options]="sorts" optionLabel="label" optionValue="value" [ngModel]="url.sort()" (ngModelChange)="url.set({ sort: $event })" styleClass="w-44" />
                </div>
            </div>

            @if (activeFilters().length) {
                <div class="flex flex-wrap gap-2 mb-4">
                    @for (f of activeFilters(); track f.key) {
                        <p-chip [label]="f.label" [removable]="true" (onRemove)="url.set(clear(f.key))" />
                    }
                </div>
            }

            <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />

            <p-table
                [value]="res.value()?.content ?? []"
                [lazy]="true"
                [lazyLoadOnInit]="false"
                [paginator]="true"
                [rows]="url.size()"
                [first]="url.page() * url.size()"
                [totalRecords]="res.value()?.totalElements ?? 0"
                [rowsPerPageOptions]="[10, 20, 50, 100]"
                [loading]="res.isLoading()"
                (onPage)="onPage($event)"
                [tableStyle]="{ 'min-width': '60rem' }"
            >
                <ng-template #header>
                    <tr><th>Pedido</th><th>Cliente</th><th>Data</th><th class="text-right">Total</th><th>Status</th><th class="text-right">Itens</th></tr>
                </ng-template>
                <ng-template #body let-o>
                    <tr>
                        <td><a [routerLink]="['/orders', o.orderId]" class="text-primary font-medium">{{ shortId(o.orderId) }}</a></td>
                        <td><a [routerLink]="['/customers', o.customerId]" class="hover:underline">{{ o.customerName }}</a></td>
                        <td>{{ dateTime(o.createdAt) }}</td>
                        <td class="text-right">{{ money(o.totalAmount, o.currency) }}</td>
                        <td><app-status-tag [status]="o.status" /></td>
                        <td class="text-right">{{ o.itemCount }}</td>
                    </tr>
                </ng-template>
                <ng-template #emptymessage><tr><td colspan="6"><app-empty text="Nenhum pedido encontrado." /></td></tr></ng-template>
            </p-table>
        </div>
    `
})
export class OrderListPage {
    private readonly api = inject(ApiService);
    protected readonly url = injectUrlState(SORTS.map((s) => s.value));
    protected readonly sorts = SORTS;
    protected readonly statuses = STATUSES;
    protected readonly dateTime = dateTime;
    protected readonly money = money;
    protected readonly shortId = shortId;

    protected readonly status = computed(() => {
        const v = this.url.get('status');
        return v === 'CREATED' || v === 'CANCELLED' ? v : null;
    });
    private readonly from = computed(() => validDate(this.url.get('from')));
    private readonly to = computed(() => validDate(this.url.get('to')));
    private readonly customerId = computed(() => {
        const v = this.url.get('customerId');
        return v && UUID_RE.test(v) ? v : null;
    });
    protected readonly fromDate = computed(() => (this.from() ? new Date(this.from()!) : null));
    protected readonly toDate = computed(() => (this.to() ? new Date(this.to()!) : null));

    protected customerInput = this.url.get('customerId') ?? '';
    protected customerInvalid = false;

    protected readonly activeFilters = computed(() => {
        const list: { key: string; label: string }[] = [];
        if (this.status()) list.push({ key: 'status', label: `Status: ${this.status()}` });
        if (this.from()) list.push({ key: 'from', label: `De: ${dateTime(this.from())}` });
        if (this.to()) list.push({ key: 'to', label: `Até: ${dateTime(this.to())}` });
        if (this.customerId()) list.push({ key: 'customerId', label: `Cliente: ${shortId(this.customerId()!)}` });
        return list;
    });

    protected readonly res = rxResource({
        params: () => ({ customerId: this.customerId(), status: this.status(), from: this.from(), to: this.to(), sort: this.url.sort(), page: this.url.page(), size: this.url.size() }),
        stream: ({ params }) => this.api.orders(params)
    });

    protected onCustomer(value: string): void {
        this.customerInput = value;
        const v = value.trim();
        this.customerInvalid = v !== '' && !UUID_RE.test(v);
        if (!this.customerInvalid) this.url.set({ customerId: v || null });
    }

    protected clear(key: string): Record<string, null> {
        return { [key]: null };
    }

    protected onPage(e: TablePageEvent): void {
        this.url.set({ page: e.first / e.rows, size: e.rows }, true);
    }
}

export default OrderListPage;
