import { useSearchParams } from 'react-router-dom';
import type { RefKind } from '../../api/types';
import { AdminProductsTab } from './AdminProductsTab';
import { AdminRefsTab } from './AdminRefsTab';

type Tab = 'products' | RefKind;

const TABS: { key: Tab; label: string }[] = [
  { key: 'products', label: 'Ürünler' },
  { key: 'publishers', label: "Publisher'lar" },
  { key: 'developers', label: 'Geliştiriciler' },
  { key: 'genres', label: 'Türler' },
];

export function AdminCatalogPage() {
  const [sp, setSp] = useSearchParams();
  const raw = sp.get('tab');
  const tab: Tab = TABS.some((t) => t.key === raw) ? (raw as Tab) : 'products';

  return (
    <section className="page" aria-labelledby="admin-catalog-title">
      <div className="page-head">
        <h1 id="admin-catalog-title">Admin Katalog</h1>
      </div>
      <div className="tabs" role="tablist" aria-label="Katalog yönetimi">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            id={`tab-${t.key}`}
            aria-selected={tab === t.key}
            aria-controls={`panel-${t.key}`}
            className={`tab ${tab === t.key ? 'tab-active' : ''}`}
            onClick={() => setSp(t.key === 'products' ? {} : { tab: t.key })}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`} className="tab-panel">
        {tab === 'products' ? <AdminProductsTab /> : <AdminRefsTab key={tab} kind={tab} />}
      </div>
    </section>
  );
}
