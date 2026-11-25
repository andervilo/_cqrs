import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, inject, provideAppInitializer, provideZonelessChangeDetection } from '@angular/core';
import { provideRouter, withEnabledBlockingInitialNavigation, withInMemoryScrolling } from '@angular/router';
import Aura from '@primeuix/themes/aura';
import { ConfirmationService, MessageService } from 'primeng/api';
import { providePrimeNG } from 'primeng/config';
import { appRoutes } from './app.routes';
import { ConfigService } from '@/app/core/config.service';
import { apiInterceptor } from '@/app/core/http.interceptor';

const PT_BR = {
    startsWith: 'Começa com',
    contains: 'Contém',
    notContains: 'Não contém',
    endsWith: 'Termina com',
    equals: 'Igual',
    notEquals: 'Diferente',
    noFilter: 'Sem filtro',
    apply: 'Aplicar',
    clear: 'Limpar',
    accept: 'Sim',
    reject: 'Não',
    choose: 'Escolher',
    today: 'Hoje',
    weekHeader: 'Sem',
    firstDayOfWeek: 0,
    dayNames: ['domingo', 'segunda', 'terça', 'quarta', 'quinta', 'sexta', 'sábado'],
    dayNamesShort: ['dom', 'seg', 'ter', 'qua', 'qui', 'sex', 'sáb'],
    dayNamesMin: ['D', 'S', 'T', 'Q', 'Q', 'S', 'S'],
    monthNames: ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'],
    monthNamesShort: ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'],
    dateFormat: 'dd/mm/yy',
    emptyMessage: 'Nenhum resultado',
    emptyFilterMessage: 'Nenhum resultado'
};

export const appConfig: ApplicationConfig = {
    providers: [
        provideRouter(appRoutes, withInMemoryScrolling({ anchorScrolling: 'enabled', scrollPositionRestoration: 'enabled' }), withEnabledBlockingInitialNavigation()),
        provideHttpClient(withFetch(), withInterceptors([apiInterceptor])),
        provideZonelessChangeDetection(),
        provideAppInitializer(() => inject(ConfigService).load()),
        providePrimeNG({ theme: { preset: Aura, options: { darkModeSelector: '.app-dark' } }, translation: PT_BR }),
        MessageService,
        ConfirmationService
    ]
};
