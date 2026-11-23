import { useEffect, useState, type FormEvent } from 'react';
import { reviewApi } from '../api/endpoints';
import type { MyReview, MyReviewView } from '../api/types';
import { formatDateTime } from '../lib/format';
import { ErrorBox, Notice } from './Feedback';

const RATING_LABELS: Record<number, string> = {
  5: '5 – Mükemmel',
  4: '4 – İyi',
  3: '3 – Orta',
  2: '2 – Zayıf',
  1: '1 – Kötü',
};

const MAX_COMMENT = 2000;

interface Props {
  baseGameId: number;
  initial: MyReview | null;
  onSaved: (review: MyReviewView) => void;
}

/** Sahip olunan ana oyun için puan + yorum (PUT /api/reviews/{baseGameId}: ekle veya güncelle). */
export function ReviewForm({ baseGameId, initial, onSaved }: Props) {
  const [rating, setRating] = useState<number>(initial?.rating ?? 5);
  const [comment, setComment] = useState(initial?.comment ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [saved, setSaved] = useState<string | null>(null);

  useEffect(() => {
    setRating(initial?.rating ?? 5);
    setComment(initial?.comment ?? '');
  }, [initial?.rating, initial?.comment]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setSaved(null);
    try {
      const trimmed = comment.trim();
      const result = await reviewApi.upsert(baseGameId, rating, trimmed === '' ? null : trimmed);
      setSaved(result.created ? 'Yorumunuz kaydedildi.' : 'Yorumunuz güncellendi.');
      onSaved(result);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="form review-form" onSubmit={onSubmit} aria-labelledby="review-form-title">
      <h3 id="review-form-title">{initial ? 'Yorumunuzu düzenleyin' : 'Bu oyunu değerlendirin'}</h3>
      {initial && <p className="muted small">Son güncelleme: {formatDateTime(initial.updatedAt)}</p>}
      {saved && <Notice kind="success" onClose={() => setSaved(null)}>{saved}</Notice>}
      <ErrorBox error={error} />
      <div className="field">
        <label htmlFor="review-rating">Puan</label>
        <select id="review-rating" value={rating} onChange={(e) => setRating(Number(e.target.value))}>
          {[5, 4, 3, 2, 1].map((r) => (
            <option key={r} value={r}>
              {'★'.repeat(r)} {RATING_LABELS[r]}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="review-comment">Yorum (isteğe bağlı)</label>
        <textarea
          id="review-comment"
          rows={4}
          maxLength={MAX_COMMENT}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          aria-describedby="review-comment-count"
        />
        <small id="review-comment-count" className="hint">
          {comment.length} / {MAX_COMMENT}
        </small>
      </div>
      <button type="submit" className="btn btn-primary" disabled={busy}>
        {busy ? 'Kaydediliyor…' : initial ? 'Yorumu güncelle' : 'Yorumu kaydet'}
      </button>
    </form>
  );
}
