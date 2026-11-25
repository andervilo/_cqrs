import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { TagModule } from 'primeng/tag';
import { ApiService } from '@/app/core/api.service';
import { ApiProblem } from '@/app/core/problem';
import { money } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { injectRouteId } from '@/app/shared/url-state';
import { of } from 'rxjs';

/** Só exibe imagens http(s); evita esquemas como `javascript:`. */
const safeUrl = (u: string) => /^https?:\/\//i.test(u);

@Component({
    selector: 'app-product-detail',
    standalone: true,
    imports: [RouterLink, ButtonModule, MessageModule, TagModule, ProblemMessage],
    template: `
        <div class="card">
            <div class="flex items-center justify-between mb-4">
                <h2 class="text-2xl font-semibold m-0">Produto</h2>
                <p-button label="Atualizar" icon="pi pi-refresh" severity="secondary" [loading]="res.isLoading()" (onClick)="res.reload()" />
            </div>
            @if (!valid()) {
                <p-message severity="warn">Identificador de produto inválido.</p-message>
            } @else {
                <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
                @if (res.value(); as p) {
                    <div class="flex flex-wrap gap-6">
                        @if (images().length) {
                            <div class="flex gap-2 flex-wrap">@for (u of images(); track u) { <img [src]="u" [alt]="p.name" class="w-48 h-48 object-cover rounded" referrerpolicy="no-referrer" /> }</div>
                        }
                        <div class="flex-1 min-w-64">
                            <div class="text-xl font-semibold">{{ p.name }}</div>
                            <div class="text-2xl my-2">{{ money(p.price, p.currency) }}</div>
                            <p-tag [value]="p.available ? 'Disponível' : 'Indisponível'" [severity]="p.available ? 'success' : 'secondary'" />
                            <p class="mt-4 whitespace-pre-line">{{ p.description }}</p>
                            @if (attrs().length) {
                                <dl class="grid grid-cols-2 gap-1 mt-4">
                                    @for (a of attrs(); track a[0]) { <dt class="text-muted-color">{{ a[0] }}</dt><dd class="m-0">{{ a[1] }}</dd> }
                                </dl>
                            }
                            <small class="text-muted-color block mt-4">Preço atual do catálogo; pedidos antigos mantêm o preço da época.</small>
                        </div>
                    </div>
                }
                <p-button label="Voltar ao catálogo" icon="pi pi-arrow-left" [text]="true" routerLink="/products" styleClass="mt-4" />
            }
        </div>
    `
})
export class ProductDetailPage {
    private readonly api = inject(ApiService);
    private readonly route = injectRouteId('productId');
    protected readonly valid = this.route.valid;
    protected readonly money = money;
    protected readonly res = rxResource({
        params: () => (this.route.valid() ? this.route.id() : undefined),
        stream: ({ params }) => (params ? this.api.productDetail(params) : of(undefined))
    });
    protected readonly images = computed(() => (this.res.value()?.imageUrls ?? []).filter(safeUrl));
    protected readonly attrs = computed(() => Object.entries(this.res.value()?.attributes ?? {}));
    protected readonly notFound = computed(() => (this.res.error() as ApiProblem | undefined)?.status === 404);
}

export default ProductDetailPage;
