import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { TableModule } from 'primeng/table';
import { of } from 'rxjs';
import { ApiService } from '@/app/core/api.service';
import { AuthService } from '@/app/core/auth.service';
import { ApiProblem } from '@/app/core/problem';
import { dateTime, money } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { StatusTag } from '@/app/shared/tags';
import { injectRouteId } from '@/app/shared/url-state';

@Component({
    selector: 'app-order-detail',
    standalone: true,
    imports: [RouterLink, TableModule, ButtonModule, MessageModule, ProblemMessage, StatusTag],
    template: `
        <div class="card">
            <div class="flex items-center justify-between mb-4">
                <h2 class="text-2xl font-semibold m-0">Pedido</h2>
                <div class="flex gap-2">
                    <p-button label="Atualizar" icon="pi pi-refresh" severity="secondary" [loading]="res.isLoading()" (onClick)="res.reload()" />
                    @if (auth.hasScope('cqrs.write') && auth.hasScope('cqrs.command.read')) {
                        <p-button label="Gerenciar" icon="pi pi-pencil" [routerLink]="['/manage/orders', orderId()]" />
                    }
                </div>
            </div>

            @if (!valid()) {
                <p-message severity="warn">Identificador de pedido inválido.</p-message>
            } @else {
                <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
                @if (notFound()) {
                    <p class="text-muted-color">O pedido pode ainda não ter sido projetado na consulta. Tente atualizar em instantes.</p>
                }
                @if (res.value(); as o) {
                    <div class="grid grid-cols-12 gap-4 mb-6">
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">ID</div><code>{{ o.orderId }}</code></div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Data</div>{{ dateTime(o.createdAt) }}</div>
                        <div class="col-span-12 md:col-span-4"><div class="text-muted-color">Status</div><app-status-tag [status]="o.status" /></div>
                        <div class="col-span-12 md:col-span-4">
                            <div class="text-muted-color">Cliente</div>
                            <a [routerLink]="['/customers', o.customer.customerId]" class="text-primary">{{ o.customer.name }}</a>
                        </div>
                    </div>

                    <p-table [value]="o.items" [tableStyle]="{ 'min-width': '40rem' }">
                        <ng-template #header>
                            <tr><th>Produto</th><th class="text-right">Qtd.</th><th class="text-right">Preço na compra</th><th class="text-right">Subtotal</th></tr>
                        </ng-template>
                        <ng-template #body let-i>
                            <tr>
                                <td>@if (auth.hasScope('cqrs.read')) { <a [routerLink]="['/products', i.productId]" class="text-primary">{{ i.productName }}</a> } @else { {{ i.productName }} }</td>
                                <td class="text-right">{{ i.quantity }}</td>
                                <td class="text-right">{{ money(i.unitPrice, o.currency) }}</td>
                                <td class="text-right">{{ money(i.subtotal, o.currency) }}</td>
                            </tr>
                        </ng-template>
                    </p-table>
                    <small class="text-muted-color block mt-2">Nome e preço são o registro histórico da compra, não os valores atuais do catálogo.</small>

                    <div class="flex flex-col items-end gap-1 mt-4">
                        <div>Subtotal: {{ money(o.subtotalAmount, o.currency) }}</div>
                        <div>Desconto: {{ money(o.discountAmount, o.currency) }}</div>
                        <div>Frete: {{ money(o.shippingAmount, o.currency) }}</div>
                        <div class="text-xl font-semibold">Total: {{ money(o.totalAmount, o.currency) }}</div>
                    </div>
                }
            }
        </div>
    `
})
export class OrderDetailPage {
    protected readonly api = inject(ApiService);
    protected readonly auth = inject(AuthService);
    protected readonly dateTime = dateTime;
    protected readonly money = money;
    private readonly route = injectRouteId('orderId');
    protected readonly orderId = this.route.id;
    protected readonly valid = this.route.valid;
    protected readonly res = rxResource({
        params: () => (this.valid() ? this.orderId() : undefined),
        stream: ({ params }) => (params ? this.api.order(params) : of(undefined))
    });
    protected readonly notFound = computed(() => (this.res.error() as ApiProblem | undefined)?.status === 404);
}

export default OrderDetailPage;
