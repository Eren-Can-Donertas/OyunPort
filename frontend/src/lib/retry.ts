/**
 * İşlemi çalıştırır; hata "tekrar denenebilir" ise TAM OLARAK BİR KEZ daha dener.
 * Çağıran, iki denemede de aynı idempotency anahtarını kullanmalıdır (ör. ödeme requestKey).
 */
export async function withSingleRetry<T>(
  operation: () => Promise<T>,
  isRetryable: (error: unknown) => boolean,
): Promise<T> {
  try {
    return await operation();
  } catch (first) {
    if (!isRetryable(first)) throw first;
    return operation();
  }
}
