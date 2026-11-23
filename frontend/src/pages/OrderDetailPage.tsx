import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { isTransientError } from '../api/client';
import { orderApi } from '../api/endpoints';
import type { PaymentResult, SimulatedOutcome } from '../api/types';
import { OrderStatusBadge, PaymentStatusBadge, TypeBadge } from '../components/Badges';
import { EmptyState, ErrorBox, Loading, Notice } from '../components/Feedback';
import { formatDateTime, formatMoney, shortKey } from '../lib/format';
import { withSingleRetry } from '../lib/retry';
import { useAsync } from '../lib/useAsync';
import { newUuid } from '../lib/uuid';
import type { OrderCreatedState } from './CartPage';

const OUTCOMES: { value: SimulatedOutcome; label: string }[] = [
  { value: 'SUCCESS', label: 'Başarılı' },
  { value: 'CARD_DECLINED', label: 'Kart reddedildi' },
  { value: 'INSUFFICIENT_FUNDS', label: 'Yetersiz bakiye' },
];

export function OrderDetailPage() {
  const { id } = useParams();
  const orderId = Number(id);
  const location = useLocation();
  const created = (location.state ?? null) as OrderCreatedState | null;

  const order = useAsync(() => orderApi.get(orderId), [orderId]);
  const [outcome, setOutcome] = useState<SimulatedOutcome>('SUCCESS');
  const [paying, setPaying] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [payResult, setPayResult] = useState<PaymentResult | null>(null);
  const [actionError, setActionError] = useState<unknown>(null);
  const [cancelled, setCancelled] = useState(false);

  if (!Number.isSafeInteger(orderId) || orderId <= 0) {
    return (
      <section className="page">
        <EmptyState>Geçersiz sipariş numarası.</EmptyState>
      </section>
    );
  }

  async function pay() {
    setPaying(true);
    setActionError(null);
    setPayResult(null);
    // Her "Öde" tıklaması için yeni requestKey; ağ hatasında AYNI anahtarla bir kez tekrar.
    const requestKey = newUuid();
    try {
      const result = await withSingleRetry(() => orderApi.pay(orderId, requestKey, outcome), isTransientError);
      setPayResult(result);
    } catch (err) {
      setActionError(err);
    } finally {
      setPaying(false);
      order.reload();
    }
  }

  async function cancel() {
    if (!window.confirm('Sipariş iptal edilsin mi? Bu işlem geri alınamaz.')) return;
    setCancelling(true);
    setActionError(null);
    setPayResult(null);
    try {
      const updated = await orderApi.cancel(orderId);
      order.setData(() => updated);
      setCancelled(true);
    } catch (err) {
      setActionError(err);
      order.reload();
    } finally {
      setCancelling(false);
    }
  }

  const o = order.data;
  const priceChanged = o?.items.some((i) => i.currentListPrice !== i.unitPricePaid) ?? false;

  return (
    <section className="page" aria-labelledby="order-title">
      <p className="breadcrumb">
        <Link to="/siparisler">← Siparişlerim</Link>
      </p>
      <ErrorBox error={order.error} onRetry={order.reload} />
      {order.loading && !o && <Loading label="Sipariş yükleniyor…" />}

      {o && (
        <>
          <div className="page-head">
            <h1 id="order-title">Sipariş #{o.orderId}</h1>
            <OrderStatusBadge status={o.status} />
          </div>

          {created && created.replayed && (
            <Notice kind="info">
              Bu sepet için daha önce oluşturulmuş sipariş açıldı (aynı checkoutKey ile idempotent tekrar; yeni sipariş
              oluşturulmadı).
            </Notice>
          )}
          {created && !created.replayed && o.status === 'PENDING' && !payResult && (
            <Notice kind="success">Siparişiniz oluşturuldu. Ödemeyi aşağıdan tamamlayabilirsiniz.</Notice>
          )}
          {cancelled && <Notice kind="info">Sipariş iptal edildi.</Notice>}

          <dl className="facts">
            <div>
              <dt>Oluşturulma</dt>
              <dd>{formatDateTime(o.createdAt)}</dd>
            </div>
            <div>
              <dt>Tamamlanma</dt>
              <dd>{formatDateTime(o.completedAt)}</dd>
            </div>
            <div>
              <dt>Kalem sayısı</dt>
              <dd>{o.itemCount}</dd>
            </div>
            <div>
              <dt>Toplam</dt>
              <dd className="price">{formatMoney(o.total)}</dd>
            </div>
            <div>
              <dt>checkoutKey</dt>
              <dd>
                <code title={o.checkoutKey}>{shortKey(o.checkoutKey)}</code>
              </dd>
            </div>
          </dl>

          <div className="block">
            <h2>Kalemler</h2>
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th scope="col">#</th>
                    <th scope="col">Ürün</th>
                    <th scope="col">Tip</th>
                    <th scope="col" className="num">
                      Ödenen birim fiyat
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {o.items.map((it) => (
                    <tr key={it.lineNo}>
                      <td>{it.lineNo}</td>
                      <td>
                        <Link to={`/urun/${it.productId}`}>{it.title}</Link>
                      </td>
                      <td>
                        <TypeBadge type={it.productType} />
                      </td>
                      <td className="num">
                        {formatMoney(it.unitPricePaid)}
                        {it.currentListPrice !== it.unitPricePaid && (
                          <div className="muted small">Güncel liste fiyatı: {formatMoney(it.currentListPrice)}</div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <th scope="row" colSpan={3}>
                      Toplam
                    </th>
                    <td className="num">
                      <strong>{formatMoney(o.total)}</strong>
                    </td>
                  </tr>
                </tfoot>
              </table>
            </div>
            {priceChanged && (
              <p className="muted small">
                Not: Bazı ürünlerin liste fiyatı sipariş oluşturulduktan sonra değişti. Sipariş tutarı, sipariş anındaki
                fiyatla sabittir (BR07).
              </p>
            )}
          </div>

          {payResult && payResult.paymentStatus === 'SUCCESS' && (
            <Notice kind="success">
              <strong>Ödeme başarılı.</strong> {formatMoney(payResult.amount)} tahsil edildi (simülasyon);{' '}
              {payResult.libraryIds.length} ürün kütüphanenize eklendi.
              {payResult.replayed && ' (Aynı istek anahtarı ile önceki sonuç döndü.)'}{' '}
              <Link to="/kutuphane">Kütüphanem →</Link>
            </Notice>
          )}
          {payResult && payResult.paymentStatus === 'FAILED' && (
            <Notice kind="error">
              <strong>Ödeme başarısız.</strong>{' '}
              {payResult.failureReason ?? 'Ödeme simülasyonu reddedildi.'} Sipariş hâlâ ödeme bekliyor; tekrar
              deneyebilirsiniz.
            </Notice>
          )}
          <ErrorBox error={actionError} title="İşlem tamamlanamadı" />

          {o.status === 'PENDING' && (
            <div className="card payment-box" aria-labelledby="pay-title">
              <h2 id="pay-title">Ödeme simülasyonu</h2>
              <p className="muted small">
                Bu bir ders projesidir: gerçek ödeme alınmaz, kart bilgisi istenmez. Aşağıdan simülasyonun sonucunu
                seçin.
              </p>
              <div className="field">
                <label htmlFor="pay-outcome">Simülasyon sonucu</label>
                <select
                  id="pay-outcome"
                  value={outcome}
                  onChange={(e) => setOutcome(e.target.value as SimulatedOutcome)}
                  disabled={paying || cancelling}
                >
                  {OUTCOMES.map((x) => (
                    <option key={x.value} value={x.value}>
                      {x.label}
                    </option>
                  ))}
                </select>
              </div>
              <div className="button-row">
                <button type="button" className="btn btn-primary" onClick={pay} disabled={paying || cancelling}>
                  {paying ? 'Ödeniyor…' : `Öde (${formatMoney(o.total)})`}
                </button>
                <button type="button" className="btn btn-danger" onClick={cancel} disabled={paying || cancelling}>
                  {cancelling ? 'İptal ediliyor…' : 'Siparişi iptal et'}
                </button>
              </div>
            </div>
          )}
          {o.status === 'COMPLETED' && !payResult && (
            <p>
              Bu siparişteki ürünler <Link to="/kutuphane">kütüphanenizde</Link>.
            </p>
          )}

          <div className="block">
            <h2>Ödeme denemeleri ({o.payments.length})</h2>
            {o.payments.length === 0 ? (
              <p className="muted">Henüz ödeme denemesi yok.</p>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <thead>
                    <tr>
                      <th scope="col">Ödeme no</th>
                      <th scope="col">Zaman</th>
                      <th scope="col">Durum</th>
                      <th scope="col" className="num">
                        Tutar
                      </th>
                      <th scope="col">requestKey</th>
                    </tr>
                  </thead>
                  <tbody>
                    {o.payments.map((p) => (
                      <tr key={p.paymentId}>
                        <td>#{p.paymentId}</td>
                        <td>{formatDateTime(p.attemptedAt)}</td>
                        <td>
                          <PaymentStatusBadge status={p.status} />
                        </td>
                        <td className="num">{formatMoney(p.amount)}</td>
                        <td>
                          <code title={p.requestKey}>{shortKey(p.requestKey)}</code>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </section>
  );
}
