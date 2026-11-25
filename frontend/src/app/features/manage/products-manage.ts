import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { TableModule, TablePageEvent } from 'primeng/table';
import { TextareaModule } from 'primeng/textarea';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { ApiService } from '@/app/core/api.service';
import { Product } from '@/app/core/models';
import { ApiProblem, toProblem } from '@/app/core/problem';
import { dateTime, money } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { ActiveTag, EmptyState } from '@/app/shared/tags';
import { debounce, injectUrlState } from '@/app/shared/url-state';

const SKU_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

@Component({
    selector: 'app-products-manage',
    standalone: true,
    imports: [FormsModule, TableModule, ButtonModule, DialogModule, InputTextModule, InputNumberModule, TextareaModule, ToggleSwitchModule, MessageModule, ProblemMessage, ActiveTag, EmptyState],
    template: `
        <div class="card">
            <div class="flex items-center justify-between mb-4">
                <h2 class="text-2xl font-semibold m-0">Gestão de produtos</h2>
                <p-button label="Novo produto" icon="pi pi-plus" (onClick)="open()" />
            </div>
            <div class="flex flex-col gap-1 mb-4"><label for="q">Busca</label><input id="q" pInputText [ngModel]="q()" (ngModelChange)="onSearch($event)" placeholder="Nome ou SKU" class="w-80" /></div>
            <p-message severity="info" styleClass="mb-4 w-full">A consulta (leitura) é atualizada de forma assíncrona; alterações podem levar alguns instantes para aparecer nas telas de consulta.</p-message>
            <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
            <p-table [value]="res.value()?.content ?? []" [lazy]="true" [lazyLoadOnInit]="false" [paginator]="true" [rows]="url.size()" [first]="url.page() * url.size()" [totalRecords]="res.value()?.totalElements ?? 0" [rowsPerPageOptions]="[10, 20, 50, 100]" [loading]="res.isLoading()" (onPage)="onPage($event)">
                <ng-template #header><tr><th>SKU</th><th>Nome</th><th class="text-right">Preço</th><th>Status</th><th>Atualizado</th><th></th></tr></ng-template>
                <ng-template #body let-p>
                    <tr>
                        <td><code>{{ p.sku }}</code></td><td>{{ p.name }}</td><td class="text-right">{{ money(p.unitPrice) }}</td>
                        <td><app-active-tag [active]="p.active" /></td><td>{{ dateTime(p.updatedAt) }}</td>
                        <td class="text-right whitespace-nowrap">
                            <p-button icon="pi pi-pencil" [text]="true" ariaLabel="Editar" (onClick)="open(p)" />
                            <p-button icon="pi pi-trash" [text]="true" severity="danger" ariaLabel="Excluir" (onClick)="confirmDelete(p)" />
                        </td>
                    </tr>
                </ng-template>
                <ng-template #emptymessage><tr><td colspan="6"><app-empty text="Nenhum produto." /></td></tr></ng-template>
            </p-table>
        </div>

        <p-dialog [header]="editing() ? 'Editar produto' : 'Novo produto'" [(visible)]="visible" [modal]="true" [style]="{ width: '34rem' }" [closable]="!saving()">
            <div class="flex flex-col gap-4">
                @if (formError(); as e) { <app-problem [error]="e" /> }
                <div class="flex flex-col gap-1"><label for="f-sku">SKU *</label><input id="f-sku" pInputText maxlength="64" [(ngModel)]="sku" /><small class="text-red-500">{{ errors().sku }}</small></div>
                <div class="flex flex-col gap-1"><label for="f-name">Nome *</label><input id="f-name" pInputText maxlength="160" [(ngModel)]="name" /><small class="text-red-500">{{ errors().name }}</small></div>
                <div class="flex flex-col gap-1"><label for="f-desc">Descrição</label><textarea id="f-desc" pTextarea rows="3" maxlength="2000" [(ngModel)]="description"></textarea></div>
                <div class="flex flex-col gap-1"><label for="f-price">Preço unitário *</label><p-inputnumber inputId="f-price" mode="decimal" [minFractionDigits]="2" [maxFractionDigits]="2" [min]="0" [max]="999999999.99" [(ngModel)]="price" /><small class="text-red-500">{{ errors().price }}</small></div>
                @if (editing()) { <div class="flex items-center gap-2"><p-toggleswitch inputId="f-active" [(ngModel)]="active" /><label for="f-active">Ativo</label></div> }
            </div>
            <ng-template #footer>
                <p-button label="Cancelar" severity="secondary" [text]="true" [disabled]="saving()" (onClick)="visible = false" />
                <p-button label="Salvar" icon="pi pi-check" [loading]="saving()" [disabled]="!valid()" (onClick)="save()" />
            </ng-template>
        </p-dialog>
    `
})
export class ProductsManagePage {
    private readonly api = inject(ApiService);
    private readonly toast = inject(MessageService);
    private readonly confirm = inject(ConfirmationService);
    protected readonly url = injectUrlState();
    protected readonly dateTime = dateTime;
    protected readonly money = money;
    protected readonly q = computed(() => this.url.get('q') ?? '');

