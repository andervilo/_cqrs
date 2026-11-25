import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DrawerModule } from 'primeng/drawer';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TableModule, TablePageEvent } from 'primeng/table';
import { ApiService } from '@/app/core/api.service';
import { EVENT_TYPES, OutboxEntry } from '@/app/core/models';
import { ApiProblem, toProblem } from '@/app/core/problem';
import { UUID_RE, dateTime, shortId } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { EmptyState, StatusTag } from '@/app/shared/tags';
import { injectUrlState } from '@/app/shared/url-state';
import { AuthService } from '@/app/core/auth.service';

const STATUS_OPTS = [
    { label: 'Todos', value: null },
    { label: 'Pendente', value: 'PENDING' },
    { label: 'Enviado', value: 'SENDED' },
    { label: 'Erro', value: 'ERRO' }
];

@Component({
    selector: 'app-outbox-page',
    standalone: true,
    imports: [FormsModule, TableModule, ButtonModule, DrawerModule, InputTextModule, SelectModule, ProblemMessage, StatusTag, EmptyState],
    template: `
        <div class="card">
            <h2 class="text-2xl font-semibold mb-1">Outbox de eventos</h2>
            <p class="text-muted-color mb-4">Fila de publicação para as projeções. Se retornar 404, a manutenção está desativada no servidor.</p>
            <div class="flex flex-wrap gap-3 items-end mb-4">
                <div class="flex flex-col gap-1"><label for="ob-status">Status</label><p-select inputId="ob-status" [options]="statuses" optionLabel="label" optionValue="value" [ngModel]="status()" (ngModelChange)="url.set({ status: $event })" styleClass="w-44" /></div>
                <div class="flex flex-col gap-1"><label for="ob-type">Tipo de evento</label><p-select inputId="ob-type" [options]="types" [showClear]="true" placeholder="Todos" [ngModel]="eventType()" (ngModelChange)="url.set({ eventType: $event })" styleClass="w-64" /></div>
                <div class="flex flex-col gap-1"><label for="ob-order">Pedido (ID)</label><input id="ob-order" pInputText class="w-80" placeholder="UUID" [ngModel]="orderInput" (ngModelChange)="onOrder($event)" [class.ng-invalid]="invalid" [class.ng-dirty]="invalid" /></div>
                <p-button icon="pi pi-refresh" severity="secondary" ariaLabel="Atualizar" [loading]="res.isLoading()" (onClick)="res.reload()" />
            </div>
            <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
            <p-table [value]="res.value()?.content ?? []" [lazy]="true" [lazyLoadOnInit]="false" [paginator]="true" [rows]="url.size()" [first]="url.page() * url.size()" [totalRecords]="res.value()?.totalElements ?? 0" [rowsPerPageOptions]="[10, 20, 50, 100]" [loading]="res.isLoading()" (onPage)="onPage($event)">
                <ng-template #header><tr><th>Criado</th><th>Tipo</th><th>Operação</th><th>Status</th><th class="text-right">Tentativas</th><th>Próxima</th><th></th></tr></ng-template>
                <ng-template #body let-e>
                    <tr>
                        <td>{{ dateTime(e.dataCriacao) }}</td><td>{{ e.eventType }}</td><td>{{ e.operation }}</td>
                        <td><app-status-tag [status]="e.status" /></td><td class="text-right">{{ e.attemptCount }}</td><td>{{ e.nextAttemptAt ? dateTime(e.nextAttemptAt) : '—' }}</td>
                        <td class="text-right"><p-button label="Detalhes" [text]="true" size="small" (onClick)="show(e)" /></td>
                    </tr>
                </ng-template>
                <ng-template #emptymessage><tr><td colspan="7"><app-empty text="Nenhum evento." /></td></tr></ng-template>
            </p-table>
        </div>

        <p-drawer [(visible)]="drawer" position="right" header="Evento do outbox" [style]="{ width: '40rem' }">
            @if (selected(); as e) {
                <app-problem [error]="actionError()" />
                <dl class="grid grid-cols-3 gap-2">
                    <dt class="text-muted-color">ID</dt><dd class="col-span-2 m-0 break-all">{{ e.id }}</dd>
                    <dt class="text-muted-color">Pedido</dt><dd class="col-span-2 m-0 break-all">{{ e.orderId ?? '—' }}</dd>
                    <dt class="text-muted-color">Status</dt><dd class="col-span-2 m-0"><app-status-tag [status]="e.status" /></dd>
                    <dt class="text-muted-color">Tipo / Operação</dt><dd class="col-span-2 m-0">{{ e.eventType }} / {{ e.operation }}</dd>
                    <dt class="text-muted-color">Chave</dt><dd class="col-span-2 m-0 break-all">{{ e.projectionKey }} (v{{ e.projectionVersion }}, schema {{ e.schemaVersion }})</dd>
                    <dt class="text-muted-color">Tentativas</dt><dd class="col-span-2 m-0">{{ e.attemptCount }}</dd>
                    <dt class="text-muted-color">Enviado em</dt><dd class="col-span-2 m-0">{{ e.dataEnvio ? dateTime(e.dataEnvio) : '—' }}</dd>
                </dl>
                @if (e.lastError) { <div class="font-semibold mt-3">Último erro</div><pre class="whitespace-pre-wrap text-sm">{{ e.lastError }}</pre> }
                <div class="font-semibold mt-3">Payload</div>
                <pre class="whitespace-pre-wrap text-sm overflow-auto max-h-96">{{ pretty(e.payload) }}</pre>
                @if (canRetry() && e.status === 'ERRO') {
                    <p-button label="Reenviar" icon="pi pi-replay" [loading]="retrying()" (onClick)="retry(e)" />
                }
            }
        </p-drawer>
    `
})
export class OutboxPage {
    private readonly api = inject(ApiService);
    private readonly toast = inject(MessageService);
    private readonly auth = inject(AuthService);
    protected readonly url = injectUrlState();
    protected readonly statuses = STATUS_OPTS;
    protected readonly types = [...EVENT_TYPES];
    protected readonly dateTime = dateTime;
    protected readonly shortId = shortId;
    protected readonly canRetry = computed(() => this.auth.hasScope('cqrs.ops.write'));

