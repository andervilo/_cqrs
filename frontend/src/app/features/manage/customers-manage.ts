import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { TableModule, TablePageEvent } from 'primeng/table';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { ApiService } from '@/app/core/api.service';
import { Customer } from '@/app/core/models';
import { ApiProblem, toProblem } from '@/app/core/problem';
import { dateTime } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { ActiveTag, EmptyState } from '@/app/shared/tags';
import { debounce, injectUrlState } from '@/app/shared/url-state';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

@Component({
    selector: 'app-customers-manage',
    standalone: true,
    imports: [FormsModule, TableModule, ButtonModule, DialogModule, InputTextModule, ToggleSwitchModule, MessageModule, ProblemMessage, ActiveTag, EmptyState],
    template: `
        <div class="card">
            <div class="flex items-center justify-between mb-4">
                <h2 class="text-2xl font-semibold m-0">Gestão de clientes</h2>
                <p-button label="Novo cliente" icon="pi pi-plus" (onClick)="open()" />
            </div>
            <div class="flex flex-col gap-1 mb-4"><label for="q">Busca</label><input id="q" pInputText [ngModel]="q()" (ngModelChange)="onSearch($event)" placeholder="Nome ou e-mail" class="w-80" /></div>
            <p-message severity="info" styleClass="mb-4 w-full">A consulta (leitura) é atualizada de forma assíncrona; alterações podem levar alguns instantes para aparecer nas telas de consulta.</p-message>
            <app-problem [error]="res.error()" [retryable]="true" (retry)="res.reload()" />
            <p-table [value]="res.value()?.content ?? []" [lazy]="true" [lazyLoadOnInit]="false" [paginator]="true" [rows]="url.size()" [first]="url.page() * url.size()" [totalRecords]="res.value()?.totalElements ?? 0" [rowsPerPageOptions]="[10, 20, 50, 100]" [loading]="res.isLoading()" (onPage)="onPage($event)">
                <ng-template #header><tr><th>Nome</th><th>E-mail</th><th>Telefone</th><th>Status</th><th>Atualizado</th><th></th></tr></ng-template>
                <ng-template #body let-c>
                    <tr>
                        <td>{{ c.name }}</td><td>{{ c.email }}</td><td>{{ c.phone ?? '—' }}</td>
                        <td><app-active-tag [active]="c.active" /></td><td>{{ dateTime(c.updatedAt) }}</td>
                        <td class="text-right whitespace-nowrap">
                            <p-button icon="pi pi-pencil" [text]="true" ariaLabel="Editar" (onClick)="open(c)" />
                            <p-button icon="pi pi-trash" [text]="true" severity="danger" ariaLabel="Excluir" (onClick)="confirmDelete(c)" />
                        </td>
                    </tr>
                </ng-template>
                <ng-template #emptymessage><tr><td colspan="6"><app-empty text="Nenhum cliente." /></td></tr></ng-template>
            </p-table>
        </div>

        <p-dialog [header]="editing() ? 'Editar cliente' : 'Novo cliente'" [(visible)]="visible" [modal]="true" [style]="{ width: '32rem' }" [closable]="!saving()">
            <div class="flex flex-col gap-4">
                @if (formError(); as e) { <app-problem [error]="e" /> }
                <div class="flex flex-col gap-1"><label for="f-name">Nome *</label><input id="f-name" pInputText maxlength="160" [(ngModel)]="name" /><small class="text-red-500">{{ errors().name }}</small></div>
                <div class="flex flex-col gap-1"><label for="f-email">E-mail *</label><input id="f-email" pInputText type="email" maxlength="254" [(ngModel)]="email" /><small class="text-red-500">{{ errors().email }}</small></div>
                <div class="flex flex-col gap-1"><label for="f-phone">Telefone</label><input id="f-phone" pInputText maxlength="24" [(ngModel)]="phone" /></div>
                @if (editing()) { <div class="flex items-center gap-2"><p-toggleswitch inputId="f-active" [(ngModel)]="active" /><label for="f-active">Ativo</label></div> }
            </div>
            <ng-template #footer>
                <p-button label="Cancelar" severity="secondary" [text]="true" [disabled]="saving()" (onClick)="visible = false" />
                <p-button label="Salvar" icon="pi pi-check" [loading]="saving()" [disabled]="!valid()" (onClick)="save()" />
            </ng-template>
        </p-dialog>
    `
})
export class CustomersManagePage {
    private readonly api = inject(ApiService);
    private readonly toast = inject(MessageService);
    private readonly confirm = inject(ConfirmationService);
    protected readonly url = injectUrlState();
    protected readonly dateTime = dateTime;
    protected readonly q = computed(() => this.url.get('q') ?? '');

    protected readonly res = rxResource({
        params: () => ({ q: this.q().trim() || null, page: this.url.page(), size: this.url.size() }),
        stream: ({ params }) => this.api.customers(params)
    });
    protected readonly onSearch = debounce((v: string) => this.url.set({ q: v.trim() || null }));

    protected visible = false;
    protected readonly editing = signal<Customer | null>(null);
    protected readonly saving = signal(false);
    protected readonly formError = signal<ApiProblem | null>(null);
    protected name = '';
    protected email = '';
    protected phone = '';
    protected active = true;

    protected errors() {
        return {
            name: !this.name.trim() ? 'Informe o nome.' : this.name.length > 160 ? 'Máximo de 160 caracteres.' : '',
            email: !this.email.trim() ? 'Informe o e-mail.' : !EMAIL_RE.test(this.email.trim()) ? 'E-mail inválido.' : ''
        };
    }
    protected valid(): boolean {
        const e = this.errors();
        return !e.name && !e.email;
    }

    protected open(c?: Customer): void {
        this.editing.set(c ?? null);
        this.name = c?.name ?? '';
        this.email = c?.email ?? '';
        this.phone = c?.phone ?? '';
        this.active = c?.active ?? true;
        this.formError.set(null);
        this.visible = true;
    }

    protected save(): void {
        if (!this.valid() || this.saving()) return;
        const body = { name: this.name.trim(), email: this.email.trim(), phone: this.phone.trim() || null };
        const cur = this.editing();
        const call = cur ? this.api.updateCustomer(cur.id, { ...body, active: this.active }) : this.api.createCustomer(body);
        this.saving.set(true);
        this.formError.set(null);
        call.subscribe({
            next: () => {
                this.saving.set(false);
                this.visible = false;
                this.toast.add({ severity: 'success', summary: cur ? 'Cliente atualizado' : 'Cliente criado' });
                this.res.reload();
            },
            error: (e) => {
                this.saving.set(false);
                this.formError.set(toProblem(e));
            }
        });
    }

    protected confirmDelete(c: Customer): void {
        this.confirm.confirm({
            header: 'Excluir cliente',
            message: `Excluir "${c.name}"? Esta ação não pode ser desfeita.`,
            acceptLabel: 'Excluir',
            rejectLabel: 'Cancelar',
            acceptButtonProps: { severity: 'danger' },
            accept: () =>
                this.api.deleteCustomer(c.id).subscribe({
                    next: () => {
                        this.toast.add({ severity: 'success', summary: 'Cliente excluído' });
                        this.res.reload();
                    },
                    error: (e) => {
                        const p = toProblem(e);
                        this.toast.add({ severity: 'error', summary: p.status === 409 ? 'Não é possível excluir' : 'Falha ao excluir', detail: p.detail ?? p.title, life: 6000 });
                    }
                })
        });
    }

    protected onPage(e: TablePageEvent): void {
        this.url.set({ page: e.first / e.rows, size: e.rows }, true);
    }
}

export default CustomersManagePage;
