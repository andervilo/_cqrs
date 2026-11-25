import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { ApiService } from '@/app/core/api.service';
import { ApiProblem, toProblem } from '@/app/core/problem';
import { num } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';

const BATCH = 100;

@Component({
    selector: 'app-rebuild-page',
    standalone: true,
    imports: [FormsModule, ButtonModule, MessageModule, SelectModule, ProblemMessage],
    template: `
        <div class="card">
            <h2 class="text-2xl font-semibold mb-1">Rebuild de projeções</h2>
            <p class="text-muted-color mb-4">Reconstrói as projeções de consulta a partir da base de comandos. Operação pesada: use com cuidado. Se retornar 404, a manutenção está desativada no servidor.</p>

            <div class="grid grid-cols-12 gap-6">
                <div class="col-span-12 lg:col-span-6">
                    <h3 class="text-xl font-semibold mb-2">Clientes / Produtos</h3>
                    <div class="flex items-end gap-3 mb-3">
                        <div class="flex flex-col gap-1"><label for="rb-type">Recurso</label><p-select inputId="rb-type" [options]="resources" [(ngModel)]="resource" [disabled]="running()" styleClass="w-48" /></div>
                        <p-button label="Reconstruir" icon="pi pi-sync" [loading]="running()" (onClick)="confirmRun()" />
                        @if (running()) { <p-button label="Parar" severity="secondary" (onClick)="stop = true" /> }
                    </div>
                    @if (processed() > 0 || done()) {
                        <p-message [severity]="done() ? 'success' : 'info'" styleClass="w-full">
                            Processados: {{ num(processed()) }} @if (done()) { — concluído } @else if (running()) { … em andamento (lotes de {{ batch }}) } @else { — interrompido }
                        </p-message>
                    }
                    <app-problem [error]="error()" />
                </div>
                <div class="col-span-12 lg:col-span-6">
                    <h3 class="text-xl font-semibold mb-2">Dashboard</h3>
                    <p class="text-muted-color">Reconstrução assíncrona de todos os períodos do dashboard (202 Accepted).</p>
                    <p-button label="Reconstruir dashboard" icon="pi pi-chart-bar" [loading]="dashBusy()" (onClick)="confirmDash()" />
                    @if (dashMessage()) { <p-message severity="success" styleClass="w-full mt-3">{{ dashMessage() }}</p-message> }
                    <app-problem [error]="dashError()" />
                </div>
            </div>
        </div>
    `
})
export class RebuildPage {
    private readonly api = inject(ApiService);
    private readonly confirm = inject(ConfirmationService);
    private readonly toast = inject(MessageService);
    protected readonly num = num;
    protected readonly batch = BATCH;
    protected readonly resources = ['CUSTOMER', 'PRODUCT'];
    protected resource: 'CUSTOMER' | 'PRODUCT' = 'CUSTOMER';
    protected stop = false;

    protected readonly running = signal(false);
    protected readonly done = signal(false);
    protected readonly processed = signal(0);
    protected readonly error = signal<ApiProblem | null>(null);
    protected readonly dashBusy = signal(false);
    protected readonly dashMessage = signal('');
    protected readonly dashError = signal<ApiProblem | null>(null);

    protected confirmRun(): void {
        this.confirm.confirm({
            header: 'Reconstruir projeção',
            message: `Reconstruir todos os registros de ${this.resource}?`,
            acceptLabel: 'Reconstruir',
            rejectLabel: 'Cancelar',
            accept: () => this.loop(null)
        });
    }

    // Itera por cursor até hasMore=false; o usuário pode interromper e o erro preserva o progresso.
    private loop(after: string | null): void {
        if (after === null) {
            this.processed.set(0);
            this.done.set(false);
            this.error.set(null);
            this.stop = false;
        }
        this.running.set(true);
        this.api.rebuild(this.resource, after, BATCH).subscribe({
            next: (r) => {
                this.processed.update((n) => n + r.processed);
                if (r.hasMore && r.nextCursor && !this.stop) {
                    this.loop(r.nextCursor);
                } else {
                    this.running.set(false);
                    this.done.set(!r.hasMore);
                    if (!r.hasMore) this.toast.add({ severity: 'success', summary: 'Rebuild concluído' });
                }
            },
            error: (e) => {
                this.running.set(false);
                this.error.set(toProblem(e));
            }
        });
    }

    protected confirmDash(): void {
        this.confirm.confirm({
            header: 'Reconstruir dashboard',
            message: 'Reconstruir o dashboard de todos os períodos?',
            acceptLabel: 'Reconstruir',
            rejectLabel: 'Cancelar',
            accept: () => {
                this.dashBusy.set(true);
                this.dashError.set(null);
                this.dashMessage.set('');
                this.api.rebuildDashboard().subscribe({
                    next: (r) => {
                        this.dashBusy.set(false);
                        this.dashMessage.set(`Solicitação aceita (${r.requestId}) para: ${r.periodCodes.join(', ')}.`);
                    },
                    error: (e) => {
                        this.dashBusy.set(false);
                        this.dashError.set(toProblem(e));
                    }
                });
            }
        });
    }
}

export default RebuildPage;
