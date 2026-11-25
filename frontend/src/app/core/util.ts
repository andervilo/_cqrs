import { DrillDown, Metric } from './models';

const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
export const UUID_RE = new RegExp(`^${UUID}$`);

export const DRILL_DOWN_ALLOWLIST: { pattern: RegExp; route: (m: RegExpMatchArray) => string[]; scopes: string[]; allowedParams: string[] }[] = [
    { pattern: /^\/api\/queries\/orders$/, route: () => ['/orders'], scopes: ['cqrs.read'], allowedParams: ['status', 'from', 'to', 'customerId'] },
    { pattern: new RegExp(`^/api/queries/orders/(${UUID})$`), route: (m) => ['/orders', m[1]], scopes: ['cqrs.read'], allowedParams: [] },
    { pattern: new RegExp(`^/api/queries/products/(${UUID})$`), route: (m) => ['/products', m[1]], scopes: ['cqrs.read'], allowedParams: [] },
    { pattern: new RegExp(`^/api/queries/customers/(${UUID})$`), route: (m) => ['/customers', m[1]], scopes: ['cqrs.read'], allowedParams: [] }
];

export interface ResolvedLink {
    commands: string[];
    queryParams: Record<string, string>;
}

/** Converte um drill-down do payload em rota interna; `null` => renderizar texto, nunca link. */
export function resolveDrillDown(dd: DrillDown | null | undefined, hasScope: (s: string) => boolean): ResolvedLink | null {
    if (!dd || dd.method !== 'GET' || typeof dd.path !== 'string') return null;
    if (!dd.path.startsWith('/api/') || dd.path.includes('//') || dd.path.includes('?') || dd.path.includes('..')) return null;
    for (const rule of DRILL_DOWN_ALLOWLIST) {
        const match = dd.path.match(rule.pattern);
        if (!match) continue;
        if (!rule.scopes.includes(dd.requiredScope) || !hasScope(dd.requiredScope)) return null;
        const query = dd.query ?? {};
        if (Object.keys(query).some((k) => !rule.allowedParams.includes(k))) return null;
        return { commands: rule.route(match), queryParams: { ...query } };
    }
    return null;
}

// ---------- Formatação (pt-BR, America/Sao_Paulo) ----------
const TZ = 'America/Sao_Paulo';

export function money(value: number | null | undefined, currency = 'BRL'): string {
    if (value === null || value === undefined) return '—';
    return new Intl.NumberFormat('pt-BR', { style: 'currency', currency }).format(value);
}
export function num(value: number | null | undefined, digits = 0): string {
    if (value === null || value === undefined) return '—';
    return new Intl.NumberFormat('pt-BR', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value);
}
export function percent(value: number | null | undefined): string {
    return value === null || value === undefined ? '—' : `${num(value, 2)}%`;
}
export function dateTime(iso: string | null | undefined): string {
    if (!iso) return '—';
    return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short', timeZone: TZ }).format(new Date(iso));
}
export function dateOnly(iso: string | null | undefined): string {
    if (!iso) return '—';
    return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeZone: TZ }).format(new Date(iso));
}
export function shortId(id: string): string {
    return id.slice(0, 8);
}

/** Formata um valor de métrica conforme a unidade; `null` nunca vira 0. */
export function metricValue(m: Metric, currency = 'BRL'): string {
    if (m.value === null) return '—';
    switch (m.unit) {
        case 'BRL': return money(m.value, currency);
        case 'PERCENT': return percent(m.value);
        case 'RATIO': return num(m.value, 2);
        default: return num(m.value);
    }
}
