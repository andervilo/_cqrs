import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { of } from 'rxjs';
import { ApiService } from '@/app/core/api.service';
import { Order } from '@/app/core/models';
import { ApiProblem, toProblem } from '@/app/core/problem';
import { dateTime, money } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { StatusTag } from '@/app/shared/tags';
import { injectRouteId } from '@/app/shared/url-state';
import { ItemRow, ItemsEditor, itemErrors } from './items-editor';

@Component({
    selector: 'app-order-manage-detail',
    standalone: true,
    imports: [RouterLink, ButtonModule, MessageModule, TableModule, ProblemMessage, StatusTag, ItemsEditor],
    template: `
        <div class="card">
            <div class="flex items-center justify-between mb-4">
                <h2 class="text-2xl font-semibold m-0">Pedido</h2>
                <div class="flex gap-2">
                    <p-button label="Ver na consulta" icon="pi pi-search" severity="secondary" [routerLink]="['/orders', id()]" />
                    <p-button label="Atualizar" icon="pi pi-refresh" severity="secondary" [loading]="res.isLoading()" (onClick)="res.reload()" />
                </div>
            </div>
            @if (!valid()) {
                <p-message severity="warn">Identificador de pedido inválido.</p-message>
            } @else {
                <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
                <app-problem [error]="actionError()" />
                @if (res.value(); as o) {
                    <div class="grid grid-cols-12 gap-4 mb-4">
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">ID</div><code>{{ o.id }}</code></div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Cliente</div><a [routerLink]="['/customers', o.customerId]" class="text-primary">{{ o.customerId }}</a></div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Status</div><app-status-tag [status]="o.status" /></div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Criado</div>{{ dateTime(o.createdAt) }}</div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Atualizado</div>{{ dateTime(o.updatedAt) }}</div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Total</div><strong>{{ money(o.totalAmount) }}</strong></div>
                        @if (o.notes) { <div class="col-span-12"><div class="text-muted-color">Observações</div>{{ o.notes }}</div> }
                    </div>

                    @if (!editing()) {
                        <p-table [value]="o.items">
                            <ng-template #header><tr><th>SKU</th><th>Produto</th><th class="text-right">Qtd.</th><th class="text-right">Preço</th><th class="text-right">Subtotal</th></tr></ng-template>
                            <ng-template #body let-i><tr><td><code>{{ i.productSku }}</code></td><td>{{ i.productName }}</td><td class="text-right">{{ i.quantity }}</td><td class="text-right">{{ money(i.unitPrice) }}</td><td class="text-right">{{ money(i.subtotal) }}</td></tr></ng-template>
                        </p-table>
                        @if (o.status === 'CREATED') {
                            <div class="flex gap-2 mt-4">
                                <p-button label="Alterar itens" icon="pi pi-pencil" (onClick)="startEdit(o)" />
                                <p-button label="Cancelar pedido" icon="pi pi-ban" severity="danger" [loading]="busy()" (onClick)="confirmCancel(o)" />
                            </div>
                        } @else {
                            <p-message severity="secondary" styleClass="mt-4">Pedido cancelado: não pode mais ser alterado.</p-message>
                        }
                    } @else {
                        <div class="font-semibold mb-2">Novos itens (substituem os atuais)</div>
                        <app-items-editor [(rows)]="rows" />
                        @for (e of errors(); track e) { <small class="text-red-500 block mt-1">{{ e }}</small> }
                        <div class="flex gap-2 mt-4">
                            <p-button label="Salvar itens" icon="pi pi-check" [loading]="busy()" [disabled]="errors().length > 0" (onClick)="saveItems(o)" />
                            <p-button label="Descartar" severity="secondary" [text]="true" [disabled]="busy()" (onClick)="editing.set(false)" />
                        </div>
                    }
                }
            }
        </div>
    `
})
export class OrderManageDetailPage {
    private readonly api = inject(ApiService);
    private readonly toast = inject(MessageService);
    private readonly confirm = inject(ConfirmationService);
    private readonly route = injectRouteId('orderId');
    protected readonly id = this.route.id;
    protected readonly valid = this.route.valid;
    protected readonly dateTime = dateTime;
    protected readonly money = money;

    protected readonly res = rxResource({
        params: () => (this.route.valid() ? this.route.id() : undefined),
        stream: ({ params }) => (params ? this.api.commandOrder(params) : of(undefined))
    });
    protected readonly editing = signal(false);
    protected readonly rows = signal<ItemRow[]>([]);
    protected readonly busy = signal(false);
    protected readonly actionError = signal<ApiProblem | null>(null);
    protected readonly errors = computed(() => itemErrors(this.rows()));

    protected startEdit(o: Order): void {
        this.rows.set(o.items.map((i) => ({ productId: i.productId, quantity: i.quantity })));
        this.actionError.set(null);
        this.editing.set(true);
    }

    protected confirmCancel(o: Order): void {
        this.confirm.confirm({
            header: 'Cancelar pedido',
            message: 'Cancelar este pedido? A ação não pode ser desfeita.',
            acceptLabel: 'Cancelar pedido',
            rejectLabel: 'Voltar',
            acceptButtonProps: { severity: 'danger' },
            accept: () => this.run(this.api.cancelOrder(o.id), 'Pedido cancelado')
        });
    }

    protected saveItems(o: Order): void {
        if (this.errors().length) return;
        this.run(
            this.api.replaceItems(o.id, this.rows().map((r) => ({ productId: r.productId!, quantity: r.quantity }))),
            'Itens atualizados'
        );
    }

    // Sem retry automático: 409 (ex.: pedido já cancelado) é mostrado e a tela é recarregada.
    private run(call: ReturnType<ApiService['cancelOrder']>, success: string): void {
        this.busy.set(true);
        this.actionError.set(null);
        call.subscribe({
            next: () => {
                this.busy.set(false);
                this.editing.set(false);
                this.toast.add({ severity: 'success', summary: success });
                this.res.reload();
            },
            error: (e) => {
                this.busy.set(false);
                this.actionError.set(toProblem(e));
                if (toProblem(e).status === 409) this.res.reload();
            }
        });
    }
}

export default OrderManageDetailPage;
