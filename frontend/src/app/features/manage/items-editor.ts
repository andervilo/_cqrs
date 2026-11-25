import { Component, inject, model, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { ApiService } from '@/app/core/api.service';
import { Product } from '@/app/core/models';
import { money } from '@/app/core/util';

export interface ItemRow {
    productId: string | null;
    quantity: number;
}

export const MAX_ITEMS = 1000;
export const MAX_QTY = 10000;

/** Erros de validação das linhas (produto obrigatório, sem duplicatas, quantidade 1..10000). */
export function itemErrors(rows: ItemRow[]): string[] {
    const errors: string[] = [];
    if (rows.length < 1) errors.push('Adicione ao menos um item.');
    if (rows.length > MAX_ITEMS) errors.push(`No máximo ${MAX_ITEMS} itens.`);
    if (rows.some((r) => !r.productId)) errors.push('Selecione o produto em todas as linhas.');
    const ids = rows.map((r) => r.productId).filter(Boolean);
    if (new Set(ids).size !== ids.length) errors.push('Há produtos repetidos.');
    if (rows.some((r) => !Number.isInteger(r.quantity) || r.quantity < 1 || r.quantity > MAX_QTY)) errors.push(`Quantidade deve estar entre 1 e ${MAX_QTY}.`);
    return errors;
}

@Component({
    selector: 'app-items-editor',
    standalone: true,
    imports: [FormsModule, ButtonModule, InputNumberModule, SelectModule],
    template: `
        @for (r of rows(); track $index) {
            <div class="flex flex-wrap items-center gap-2 mb-2">
                <p-select class="flex-1 min-w-64" [options]="products()" optionLabel="label" optionValue="id" [filter]="true" filterBy="label" placeholder="Produto" [ngModel]="r.productId" (ngModelChange)="r.productId = $event; touch()" [loading]="loading()" appendTo="body" [attr.aria-label]="'Produto ' + ($index + 1)" />
                <p-inputnumber [ngModel]="r.quantity" (ngModelChange)="r.quantity = $event; touch()" [min]="1" [max]="10000" [useGrouping]="false" [showButtons]="true" [attr.aria-label]="'Quantidade ' + ($index + 1)" />
                <span class="w-32 text-right text-muted-color">{{ subtotal(r) }}</span>
                <p-button icon="pi pi-times" [text]="true" severity="danger" ariaLabel="Remover item" (onClick)="remove($index)" />
            </div>
        }
        <p-button label="Adicionar item" icon="pi pi-plus" severity="secondary" size="small" (onClick)="add()" />
    `
})
export class ItemsEditor {
    private readonly api = inject(ApiService);
    readonly rows = model<ItemRow[]>([]);
    protected readonly products = signal<(Product & { label: string })[]>([]);
    protected readonly loading = signal(true);

    constructor() {
        // Só produtos ativos podem entrar em pedidos novos.
        this.api.products({ size: 100, page: 0 }).subscribe({
            next: (p) => {
                this.products.set(p.content.filter((x) => x.active).map((x) => ({ ...x, label: `${x.name} (${x.sku})` })));
                this.loading.set(false);
            },
            error: () => this.loading.set(false)
        });
    }

    protected touch(): void {
        this.rows.set([...this.rows()]);
    }
    protected add(): void {
        this.rows.set([...this.rows(), { productId: null, quantity: 1 }]);
    }
    protected remove(i: number): void {
        this.rows.set(this.rows().filter((_, idx) => idx !== i));
    }
    protected subtotal(r: ItemRow): string {
        const p = this.products().find((x) => x.id === r.productId);
        return p ? money(p.unitPrice * (r.quantity || 0)) : '';
    }
}
