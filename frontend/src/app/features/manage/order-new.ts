import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { ApiService } from '@/app/core/api.service';
import { Customer } from '@/app/core/models';
import { ApiProblem, toProblem } from '@/app/core/problem';
import { ProblemMessage } from '@/app/shared/problem-message';
import { ItemRow, ItemsEditor, itemErrors } from './items-editor';

@Component({
    selector: 'app-order-new',
    standalone: true,
    imports: [FormsModule, ButtonModule, MessageModule, SelectModule, TextareaModule, ProblemMessage, ItemsEditor],
    template: `
        <div class="card">
            <h2 class="text-2xl font-semibold mb-4">Novo pedido</h2>
            <app-problem [error]="error()" />
            <div class="flex flex-col gap-4 max-w-4xl">
                <div class="flex flex-col gap-1">
                    <label for="o-customer">Cliente *</label>
                    <p-select inputId="o-customer" [options]="customers()" optionLabel="label" optionValue="id" [filter]="true" filterBy="label" placeholder="Selecione" [(ngModel)]="customerId" [loading]="loadingCustomers()" />
                </div>
                <div>
                    <div class="font-semibold mb-2">Itens *</div>
                    <app-items-editor [(rows)]="rows" />
                    @for (e of errors(); track e) { <small class="text-red-500 block mt-1">{{ e }}</small> }
                </div>
                <div class="flex flex-col gap-1"><label for="o-notes">Observações</label><textarea id="o-notes" pTextarea rows="3" maxlength="1000" [(ngModel)]="notes"></textarea></div>
                <div class="flex gap-2">
                    <p-button label="Criar pedido" icon="pi pi-check" [loading]="saving()" [disabled]="!ok()" (onClick)="submit()" />
                    <p-button label="Cancelar" severity="secondary" [text]="true" (onClick)="back()" />
                </div>
            </div>
        </div>
    `
})
export class OrderNewPage {
    private readonly api = inject(ApiService);
    private readonly router = inject(Router);
    private readonly toast = inject(MessageService);
    protected readonly customers = signal<(Customer & { label: string })[]>([]);
    protected readonly loadingCustomers = signal(true);
    protected readonly rows = signal<ItemRow[]>([{ productId: null, quantity: 1 }]);
    protected readonly saving = signal(false);
    protected readonly error = signal<ApiProblem | null>(null);
    protected customerId: string | null = null;
    protected notes = '';

    constructor() {
        this.api.customers({ size: 100, page: 0 }).subscribe({
            next: (p) => {
                this.customers.set(p.content.filter((c) => c.active).map((c) => ({ ...c, label: `${c.name} <${c.email}>` })));
                this.loadingCustomers.set(false);
            },
            error: (e) => {
                this.error.set(toProblem(e));
                this.loadingCustomers.set(false);
            }
        });
    }

    protected errors(): string[] {
        return itemErrors(this.rows());
    }
    protected ok(): boolean {
        return !!this.customerId && this.errors().length === 0 && !this.saving();
    }

    // POST não é repetido automaticamente: o usuário decide após ver o erro.
    protected submit(): void {
        if (!this.ok()) return;
        this.saving.set(true);
        this.error.set(null);
        this.api
            .createOrder({ customerId: this.customerId!, items: this.rows().map((r) => ({ productId: r.productId!, quantity: r.quantity })), notes: this.notes.trim() || null })
            .subscribe({
                next: (o) => {
                    this.toast.add({ severity: 'success', summary: 'Pedido criado' });
                    this.router.navigate(['/manage/orders', o.id]);
                },
                error: (e) => {
                    this.saving.set(false);
                    this.error.set(toProblem(e));
                }
            });
    }

    protected back(): void {
        this.router.navigate(['/manage/orders']);
    }
}

export default OrderNewPage;
