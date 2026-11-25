import { Injectable, signal } from '@angular/core';

export interface AppConfig {
    apiBaseUrl: string;
    /** `none` só deve ser usado com o gateway em perfil `local`. */
    authMode: 'none' | 'bearer';
    localScopes: string[];
}

const DEFAULTS: AppConfig = { apiBaseUrl: 'http://localhost:8082', authMode: 'bearer', localScopes: [] };

@Injectable({ providedIn: 'root' })
export class ConfigService {
    readonly value = signal<AppConfig>(DEFAULTS);

    async load(): Promise<void> {
        try {
            const response = await fetch('config.json', { cache: 'no-store' });
            if (response.ok) {
                const loaded = (await response.json()) as Partial<AppConfig>;
                this.value.set({ ...DEFAULTS, ...loaded, apiBaseUrl: (loaded.apiBaseUrl ?? DEFAULTS.apiBaseUrl).replace(/\/+$/, '') });
            }
        } catch {
            this.value.set(DEFAULTS);
        }
    }
}
