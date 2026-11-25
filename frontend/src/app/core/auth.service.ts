import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { ConfigService } from './config.service';

const STORAGE_KEY = 'cqrs.access-token';

interface JwtPayload {
    exp?: number;
    scope?: string;
    scp?: string | string[];
}

function decode(token: string): JwtPayload | null {
    try {
        const part = token.split('.')[1];
        const json = atob(part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '='));
        return JSON.parse(json) as JwtPayload;
    } catch {
        return null;
    }
}

@Injectable({ providedIn: 'root' })
export class AuthService {
    private readonly config = inject(ConfigService);
    readonly token = signal<string | null>(sessionStorage.getItem(STORAGE_KEY));

    readonly scopes = computed<ReadonlySet<string>>(() => {
        const cfg = this.config.value();
        if (cfg.authMode === 'none') return new Set(cfg.localScopes);
        const payload = this.token() ? decode(this.token()!) : null;
        if (!payload) return new Set();
        const raw = payload.scope ?? payload.scp ?? [];
        return new Set(Array.isArray(raw) ? raw : raw.split(' ').filter(Boolean));
    });

    readonly authenticated = computed(() => {
        if (this.config.value().authMode === 'none') return true;
        const t = this.token();
        const payload = t ? decode(t) : null;
        return !!payload && (payload.exp === undefined || payload.exp * 1000 > Date.now());
    });

    hasScope(scope: string): boolean {
        return this.authenticated() && this.scopes().has(scope);
    }

    hasAll(scopes: readonly string[]): boolean {
        return scopes.every((s) => this.hasScope(s));
    }

    setToken(token: string): void {
        const clean = token.trim().replace(/^Bearer\s+/i, '');
        sessionStorage.setItem(STORAGE_KEY, clean);
        this.token.set(clean);
    }

    logout(): void {
        sessionStorage.removeItem(STORAGE_KEY);
        this.token.set(null);
    }
}

/** Bloqueia a rota sem login ou sem todos os scopes pedidos. */
export function scopeGate(...required: string[]): CanActivateFn {
    return () => {
        const auth = inject(AuthService);
        const router = inject(Router);
        if (!auth.authenticated()) return router.createUrlTree(['/auth/login']);
        return auth.hasAll(required) ? true : router.createUrlTree(['/auth/access']);
    };
}
