import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule, TablePageEvent } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ApiService } from '@/app/core/api.service';
import { money } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { EmptyState } from '@/app/shared/tags';
import { debounce, injectUrlState } from '@/app/shared/url-state';

const SORTS = [
    { label: 'Nome (A–Z)', value: 'name,asc' },
    { label: 'Maior preço', value: 'price,desc' }
];
const AVAIL = [
    { label: 'Todos', value: null },
    { label: 'Disponíveis', value: 'true' },
    { label: 'Indisponíveis', value: 'false' }
];
const decimal = (v: string | null) => (v !== null && /^\d+(\.\d{1,2})?$/.test(v) ? Number(v) : null);

@Component({
    selector: 'app-product-catalog',
    standalone: true,
    imports: [FormsModule, RouterLink, TableModule, SelectModule, InputTextModule, InputNumberModule, ButtonModule, TagModule, ProblemMessage, EmptyState],
    template: `
        <div class="card">
            <h2 class="text-2xl font-semibold mb-1">Catálogo de produtos</h2>
            <p class="text-muted-color mb-4">Consulta (projeção <code>PRODUCT_CATALOG</code>).</p>
            <div class="flex flex-wrap gap-3 items-end mb-4">
                <div class="flex flex-col gap-1"><label for="p-search">Busca</label><input id="p-search" pInputText [ngModel]="search()" (ngModelChange)="onSearch($event)" placeholder="Nome ou SKU" /></div>
                <div class="flex flex-col gap-1"><label for="p-cat">Categoria</label><input id="p-cat" pInputText [ngModel]="category()" (ngModelChange)="onCategory($event)" /></div>
                <div class="flex flex-col gap-1"><label for="p-av">Disponibilidade</label><p-select inputId="p-av" [options]="avail" optionLabel="label" optionValue="value" [ngModel]="available()" (ngModelChange)="url.set({ available: $event })" styleClass="w-44" /></div>
                <div class="flex flex-col gap-1"><label for="p-min">Preço mín.</label><p-inputnumber inputId="p-min" mode="decimal" [minFractionDigits]="2" [maxFractionDigits]="2" [min]="0" [ngModel]="minPrice()" (ngModelChange)="onPrice('minPrice', $event)" /></div>
                <div class="flex flex-col gap-1"><label for="p-max">Preço máx.</label><p-inputnumber inputId="p-max" mode="decimal" [minFractionDigits]="2" [maxFractionDigits]="2" [min]="0" [ngModel]="maxPrice()" (ngModelChange)="onPrice('maxPrice', $event)" /></div>
                <div class="flex flex-col gap-1"><label for="p-sort">Ordenação</label><p-select inputId="p-sort" [options]="sorts" optionLabel="label" optionValue="value" [ngModel]="url.sort()" (ngModelChange)="url.set({ sort: $event })" styleClass="w-44" /></div>
            </div>
            @if (priceRangeInvalid()) { <small class="text-red-500 block mb-3">O preço mínimo não pode ser maior que o máximo.</small> }
            <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
            <p-table [value]="res.value()?.content ?? []" [lazy]="true" [lazyLoadOnInit]="false" [paginator]="true" [rows]="url.size()" [first]="url.page() * url.size()" [totalRecords]="res.value()?.totalElements ?? 0" [rowsPerPageOptions]="[10, 20, 50, 100]" [loading]="res.isLoading()" (onPage)="onPage($event)">
                <ng-template #header><tr><th>Produto</th><th>Categoria</th><th class="text-right">Preço</th><th>Disponibilidade</th></tr></ng-template>
                <ng-template #body let-p>
                    <tr>
                        <td><a [routerLink]="['/products', p.productId]" class="text-primary font-medium">{{ p.name }}</a></td>
                        <td>{{ p.category ?? '—' }}</td>
                        <td class="text-right">{{ money(p.price, p.currency) }}</td>
                        <td><p-tag [value]="p.available ? 'Disponível' : 'Indisponível'" [severity]="p.available ? 'success' : 'secondary'" /></td>
                    </tr>
                </ng-template>
                <ng-template #emptymessage><tr><td colspan="4"><app-empty text="Nenhum produto encontrado." /></td></tr></ng-template>
            </p-table>
        </div>
    `
})
export class ProductCatalogPage {
    private readonly api = inject(ApiService);
    protected readonly url = injectUrlState(SORTS.map((s) => s.value));
    protected readonly sorts = SORTS;
    protected readonly avail = AVAIL;
    protected readonly money = money;

    protected readonly search = computed(() => this.url.get('search') ?? '');
    protected readonly category = computed(() => this.url.get('category') ?? '');
    protected readonly available = computed(() => {
        const v = this.url.get('available');
        return v === 'true' || v === 'false' ? v : null;
    });
    protected readonly minPrice = computed(() => decimal(this.url.get('minPrice')));
    protected readonly maxPrice = computed(() => decimal(this.url.get('maxPrice')));
    protected readonly priceRangeInvalid = computed(() => this.minPrice() !== null && this.maxPrice() !== null && this.minPrice()! > this.maxPrice()!);

    protected readonly res = rxResource({
        params: () => (this.priceRangeInvalid() ? undefined : { search: this.search().trim() || null, category: this.category().trim() || null, available: this.available(), minPrice: this.minPrice(), maxPrice: this.maxPrice(), sort: this.url.sort(), page: this.url.page(), size: this.url.size() }),
        stream: ({ params }) => this.api.catalog(params)
    });

    protected readonly onSearch = debounce((v: string) => this.url.set({ search: v.trim() || null }));
    protected readonly onCategory = debounce((v: string) => this.url.set({ category: v.trim() || null }));

    protected readonly onPrice = debounce((key: 'minPrice' | 'maxPrice', n: number | null) => {
        this.url.set({ [key]: n !== null && Number.isFinite(n) && n >= 0 ? n.toFixed(2) : null });
    });

    protected onPage(e: TablePageEvent): void {
        this.url.set({ page: e.first / e.rows, size: e.rows }, true);
    }
}

export default ProductCatalogPage;
