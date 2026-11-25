import { Component, computed, input } from '@angular/core';
import { TagModule } from 'primeng/tag';

type Severity = 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast';

const SEVERITY: Record<string, { severity: Severity; label: string }> = {
    CREATED: { severity: 'info', label: 'Criado' },
    CANCELLED: { severity: 'danger', label: 'Cancelado' },
    PENDING: { severity: 'info', label: 'Pendente' },
    SENDED: { severity: 'success', label: 'Enviado' },
    ERRO: { severity: 'danger', label: 'Erro' }
};

@Component({
    selector: 'app-status-tag',
    standalone: true,
    imports: [TagModule],
    template: `<p-tag [value]="view().label" [severity]="view().severity" />`
})
export class StatusTag {
    readonly status = input.required<string>();
    readonly view = computed(() => SEVERITY[this.status()] ?? { severity: 'secondary' as Severity, label: this.status() });
}

@Component({
    selector: 'app-active-tag',
    standalone: true,
    imports: [TagModule],
    template: `<p-tag [value]="active() ? 'Ativo' : 'Inativo'" [severity]="active() ? 'success' : 'secondary'" />`
})
export class ActiveTag {
    readonly active = input.required<boolean>();
}

@Component({
    selector: 'app-empty',
    standalone: true,
    template: `
        <div class="flex flex-col items-center gap-2 py-8 text-muted-color">
            <i class="pi pi-inbox text-4xl"></i>
            <span>{{ text() }}</span>
        </div>
    `
})
export class EmptyState {
    readonly text = input('Nenhum resultado.');
}
