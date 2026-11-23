import { Link, useSearchParams } from 'react-router-dom';
import { orderApi } from '../api/endpoints';
import { OrderStatusBadge } from '../components/Badges';
import { EmptyState, ErrorBox, Loading } from '../components/Feedback';
import { Pagination } from '../components/Pagination';
import { formatDateTime, formatMoney } from '../lib/format';
import { useAsync } from '../lib/useAsync';

const PAGE_SIZE = 20;

export function OrdersPage() {
  const [sp, setSp] = useSearchParams();
  const page = Math.max(0, Number(sp.get('page') ?? 0) || 0);
  const orders = useAsync(() => orderApi.list(page, PAGE_SIZE), [page], { keepPrevious: true });

  return (
    <section className="page" aria-labelledby="orders-title">
      <div className="page-head">
        <h1 id="orders-title">Siparişlerim</h1>
      </div>
      <ErrorBox error={orders.error} onRetry={orders.reload} />
      {orders.loading && !orders.data && <Loading label="Siparişler yükleniyor…" />}
      {orders.data &&
        (orders.data.total === 0 ? (
          <EmptyState>
            Henüz siparişiniz yok. <Link to="/">Mağazaya göz atın →</Link>
          </EmptyState>
        ) : (
          <div className={orders.loading ? 'is-refreshing' : undefined} aria-busy={orders.loading}>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">Sipariş</th>
                    <th scope="col">Tarih</th>
                    <th scope="col">Durum</th>
                    <th scope="col" className="num">
                      Kalem
                    </th>
                    <th scope="col" className="num">
                      Tutar
                    </th>
                    <th scope="col">Tamamlanma</th>
                  </tr>
                </thead>
                <tbody>
                  {orders.data.items.map((o) => (
                    <tr key={o.orderId}>
                      <td>
                        <Link to={`/siparisler/${o.orderId}`}>#{o.orderId}</Link>
                      </td>
                      <td>{formatDateTime(o.createdAt)}</td>
                      <td>
                        <OrderStatusBadge status={o.status} />
                      </td>
                      <td className="num">{o.itemCount}</td>
                      <td className="num">{formatMoney(o.total)}</td>
                      <td>{formatDateTime(o.completedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={orders.data.page}
              size={orders.data.size}
              total={orders.data.total}
              disabled={orders.loading}
              onChange={(p) => setSp(p > 0 ? { page: String(p) } : {})}
            />
          </div>
        ))}
    </section>
  );
}
