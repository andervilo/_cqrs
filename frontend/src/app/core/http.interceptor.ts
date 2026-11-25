import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';
import { ConfigService } from './config.service';

/** Anexa o Bearer somente às chamadas ao gateway configurado e trata 401. */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
    const config = inject(ConfigService).value();
    if (!req.url.startsWith(config.apiBaseUrl)) return next(req);

    const auth = inject(AuthService);
    const router = inject(Router);
    const token = auth.token();
    const outgoing = config.authMode === 'bearer' && token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

    return next(outgoing).pipe(
        catchError((error: unknown) => {
            if (error instanceof HttpErrorResponse && error.status === 401 && config.authMode === 'bearer') {
                auth.logout();
                void router.navigate(['/auth/login']);
            }
            return throwError(() => error);
        })
    );
};
