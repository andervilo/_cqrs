import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule, TablePageEvent } from 'primeng/table';
import { ApiService } from '@/app/core/api.service';
import { dateTime, num } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { EmptyState } from '@/app/shared/tags';
import { debounce, injectUrlState } from '@/app/shared/url-state';

const SORTS = [
    { label: 'Nome (A–Z)', value: 'name,asc' },
    { label: 'Compra mais recente', value: 'lastOrderAt,desc' }
];

@Component({
    selector: 'app-customer-list',
    standalone: true,
    imports: [FormsModule, RouterLink, TableModule, SelectModule, InputTextModule, ProblemMessage, EmptyState],
    template: `
        <div class="card">
            <h2 class="text-2xl font-semibold mb-1">Clientes</h2>
            <p class="text-muted-color mb-4">Consulta (projeção <code>CUSTOMER_SUMMARY</code>).</p>
            <div class="flex flex-wrap gap-3 items-end mb-4">
                <div class="flex flex-col gap-1"><label for="c-search">Busca</label><input id="c-search" pInputText [ngModel]="search()" (ngModelChange)="onSearch($event)" placeholder="Nome ou e-mail" class="w-80" /></div>
                <div class="flex flex-col gap-1"><label for="c-sort">Ordenação</label><p-select inputId="c-sort" [options]="sorts" optionLabel="label" optionValue="value" [ngModel]="url.sort()" (ngModelChange)="url.set({ sort: $event })" styleClass="w-56" /></div>
            </div>
            <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
            <p-table [value]="res.value()?.content ?? []" [lazy]="true" [lazyLoadOnInit]="false" [paginator]="true" [rows]="url.size()" [first]="url.page() * url.size()" [totalRecords]="res.value()?.totalElements ?? 0" [rowsPerPageOptions]="[10, 20, 50, 100]" [loading]="res.isLoading()" (onPage)="onPage($event)">
                <ng-template #header><tr><th>Nome</th><th>E-mail</th><th class="text-right">Pedidos</th><th>Última compra</th></tr></ng-template>
                <ng-template #body let-c>
                    <tr>
                        <td><a [routerLink]="['/customers', c.customerId]" class="text-primary font-medium">{{ c.name }}</a></td>
                        <td>{{ c.email }}</td>
                        <td class="text-right">{{ num(c.orderCount) }}</td>
                        <td>{{ c.lastOrderAt ? dateTime(c.lastOrderAt) : '—' }}</td>
                    </tr>
                </ng-template>
                <ng-template #emptymessage><tr><td colspan="4"><app-empty text="Nenhum cliente encontrado." /></td></tr></ng-template>
            </p-table>
        </div>
    `
})
export class CustomerListPage {
    private readonly api = inject(ApiService);
    protected readonly url = injectUrlState(SORTS.map((s) => s.value));
    protected readonly sorts = SORTS;
    protected readonly dateTime = dateTime;
    protected readonly num = num;
    protected readonly search = computed(() => this.url.get('search') ?? '');
    protected readonly res = rxResource({
        params: () => ({ search: this.search().trim() || null, sort: this.url.sort(), page: this.url.page(), size: this.url.size() }),
        stream: ({ params }) => this.api.customerSummaries(params)
    });
    protected readonly onSearch = debounce((v: string) => this.url.set({ search: v.trim() || null }));
    protected onPage(e: TablePageEvent): void {
        this.url.set({ page: e.first / e.rows, size: e.rows }, true);
    }
}

export default CustomerListPage;
