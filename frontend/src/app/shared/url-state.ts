import { computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { UUID_RE } from '@/app/core/util';

const clamp = (n: number, min: number, max: number) => Math.min(max, Math.max(min, n));
const int = (raw: string | null, fallback: number) => (raw !== null && /^\d+$/.test(raw) ? Number(raw) : fallback);

/** Estado de listas guardado na URL (deep link): filtros, `page`, `size` e `sort`. */
export function injectUrlState(allowedSorts: readonly string[] = []) {
    const route = inject(ActivatedRoute);
    const router = inject(Router);
    const qp = toSignal(route.queryParamMap, { initialValue: route.snapshot.queryParamMap });

    const get = (key: string): string | null => {
        const value = qp().get(key);
        return value === null || value === '' ? null : value;
    };
    const page = computed(() => int(qp().get('page'), 0));
    const size = computed(() => clamp(int(qp().get('size'), 20), 1, 100));
    const sort = computed(() => {
        const value = qp().get('sort');
        return value && allowedSorts.includes(value) ? value : (allowedSorts[0] ?? null);
    });

    /** Mudar filtro/ordenação zera `page`; mudar só a página usa `keepPage`. */
    const set = (patch: Record<string, string | number | null>, keepPage = false) =>
        router.navigate([], { relativeTo: route, queryParams: { ...patch, ...(keepPage ? {} : { page: null }) }, queryParamsHandling: 'merge', replaceUrl: true });

    return { qp, get, page, size, sort, set };
}

/** Parâmetro de rota reativo, junto com a validação de UUID. */
export function injectRouteId(name: string) {
    const route = inject(ActivatedRoute);
    const params = toSignal(route.paramMap, { initialValue: route.snapshot.paramMap });
    const id = computed(() => params().get(name) ?? '');
    return { id, valid: computed(() => UUID_RE.test(id())) };
}

/** Executa `fn` após `ms` sem novas chamadas (busca com debounce). */
export function debounce<A extends unknown[]>(fn: (...args: A) => void, ms = 300): (...args: A) => void {
    let timer: ReturnType<typeof setTimeout> | undefined;
    return (...args: A) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), ms);
    };
}