    protected readonly res = rxResource({
        params: () => ({ q: this.q().trim() || null, page: this.url.page(), size: this.url.size() }),
        stream: ({ params }) => this.api.products(params)
    });
    protected readonly onSearch = debounce((v: string) => this.url.set({ q: v.trim() || null }));

    protected visible = false;
    protected readonly editing = signal<Product | null>(null);
    protected readonly saving = signal(false);
    protected readonly formError = signal<ApiProblem | null>(null);
    protected sku = '';
    protected name = '';
    protected description = '';
    protected price: number | null = null;
    protected active = true;

    protected errors() {
        return {
            sku: !SKU_RE.test(this.sku) ? 'SKU inválido (letras, números, ".", "_" ou "-"; até 64).' : '',
            name: !this.name.trim() ? 'Informe o nome.' : '',
            price: this.price === null || this.price < 0.01 ? 'Preço mínimo de 0,01.' : ''
        };
    }
    protected valid(): boolean {
        const e = this.errors();
        return !e.sku && !e.name && !e.price;
    }

    protected open(p?: Product): void {
        this.editing.set(p ?? null);
        this.sku = p?.sku ?? '';
        this.name = p?.name ?? '';
        this.description = p?.description ?? '';
        this.price = p?.unitPrice ?? null;
        this.active = p?.active ?? true;
        this.formError.set(null);
        this.visible = true;
    }

    protected save(): void {
        if (!this.valid() || this.saving()) return;
        const body = { sku: this.sku.trim(), name: this.name.trim(), description: this.description.trim() || null, unitPrice: this.price! };
        const cur = this.editing();
        const call = cur ? this.api.updateProduct(cur.id, { ...body, active: this.active }) : this.api.createProduct(body);
        this.saving.set(true);
        this.formError.set(null);
        call.subscribe({
            next: () => {
                this.saving.set(false);
                this.visible = false;
                this.toast.add({ severity: 'success', summary: cur ? 'Produto atualizado' : 'Produto criado' });
                this.res.reload();
            },
            error: (e) => {
                this.saving.set(false);
                this.formError.set(toProblem(e));
            }
        });
    }

    protected confirmDelete(p: Product): void {
        this.confirm.confirm({
            header: 'Excluir produto',
            message: `Excluir "${p.name}"? Esta ação não pode ser desfeita.`,
            acceptLabel: 'Excluir',
            rejectLabel: 'Cancelar',
            acceptButtonProps: { severity: 'danger' },
            accept: () =>
                this.api.deleteProduct(p.id).subscribe({
                    next: () => {
                        this.toast.add({ severity: 'success', summary: 'Produto excluído' });
                        this.res.reload();
                    },
                    error: (e) => {
                        const pr = toProblem(e);
                        this.toast.add({ severity: 'error', summary: pr.status === 409 ? 'Não é possível excluir' : 'Falha ao excluir', detail: pr.detail ?? pr.title, life: 6000 });
                    }
                })
        });
    }

    protected onPage(e: TablePageEvent): void {
        this.url.set({ page: e.first / e.rows, size: e.rows }, true);
    }
}

export default ProductsManagePage;
