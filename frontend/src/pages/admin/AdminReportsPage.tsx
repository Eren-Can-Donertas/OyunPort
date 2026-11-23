import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { reportApi } from '../../api/endpoints';
import type { PlanResult, ReportResult } from '../../api/types';
import { EmptyState, ErrorBox, Loading } from '../../components/Feedback';
import { formatDecimal, formatInt } from '../../lib/format';
import { columnLabel, formatCell, isNumericColumn } from '../../lib/reportFormat';
import { useAsync } from '../../lib/useAsync';

const OVERVIEW_KEY = 'overview';

function OverviewCards() {
  const overview = useAsync(() => reportApi.run(OVERVIEW_KEY), []);
  if (overview.loading && !overview.data) return <Loading label="Genel bakış yükleniyor…" />;
  if (overview.error) return <ErrorBox error={overview.error} title="Genel bakış yüklenemedi" onRetry={overview.reload} />;
  const r = overview.data;
  const row = r?.rows[0];
  if (!r || !row) return null;
  return (
    <div className="block">
      <div className="stat-grid">
        {r.columns.map((c) => (
          <div key={c} className="stat card">
            <span className="stat-label">{columnLabel(c)}</span>
            <span className="stat-value">{formatCell(c, row[c])}</span>
          </div>
        ))}
      </div>
      <p className="muted small">
        Kaynak: <code>rpt_overview</code> · {formatInt(r.elapsedMs)} ms
      </p>
    </div>
  );
}

function ReportTable({ result }: { result: ReportResult }) {
  if (result.rows.length === 0) return <EmptyState>Bu rapor için satır yok.</EmptyState>;
  const first = result.rows[0];
  return (
    <div className="table-wrap report-table">
      <table className="table table-compact">
        <caption className="sr-only">{result.title}</caption>
        <thead>
          <tr>
            {result.columns.map((c) => (
              <th key={c} scope="col" className={isNumericColumn(c, first[c]) ? 'num' : undefined}>
                <span className="col-label">{columnLabel(c)}</span>
                <code className="col-raw">{c}</code>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {result.rows.map((row, i) => (
            <tr key={i}>
              {result.columns.map((c) => (
                <td key={c} className={isNumericColumn(c, first[c]) ? 'num' : undefined}>
                  {formatCell(c, row[c])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function PlanView({ reportKey }: { reportKey: string }) {
  const [plan, setPlan] = useState<PlanResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function load() {
    setBusy(true);
    setError(null);
    try {
      setPlan(await reportApi.plan(reportKey));
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="block">
      <button type="button" className="btn" onClick={load} disabled={busy}>
        {busy ? 'Plan alınıyor…' : plan ? 'Sorgu planını yenile' : 'Sorgu planı (EXPLAIN ANALYZE)'}
      </button>
      <ErrorBox error={error} />
      {plan && (
        <div className="plan">
          <p className="plan-times">
            <span>
              Çalışma süresi: <strong>{plan.executionTimeMs === null ? '—' : `${formatDecimal(plan.executionTimeMs)} ms`}</strong>
            </span>
            <span>
              Planlama süresi: <strong>{plan.planningTimeMs === null ? '—' : `${formatDecimal(plan.planningTimeMs)} ms`}</strong>
            </span>
          </p>
          <pre className="code-block" aria-label="EXPLAIN (ANALYZE, BUFFERS) çıktısı">
            {plan.plan.join('\n')}
          </pre>
        </div>
      )}
    </div>
  );
}

function ReportRunner({ reportKey }: { reportKey: string }) {
  const result = useAsync(() => reportApi.run(reportKey), [reportKey]);
  const r = result.data;
  return (
    <div className="card report-panel" aria-live="polite">
      {result.loading && !r && <Loading label="Rapor çalıştırılıyor…" />}
      <ErrorBox error={result.error} onRetry={result.reload} />
      {r && (
        <div className={result.loading ? 'is-refreshing' : undefined}>
          <div className="page-head">
            <h2>{r.title}</h2>
            <div className="button-row">
              <span className="muted small">
                {formatInt(r.rows.length)} satır · {formatInt(r.elapsedMs)} ms
              </span>
              <button type="button" className="btn btn-small" onClick={result.reload} disabled={result.loading}>
                Yeniden çalıştır
              </button>
            </div>
          </div>
          <p className="muted">{r.description}</p>
          <ReportTable result={r} />
        </div>
      )}
    </div>
  );
}

export function AdminReportsPage() {
  const defs = useAsync(() => reportApi.definitions(), []);
  const [sp, setSp] = useSearchParams();
  const selected = sp.get('rapor');
  const def = defs.data?.find((d) => d.key === selected) ?? null;

  return (
    <section className="page" aria-labelledby="reports-title">
      <div className="page-head">
        <h1 id="reports-title">Admin Raporlar</h1>
      </div>

      <OverviewCards />

      <ErrorBox error={defs.error} title="Rapor listesi yüklenemedi" onRetry={defs.reload} />
      {defs.loading && !defs.data && <Loading label="Rapor listesi yükleniyor…" />}

      {defs.data && (
        <div className="reports-layout">
          <nav className="report-list" aria-label="Raporlar">
            <ul>
              {defs.data.map((d) => (
                <li key={d.key}>
                  <button
                    type="button"
                    className={`report-link ${d.key === selected ? 'active' : ''}`}
                    aria-current={d.key === selected ? 'true' : undefined}
                    onClick={() => setSp({ rapor: d.key })}
                  >
                    <strong>{d.title}</strong>
                    <span className="muted small">{d.description}</span>
                  </button>
                </li>
              ))}
            </ul>
          </nav>
          <div className="report-main">
            {!def ? (
              <EmptyState>Çalıştırmak için soldan bir rapor seçin.</EmptyState>
            ) : (
              <>
                <ReportRunner key={def.key} reportKey={def.key} />
                <details className="block sql-details">
                  <summary>SQL sorgusu</summary>
                  <pre className="code-block">{def.sql}</pre>
                </details>
                <PlanView key={`plan-${def.key}`} reportKey={def.key} />
              </>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
