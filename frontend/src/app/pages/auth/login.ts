import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { TextareaModule } from 'primeng/textarea';
import { MessageModule } from 'primeng/message';
import { AuthService } from '@/app/core/auth.service';
import { AppFloatingConfigurator } from '../../layout/component/app.floatingconfigurator';

@Component({
    selector: 'app-login',
    standalone: true,
    imports: [FormsModule, ButtonModule, TextareaModule, MessageModule, AppFloatingConfigurator],
    template: `
        <app-floating-configurator />
        <div class="bg-surface-50 dark:bg-surface-950 flex items-center justify-center min-h-screen p-4">
            <div class="card w-full max-w-xl">
                <h2 class="text-2xl font-semibold mb-2">Acesso</h2>
                <p class="text-muted-color mb-4">Não há endpoint de login: cole um token JWT (Bearer) emitido pelo seu provedor de identidade. Ele fica apenas na sessão do navegador.</p>
                <label for="token" class="block mb-1">Token de acesso</label>
                <textarea id="token" pTextarea rows="6" class="w-full font-mono" [(ngModel)]="token" autocomplete="off"></textarea>
                @if (error()) {
                    <p-message severity="error" styleClass="mt-3">{{ error() }}</p-message>
                }
                <p-button label="Entrar" icon="pi pi-sign-in" styleClass="mt-4" (onClick)="enter()" [disabled]="!token.trim()" />
            </div>
        </div>
    `
})
export class Login {
    private readonly auth = inject(AuthService);
    private readonly router = inject(Router);
    protected token = '';
    protected readonly error = signal('');

    protected enter(): void {
        this.auth.setToken(this.token);
        if (!this.auth.authenticated()) {
            this.auth.logout();
            this.error.set('Token inválido ou expirado.');
            return;
        }
        this.router.navigateByUrl('/');
    }
}
