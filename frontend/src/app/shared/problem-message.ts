import { Component, computed, input, output } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { ApiProblem, toProblem } from '@/app/core/problem';

/** Exibe um erro de API com título, detalhe e correlation id. */
@Component({
    selector: 'app-problem',
    standalone: true,
    imports: [MessageModule, ButtonModule],
    template: `
        @if (problem(); as p) {
            <p-message severity="error" styleClass="w-full mb-4" role="alert">
                <div class="flex flex-col gap-1">
                    <strong>{{ headline() }}</strong>
                    @if (p.detail && p.detail !== headline()) {
                        <span>{{ p.detail }}</span>
                    }
                    @if (p.correlationId) {
                        <small class="text-muted-color">Correlation ID: {{ p.correlationId }}</small>
                    }
                </div>
                @if (retryable()) {
                    <p-button label="Tentar novamente" icon="pi pi-refresh" size="small" severity="secondary" styleClass="ml-4" (onClick)="retry.emit()" />
                }
            </p-message>
        }
    `
})
export class ProblemMessage {
    readonly error = input<unknown>(null);
    readonly retryable = input(false);
    readonly retry = output<void>();

    readonly problem = computed<ApiProblem | null>(() => (this.error() ? toProblem(this.error()) : null));
    readonly headline = computed(() => {
        const p = this.problem();
        if (!p) return '';
        switch (p.status) {
            case 403: return 'Você não tem acesso a este recurso.';
            case 404: return 'Não encontrado.';
            case 0: return 'Sem conexão com o servidor.';
            default: return p.title;
        }
    });
}
