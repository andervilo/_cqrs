import { Routes } from '@angular/router';
import { AppLayout } from './app/layout/component/app.layout';
import { Notfound } from './app/pages/notfound/notfound';
import { scopeGate } from '@/app/core/auth.service';

const READ = scopeGate('cqrs.read');
const WRITE = scopeGate('cqrs.write', 'cqrs.command.read');

export const appRoutes: Routes = [
    {
        path: '',
        component: AppLayout,
        children: [
            { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
            { path: 'dashboard', canActivate: [scopeGate('cqrs.dashboard.read')], loadComponent: () => import('./app/features/dashboard/dashboard-page') },
            { path: 'orders', canActivate: [READ], loadComponent: () => import('./app/features/orders/order-list') },
            { path: 'orders/:orderId', canActivate: [READ], loadComponent: () => import('./app/features/orders/order-detail') },
            { path: 'products', canActivate: [READ], loadComponent: () => import('./app/features/products/product-catalog') },
            { path: 'products/:productId', canActivate: [READ], loadComponent: () => import('./app/features/products/product-detail') },
            { path: 'customers', canActivate: [READ], loadComponent: () => import('./app/features/customers/customer-list') },
            { path: 'customers/:customerId', canActivate: [READ], loadComponent: () => import('./app/features/customers/customer-detail') },
            { path: 'manage/customers', canActivate: [WRITE], loadComponent: () => import('./app/features/manage/customers-manage') },
            { path: 'manage/products', canActivate: [WRITE], loadComponent: () => import('./app/features/manage/products-manage') },
            { path: 'manage/orders', canActivate: [WRITE], loadComponent: () => import('./app/features/manage/orders-manage') },
            { path: 'manage/orders/new', canActivate: [WRITE], loadComponent: () => import('./app/features/manage/order-new') },
            { path: 'manage/orders/:orderId', canActivate: [WRITE], loadComponent: () => import('./app/features/manage/order-manage-detail') },
            { path: 'ops/outbox', canActivate: [scopeGate('cqrs.ops.read')], loadComponent: () => import('./app/features/ops/outbox-page') },
            { path: 'ops/rebuild', canActivate: [scopeGate('cqrs.ops.write')], loadComponent: () => import('./app/features/ops/rebuild-page') }
        ]
    },
    { path: 'notfound', component: Notfound },
    { path: 'auth', loadChildren: () => import('./app/pages/auth/auth.routes') },
    { path: '**', redirectTo: '/notfound' }
];
