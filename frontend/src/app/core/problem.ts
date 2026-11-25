import { HttpErrorResponse } from '@angular/common/http';
import { MonoTypeOperatorFunction, catchError, throwError } from 'rxjs';

/** Erro de API normalizado (Problem Details + cabeçalhos úteis). */
export class ApiProblem extends Error {
    constructor(
        readonly status: number,
        readonly title: string,
        readonly detail: string | null,
        readonly code: string | null,
        readonly correlationId: string | null,
        readonly retryAfter: number | null
    ) {
        super(detail ?? title);
    }

    get isNetwork(): boolean {
        return this.status === 0 || this.status === 502 || this.status === 503 || this.status === 504;
    }
}

export function toProblem(error: unknown): ApiProblem {
    if (error instanceof ApiProblem) return error;
    if (error instanceof HttpErrorResponse) {
        const body = (error.error && typeof error.error === 'object' ? error.error : {}) as Record<string, unknown>;
        const retry = Number(error.headers?.get('Retry-After'));
        return new ApiProblem(
            error.status,
            typeof body['title'] === 'string' ? body['title'] : error.status === 0 ? 'Sem conexão com o servidor' : 'Falha na requisição',
            typeof body['detail'] === 'string' ? body['detail'] : null,
            typeof body['code'] === 'string' ? body['code'] : null,
            error.headers?.get('X-Correlation-Id') ?? null,
            Number.isFinite(retry) && retry > 0 ? retry : null
        );
    }
    return new ApiProblem(0, 'Erro inesperado', null, null, null, null);
}

export function mapProblem<T>(): MonoTypeOperatorFunction<T> {
    return catchError((e) => throwError(() => toProblem(e)));
}
