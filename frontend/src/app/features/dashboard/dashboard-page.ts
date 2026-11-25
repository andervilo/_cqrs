import { NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { ChartModule } from 'primeng/chart';
import { MessageModule } from 'primeng/message';
import { SelectButtonModule } from 'primeng/selectbutton';
import { SkeletonModule } from 'primeng/skeleton';
import { TableModule } from 'primeng/table';
import { TabsModule } from 'primeng/tabs';
import { TagModule } from 'primeng/tag';
import { ApiService } from '@/app/core/api.service';
import { AuthService } from '@/app/core/auth.service';
import { DEFAULT_PERIOD, DashboardProduct, DashboardResponse, DrillDown, Metric, PERIOD_CODES, PeriodCode } from '@/app/core/models';
import { ApiProblem, toProblem } from '@/app/core/problem';
import { dateOnly, dateTime, metricValue, money, num, percent, resolveDrillDown } from '@/app/core/util';
import { ProblemMessage } from '@/app/shared/problem-message';
import { EmptyState } from '@/app/shared/tags';
import { injectUrlState } from '@/app/shared/url-state';

const PERIOD_LABELS: Record<PeriodCode, string> = {
    TODAY: 'Hoje',
    LAST_7_DAYS: '7 dias',
    LAST_30_DAYS: '30 dias',
    MONTH_TO_DATE: 'Mês atual',
    PREVIOUS_MONTH: 'Mês anterior'
};
const METRIC_LABELS: Record<string, string> = {
    requestedValue: 'Valor solicitado',
    retainedOrderValue: 'Valor retido',
    cancelledOrderValue: 'Valor cancelado',
    orderCount: 'Pedidos',
    activeOrderCount: 'Pedidos ativos',
    cancelledOrderCount: 'Pedidos cancelados',
    cancellationRate: 'Taxa de cancelamento',
    cancellationValueRate: 'Taxa de cancelamento (valor)',
    averageActiveOrderValue: 'Ticket médio',
    activeBuyerCount: 'Compradores ativos',
    newActiveBuyerCount: 'Compradores novos',
    returningActiveBuyerCount: 'Compradores recorrentes',
    returningBuyerShare: 'Participação de recorrentes',
    ordersPerActiveBuyer: 'Pedidos por comprador',
    top5CustomerValueShare: 'Top 5 clientes (% do valor)',
    retainedUnits: 'Unidades retidas',
    requestedUnits: 'Unidades solicitadas',
    cancelledUnits: 'Unidades canceladas'
};
const BUCKET_LABELS: Record<string, string> = { LT_6H: '< 6h', H6_TO_24: '6–24h', H24_TO_48: '24–48h', GTE_48H: '≥ 48h' };
const PRIORITY: Record<string, 'danger' | 'warn' | 'info'> = { HIGH: 'danger', MEDIUM: 'warn', INFO: 'info' };
const MAX_POLLS = 12;

type SeriesKey = 'retainedOrderValue' | 'orderCount' | 'cancelledOrderValue' | 'activeBuyerCount';
const SERIES: { key: SeriesKey; label: string; money: boolean }[] = [
    { key: 'retainedOrderValue', label: 'Valor retido', money: true },
    { key: 'orderCount', label: 'Pedidos', money: false },
    { key: 'cancelledOrderValue', label: 'Valor cancelado', money: true },
    { key: 'activeBuyerCount', label: 'Compradores', money: false }
];

@Component({
    selector: 'app-dashboard-page',
    standalone: true,
    imports: [NgTemplateOutlet, FormsModule, RouterLink, ButtonModule, CardModule, ChartModule, MessageModule, SelectButtonModule, SkeletonModule, TableModule, TabsModule, TagModule, ProblemMessage, EmptyState],
    template: `
        <div class="flex flex-wrap items-center justify-between gap-3 mb-4">
            <div>
                <h2 class="text-2xl font-semibold m-0">Dashboard de vendas</h2>
                @if (data(); as d) {
                    <small class="text-muted-color">
                        {{ dateOnly(d.dashboard.period.from) }} – {{ dateOnly(d.dashboard.period.to) }}
                        @if (d.dashboard.period.inProgress) { (em andamento) }
                        · calculado em {{ dateTime(d.dashboard.computedAt) }}
                    </small>
                }
            </div>
            <div class="flex items-center gap-2">
                <p-selectbutton [options]="periodOptions" optionLabel="label" optionValue="value" [ngModel]="period()" (ngModelChange)="onPeriod($event)" [allowEmpty]="false" ariaLabelledBy="period" />
                <p-button icon="pi pi-refresh" severity="secondary" [loading]="loading()" (onClick)="load()" ariaLabel="Atualizar" />
            </div>
        </div>

        @if (initializing()) {
            <p-message severity="info" styleClass="w-full mb-4">O dashboard está sendo preparado. Tentando novamente automaticamente… ({{ polls() }}/{{ maxPolls }})</p-message>
        }
        @if (gaveUp()) {
            <p-message severity="warn" styleClass="w-full mb-4">O dashboard ainda não ficou disponível. <p-button label="Tentar novamente" size="small" severity="secondary" (onClick)="load()" /></p-message>
        }
        <app-problem [error]="error()" [retryable]="true" (retry)="load()" />

        @if (data(); as d) {
            @if (d.availability.status === 'STALE') {
                <p-message severity="warn" styleClass="w-full mb-4">
                    Dados desatualizados há {{ d.availability.ageSeconds }}s{{ d.availability.warning ? ' — ' + d.availability.warning : '' }}.
                </p-message>
            }

            @let v = d.dashboard;
            <div class="grid grid-cols-12 gap-4 mb-4">
                @for (k of summaryKeys(); track k) {
                    @let m = v.summary[k];
                    <div class="col-span-12 sm:col-span-6 xl:col-span-3">
                        <div class="card mb-0">
                            <div class="text-muted-color mb-1">{{ metricLabel(k) }}</div>
                            <div class="text-2xl font-semibold">{{ metricValue(m, v.currency) }}</div>
                            <div class="mt-2 text-sm" [class]="changeClass(m)">{{ change(m) }}</div>
                            @if (m.value === null) { <small class="text-muted-color">Indisponível ({{ m.status }})</small> }
                        </div>
                    </div>
                }
            </div>

            @if (v.unavailableMetrics.length) {
                <p-message severity="secondary" styleClass="w-full mb-4">
                    Métricas indisponíveis:
                    @for (u of v.unavailableMetrics; track u.code) { <span class="mr-3">{{ metricLabel(u.code) }} ({{ u.reason }})</span> }
                </p-message>
            }

            <div class="card">
                <div class="font-semibold text-xl mb-3">Evolução diária</div>
                <p-tabs [value]="series()" (valueChange)="series.set($any($event))">
                    <p-tablist>@for (s of seriesOptions; track s.key) { <p-tab [value]="s.key">{{ s.label }}</p-tab> }</p-tablist>
                </p-tabs>
                @if (v.daily.length) {
                    <p-chart type="line" [data]="chartData()" [options]="chartOptions()" height="320px" />
                } @else {
                    <app-empty text="Sem dados diários no período." />
                }
            </div>

            <div class="card">
                <div class="font-semibold text-xl mb-1">Produtos</div>
                <small class="text-muted-color block mb-3">
                    Top 5 = {{ percent(v.products.top5ProductValueShare) }} do valor retido
                    @if (v.products.otherProductsRetainedValue !== null) { · demais produtos: {{ money(v.products.otherProductsRetainedValue, v.currency) }} }
                </small>
                <p-tabs value="value">
                    <p-tablist>
                        <p-tab value="value">Por valor</p-tab>
                        <p-tab value="units">Por unidades</p-tab>
                        <p-tab value="declining">Em queda</p-tab>
                    </p-tablist>
                    <p-tabpanels>
                        <p-tabpanel value="value"><ng-container *ngTemplateOutlet="products; context: { $implicit: v.products.byRetainedValue, cur: v.currency }" /></p-tabpanel>
                        <p-tabpanel value="units"><ng-container *ngTemplateOutlet="products; context: { $implicit: v.products.byRetainedUnits, cur: v.currency }" /></p-tabpanel>
                        <p-tabpanel value="declining"><ng-container *ngTemplateOutlet="products; context: { $implicit: v.products.declining, cur: v.currency }" /></p-tabpanel>
                    </p-tabpanels>
                </p-tabs>
            </div>

            <div class="grid grid-cols-12 gap-4">
                <div class="col-span-12 lg:col-span-6">
                    <div class="card h-full">
                        <div class="font-semibold text-xl mb-3">Clientes</div>
                        <dl class="grid grid-cols-2 gap-2 m-0">
                            <dt class="text-muted-color">Compradores ativos</dt><dd class="m-0 text-right">{{ num(v.customers.activeBuyerCount) }}</dd>
                            <dt class="text-muted-color">Novos</dt><dd class="m-0 text-right">{{ num(v.customers.newActiveBuyerCount) }}</dd>
                            <dt class="text-muted-color">Recorrentes</dt><dd class="m-0 text-right">{{ num(v.customers.returningActiveBuyerCount) }} ({{ percent(v.customers.returningBuyerShare) }})</dd>
                            <dt class="text-muted-color">Pedidos por comprador</dt><dd class="m-0 text-right">{{ num(v.customers.ordersPerActiveBuyer, 2) }}</dd>
                            <dt class="text-muted-color">Top 5 clientes</dt><dd class="m-0 text-right">{{ percent(v.customers.top5CustomerValueShare) }} · {{ money(v.customers.top5CustomerRetainedValue, v.currency) }}</dd>
                        </dl>
                        @if (v.valueChangeDrivers; as dr) {
                            <div class="mt-4 font-semibold">Variação do valor retido</div>
                            @if (dr.status === 'AVAILABLE' || dr.totalChange !== null) {
                                <div class="text-sm">Total: {{ money(dr.totalChange, v.currency) }} · Volume: {{ money(dr.volumeEffect, v.currency) }} · Ticket médio: {{ money(dr.averageValueEffect, v.currency) }}</div>
                            } @else { <small class="text-muted-color">Indisponível ({{ dr.status }})</small> }
                        }
                    </div>
                </div>
                <div class="col-span-12 lg:col-span-6">
                    <div class="card h-full">
                        <div class="font-semibold text-xl mb-1">Carteira em aberto</div>
                        <small class="text-muted-color block mb-3">{{ num(v.openPortfolio.orderCount) }} pedidos · {{ money(v.openPortfolio.value, v.currency) }}
                            @if (v.openPortfolio.oldestCreatedAt) { · mais antigo: {{ dateTime(v.openPortfolio.oldestCreatedAt) }} }</small>
                        @for (b of v.openPortfolio.ageBuckets; track b.code) {
                            <div class="flex justify-between items-center py-2 border-b border-surface">
                                <span>{{ bucketLabel(b.code) }}</span>
                                <span>{{ num(b.orderCount) }} · {{ money(b.value, v.currency) }}
                                    @if (link(b.drillDown); as l) { <a [routerLink]="l.commands" [queryParams]="l.queryParams" class="ml-2 text-primary">ver</a> }
                                </span>
                            </div>
                        }
                    </div>
                </div>
            </div>

            <div class="card mt-4">
                <div class="font-semibold text-xl mb-3">Insights</div>
                @for (i of v.insights; track i.code) {
                    <div class="border border-surface rounded p-4 mb-3">
                        <div class="flex items-center gap-2 mb-1">
                            <p-tag [value]="i.priority" [severity]="priority(i.priority)" />
                            <strong>{{ i.title }}</strong>
                        </div>
                        <p class="m-0 mb-2">{{ i.description }}</p>
                        <small class="text-muted-color block">Exposição: {{ num(i.exposure.orderCount) }} pedidos · {{ money(i.exposure.value, v.currency) }} · amostra {{ i.evidence.currentSampleSize }}</small>
                        <small class="block mt-1"><strong>Ação sugerida:</strong> {{ i.suggestedAction }}</small>
                        @for (lim of i.limitations; track lim) { <small class="text-muted-color block">⚠ {{ lim }}</small> }
                        @if (link(i.drillDown); as l) { <a [routerLink]="l.commands" [queryParams]="l.queryParams" class="text-primary text-sm">Ver detalhes</a> }
                    </div>
                } @empty { <app-empty text="Nenhum insight para o período." /> }
                @if (v.additionalInsightCount > 0) { <small class="text-muted-color">+{{ v.additionalInsightCount }} insights adicionais não exibidos.</small> }
            </div>

            <div class="card mt-4 text-sm text-muted-color">
                <div>Fonte: {{ v.dataCoverage.source }} · geração {{ v.dataCoverage.generation }} · completude {{ v.dataCoverage.completeness }}
                    @if (v.dataCoverage.earliestOrderAt) { · desde {{ dateOnly(v.dataCoverage.earliestOrderAt) }} }</div>
                <div>Fuso {{ v.timeZone }} · definições v{{ v.definitionsVersion }} · regras v{{ v.rulesVersion }}</div>
                <div class="mt-1">Regras aplicadas: @for (r of rules(); track r[0]) { <span class="mr-3">{{ r[0] }}={{ r[1] }}</span> }</div>
            </div>

            <ng-template #products let-rows let-cur="cur">
                <p-table [value]="rows" [tableStyle]="{ 'min-width': '40rem' }">
                    <ng-template #header><tr><th>Produto</th><th class="text-right">Valor retido</th><th class="text-right">Unid.</th><th class="text-right">Pedidos</th><th class="text-right">Participação</th><th class="text-right">Cancel. (valor)</th><th class="text-right">Variação</th></tr></ng-template>
                    <ng-template #body let-p>
                        <tr>
                            <td>@if (auth.hasScope('cqrs.read')) { <a [routerLink]="['/products', p.productId]" class="text-primary">{{ p.productName }}</a> } @else { {{ p.productName }} }</td>
                            <td class="text-right">{{ money(p.retainedOrderValue, cur) }}</td>
                            <td class="text-right">{{ num(p.retainedUnits) }}</td>
                            <td class="text-right">{{ num(p.activeOrderCount) }}</td>
                            <td class="text-right">{{ percent(p.retainedValueShare) }}</td>
                            <td class="text-right">{{ percent(p.cancellationValueRate) }}</td>
                            <td class="text-right">{{ productChange(p) }}</td>
                        </tr>
                    </ng-template>
                    <ng-template #emptymessage><tr><td colspan="7"><app-empty /></td></tr></ng-template>
                </p-table>
            </ng-template>
        } @else if (loading() && !error()) {
            <div class="grid grid-cols-12 gap-4">
                @for (n of [1, 2, 3, 4]; track n) { <div class="col-span-12 sm:col-span-6 xl:col-span-3"><p-skeleton height="7rem" /></div> }
            </div>
        }
    `
})
export class DashboardPage {
    private readonly api = inject(ApiService);
    protected readonly auth = inject(AuthService);
    private readonly url = injectUrlState();
    private readonly destroyRef = inject(DestroyRef);

    protected readonly dateOnly = dateOnly;
    protected readonly dateTime = dateTime;
    protected readonly metricValue = metricValue;
    protected readonly money = money;
    protected readonly num = num;
    protected readonly percent = percent;
    protected readonly maxPolls = MAX_POLLS;
    protected readonly seriesOptions = SERIES;
    protected readonly periodOptions = PERIOD_CODES.map((value) => ({ value, label: PERIOD_LABELS[value] }));

    protected readonly period = computed<PeriodCode>(() => {
        const p = this.url.get('period') as PeriodCode | null;
        return p && PERIOD_CODES.includes(p) ? p : DEFAULT_PERIOD;
    });
    protected readonly data = signal<DashboardResponse | null>(null);
    protected readonly error = signal<ApiProblem | null>(null);
    protected readonly loading = signal(false);
    protected readonly initializing = signal(false);
    protected readonly gaveUp = signal(false);
    protected readonly polls = signal(0);
    protected readonly series = signal<SeriesKey>('retainedOrderValue');

    private timer: ReturnType<typeof setTimeout> | undefined;
    private seq = 0;

    protected readonly summaryKeys = computed(() => Object.keys(this.data()?.dashboard.summary ?? {}));
    protected readonly rules = computed(() => Object.entries(this.data()?.dashboard.appliedRules ?? {}));

    protected readonly chartData = computed(() => {
        const v = this.data()?.dashboard;
        const key = this.series();
        const label = SERIES.find((s) => s.key === key)!.label;
        const cmp = v?.comparisonDaily ?? [];
        return {
            labels: (v?.daily ?? []).map((d) => dateOnly(d.date)),
            datasets: [
                { label, data: (v?.daily ?? []).map((d) => d[key]), borderColor: '#3b82f6', tension: 0.3, fill: false },
                ...(cmp.length ? [{ label: `${label} (período anterior)`, data: cmp.map((d) => d[key]), borderColor: '#9ca3af', borderDash: [5, 5], tension: 0.3, fill: false }] : [])
            ]
        };
    });
    protected readonly chartOptions = computed(() => {
        const isMoney = SERIES.find((s) => s.key === this.series())!.money;
        const cur = this.data()?.dashboard.currency ?? 'BRL';
        return {
            maintainAspectRatio: false,
            plugins: { tooltip: { callbacks: { label: (c: { dataset: { label?: string }; parsed: { y: number | null } }) => `${c.dataset.label}: ${isMoney ? money(c.parsed.y, cur) : num(c.parsed.y)}` } } },
            scales: { y: { beginAtZero: true } }
        };
    });

    constructor() {
        effect(() => {
            this.period();
            this.load();
        });
        this.destroyRef.onDestroy(() => {
            this.seq++;
            clearTimeout(this.timer);
        });
    }

    protected onPeriod(p: PeriodCode): void {
        this.url.set({ period: p === DEFAULT_PERIOD ? null : p });
    }

    protected load(): void {
        clearTimeout(this.timer);
        const run = ++this.seq;
        this.polls.set(0);
        this.gaveUp.set(false);
        this.fetch(run);
    }

    private fetch(run: number): void {
        this.loading.set(true);
        this.api.dashboard(this.period()).subscribe({
            next: (res) => {
                if (run !== this.seq) return;
                this.data.set(res);
                this.error.set(null);
                this.initializing.set(false);
                this.loading.set(false);
            },
            error: (e) => {
                if (run !== this.seq) return;
                const p = toProblem(e);
                if (p.status === 503 && p.code === 'DASHBOARD_INITIALIZING') {
                    this.data.set(null);
                    this.error.set(null);
                    if (this.polls() >= MAX_POLLS) {
                        this.initializing.set(false);
                        this.gaveUp.set(true);
                        this.loading.set(false);
                        return;
                    }
                    this.initializing.set(true);
                    this.polls.update((n) => n + 1);
                    this.timer = setTimeout(() => this.fetch(run), Math.max(p.retryAfter ?? 0, 5) * 1000);
                    return;
                }
                this.initializing.set(false);
                this.error.set(p);
                this.loading.set(false);
            }
        });
    }

    protected metricLabel(k: string): string {
        return METRIC_LABELS[k] ?? k;
    }
    protected bucketLabel(c: string): string {
        return BUCKET_LABELS[c] ?? c;
    }
    protected priority(p: string) {
        return PRIORITY[p] ?? 'info';
    }
    protected link(dd: DrillDown | null | undefined) {
        return resolveDrillDown(dd, (s) => this.auth.hasScope(s));
    }

    protected change(m: Metric): string {
        if (m.comparisonStatus && m.comparisonStatus !== 'AVAILABLE' && m.changePercent === null && m.changePercentagePoints === null) return 'Sem comparação';
        if (m.changePercentagePoints !== null) return `${m.changePercentagePoints > 0 ? '+' : ''}${num(m.changePercentagePoints, 2)} p.p.`;
        if (m.changePercent !== null) return `${m.changePercent > 0 ? '+' : ''}${num(m.changePercent, 2)}%`;
        return 'Sem comparação';
    }
    protected changeClass(m: Metric): string {
        const c = m.changePercentagePoints ?? m.changePercent;
        if (c === null || c === 0 || m.favorableDirection === 'CONTEXT_DEPENDENT') return 'text-muted-color';
        const good = (c > 0) === (m.favorableDirection === 'UP');
        return good ? 'text-green-500' : 'text-red-500';
    }
    protected productChange(p: DashboardProduct): string {
        if (p.changePercent === null) return '—';
        return `${p.changePercent > 0 ? '+' : ''}${num(p.changePercent, 1)}%`;
    }
}

export default DashboardPage;