    protected readonly status = computed(() => {
        const v = this.url.get('status');
        return v === 'PENDING' || v === 'SENDED' || v === 'ERRO' ? v : null;
    });
    protected readonly eventType = computed(() => {
        const v = this.url.get('eventType');
        return v && (EVENT_TYPES as readonly string[]).includes(v) ? v : null;
    });
    private readonly orderId = computed(() => {
        const v = this.url.get('orderId');
        return v && UUID_RE.test(v) ? v : null;
    });
    protected orderInput = this.url.get('orderId') ?? '';
    protected invalid = false;

    protected readonly res = rxResource({
        params: () => ({ status: this.status(), eventType: this.eventType(), orderId: this.orderId(), page: this.url.page(), size: this.url.size() }),
        stream: ({ params }) => this.api.outbox(params)
    });

    protected drawer = false;
    protected readonly selected = signal<OutboxEntry | null>(null);
    protected readonly retrying = signal(false);
    protected readonly actionError = signal<ApiProblem | null>(null);

    protected show(e: OutboxEntry): void {
        this.selected.set(e);
        this.actionError.set(null);
        this.drawer = true;
    }

    protected pretty(p: unknown): string {
        try {
            return JSON.stringify(typeof p === 'string' ? JSON.parse(p) : p, null, 2);
        } catch {
            return String(p);
        }
    }

    protected retry(e: OutboxEntry): void {
        this.retrying.set(true);
        this.actionError.set(null);
        this.api.retryOutbox(e.id).subscribe({
            next: () => {
                this.retrying.set(false);
                this.drawer = false;
                this.toast.add({ severity: 'success', summary: 'Reenvio solicitado' });
                this.res.reload();
            },
            error: (err) => {
                this.retrying.set(false);
                this.actionError.set(toProblem(err));
            }
        });
    }

    protected onOrder(v: string): void {
        this.orderInput = v;
        const t = v.trim();
        this.invalid = t !== '' && !UUID_RE.test(t);
        if (!this.invalid) this.url.set({ orderId: t || null });
    }
    protected onPage(e: TablePageEvent): void {
        this.url.set({ page: e.first / e.rows, size: e.rows }, true);
    }
}

export default OutboxPage;
