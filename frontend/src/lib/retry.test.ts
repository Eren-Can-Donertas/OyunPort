import { describe, expect, it } from 'vitest';
import { withSingleRetry } from './retry';
import { ApiError, isTransientError } from '../api/client';

describe('withSingleRetry (ödeme requestKey tekrar denemesi)', () => {
  it('ağ hatasında aynı işlemi tam bir kez tekrar dener', async () => {
    const keys: string[] = [];
    const requestKey = 'rk-1';
    let calls = 0;
    const result = await withSingleRetry(async () => {
      calls++;
      keys.push(requestKey);
      if (calls === 1) throw new ApiError(0, 'NETWORK_ERROR', 'ağ');
      return 'ok';
    }, isTransientError);
    expect(result).toBe('ok');
    expect(calls).toBe(2);
    expect(keys).toEqual(['rk-1', 'rk-1']);
  });

  it('ikinci ağ hatasından sonra vazgeçer', async () => {
    let calls = 0;
    await expect(
      withSingleRetry(async () => {
        calls++;
        throw new ApiError(0, 'NETWORK_ERROR', 'ağ');
      }, isTransientError),
    ).rejects.toBeInstanceOf(ApiError);
    expect(calls).toBe(2);
  });

  it('iş kuralı hatalarında (409/422) tekrar denemez', async () => {
    let calls = 0;
    await expect(
      withSingleRetry(async () => {
        calls++;
        throw new ApiError(409, 'ALREADY_OWNED', '[BR02] zaten sahipsiniz');
      }, isTransientError),
    ).rejects.toMatchObject({ status: 409, message: '[BR02] zaten sahipsiniz' });
    expect(calls).toBe(1);
  });

  it('ağ geçidi hatalarını (502/503/504) geçici sayar', () => {
    expect(isTransientError(new ApiError(502, 'HTTP_502', 'x'))).toBe(true);
    expect(isTransientError(new ApiError(504, 'HTTP_504', 'x'))).toBe(true);
    expect(isTransientError(new ApiError(500, 'INTERNAL_ERROR', 'x'))).toBe(false);
    expect(isTransientError(new Error('x'))).toBe(false);
  });
});
