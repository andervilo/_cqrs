import { Component, computed, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { MenuItem } from 'primeng/api';
import { AppMenuitem } from './app.menuitem';
import { AuthService } from '@/app/core/auth.service';

@Component({
    selector: 'app-menu',
    standalone: true,
    imports: [CommonModule, AppMenuitem, RouterModule],
    template: `<ul class="layout-menu">
        @for (item of model(); track item.label) {
            <li app-menuitem [item]="item" [root]="true"></li>
        }
    </ul> `
})
export class AppMenu {
    private readonly auth = inject(AuthService);

    readonly model = computed<MenuItem[]>(() => {
        const link = (label: string, icon: string, path: string, scopes: string[]): MenuItem | null =>
            this.auth.hasAll(scopes) ? { label, icon: `pi pi-fw ${icon}`, routerLink: [path] } : null;
        const group = (label: string, items: (MenuItem | null)[]): MenuItem | null => {
            const visible = items.filter((i): i is MenuItem => !!i);
            return visible.length ? { label, items: visible } : null;
        };
        const write = ['cqrs.write', 'cqrs.command.read'];
        return [
            group('Visão geral', [link('Dashboard', 'pi-chart-bar', '/dashboard', ['cqrs.dashboard.read'])]),
            group('Consulta', [
                link('Pedidos', 'pi-shopping-cart', '/orders', ['cqrs.read']),
                link('Clientes', 'pi-users', '/customers', ['cqrs.read']),
                link('Produtos', 'pi-box', '/products', ['cqrs.read'])
            ]),
            group('Gestão', [
                link('Pedidos', 'pi-pencil', '/manage/orders', write),
                link('Clientes', 'pi-user-edit', '/manage/customers', write),
                link('Produtos', 'pi-tags', '/manage/products', write)
            ]),
            group('Operações', [link('Outbox', 'pi-send', '/ops/outbox', ['cqrs.ops.read']), link('Rebuild', 'pi-sync', '/ops/rebuild', ['cqrs.ops.write'])])
        ].filter((g): g is MenuItem => !!g);
    });
}
