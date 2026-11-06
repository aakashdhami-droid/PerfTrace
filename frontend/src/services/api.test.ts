import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError } from './api';

const storage = new Map<string, string>();
const dispatchEvent = vi.fn();

vi.stubGlobal('localStorage', {
  getItem: (key: string) => storage.get(key) ?? null,
  setItem: (key: string, value: string) => { storage.set(key, value); },
  removeItem: (key: string) => { storage.delete(key); },
  clear: () => { storage.clear(); },
});

vi.stubGlobal('window', { dispatchEvent });

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('api client', () => {
  beforeEach(() => {
    storage.clear();
    dispatchEvent.mockClear();
    vi.unstubAllGlobals();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => { storage.set(key, value); },
      removeItem: (key: string) => { storage.delete(key); },
      clear: () => { storage.clear(); },
    });
    vi.stubGlobal('window', { dispatchEvent });
  });

  it('persists the session on login and notifies listeners', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      token: 'jwt-token',
      id: 7,
      username: 'demo',
      email: 'demo@example.com',
    })));

    const result = await api.login('demo', 'secret');

    expect(result.token).toBe('jwt-token');
    expect(storage.get('perftrace_token')).toBe('jwt-token');
    expect(api.isAuthenticated()).toBe(true);
    expect(api.getCurrentUser()).toEqual({ id: 7, username: 'demo', email: 'demo@example.com' });
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
  });

  it('clears the session on logout', () => {
    storage.set('perftrace_token', 'jwt-token');
    storage.set('perftrace_user', '{"id":1}');

    api.logout();

    expect(storage.size).toBe(0);
    expect(api.isAuthenticated()).toBe(false);
    expect(api.getCurrentUser()).toBeNull();
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
  });

  it('clears credentials and throws ApiError when the API returns 401', async () => {
    storage.set('perftrace_token', 'expired-token');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      message: 'Authentication is required to access this resource.',
    }, 401)));

    await expect(api.listRuns()).rejects.toMatchObject({
      name: 'ApiError',
      status: 401,
      message: 'Authentication is required to access this resource.',
    });

    expect(storage.has('perftrace_token')).toBe(false);
    expect(dispatchEvent).toHaveBeenCalledTimes(1);
  });

  it('uses a text response body as the error message for non-JSON failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('Upstream unavailable', {
      status: 503,
      headers: { 'content-type': 'text/plain' },
    })));

    await expect(api.getRun(3)).rejects.toEqual(
      expect.objectContaining({ status: 503, message: 'Upstream unavailable' })
    );
  });

  it('falls back to the status message when an error body is empty', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 500 })));

    const error = await api.getRun(3).catch((e) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(500);
    expect(error.message).toBe('Request failed with status 500');
  });

  it('attaches the bearer token and multipart body when uploading', async () => {
    storage.set('perftrace_token', 'jwt-token');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ id: 11, status: 'COMPLETED' }));
    vi.stubGlobal('fetch', fetchMock);

    const file = new File(['sensor,value'], 'hwinfo.csv', { type: 'text/csv' });
    await api.uploadRun(file, 'baseline run');

    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/runs/upload');
    expect(init.method).toBe('POST');
    expect(init.headers).toEqual({ Authorization: 'Bearer jwt-token' });
    const form = init.body as FormData;
    expect(form.get('file')).toBe(file);
    expect(form.get('description')).toBe('baseline run');
  });

  it('sends a declared workload type but omits the UNSPECIFIED default', async () => {
    storage.set('perftrace_token', 'jwt-token');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ runId: 1, status: 'COMPLETED' }));
    vi.stubGlobal('fetch', fetchMock);

    const file = new File(['sensor,value'], 'hwinfo.csv', { type: 'text/csv' });
    await api.uploadRun(file, undefined, 'GAMING');

    const gamingForm = fetchMock.mock.calls[0][1].body as FormData;
    expect(gamingForm.get('workloadType')).toBe('GAMING');
    expect(gamingForm.get('description')).toBeNull();

    await api.uploadRun(file, undefined, 'UNSPECIFIED');

    const unspecifiedForm = fetchMock.mock.calls[1][1].body as FormData;
    expect(unspecifiedForm.get('workloadType')).toBeNull();
  });

  it('reports a generic upload failure when the server returns an empty error', async () => {
    storage.set('perftrace_token', 'jwt-token');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 413 })));

    await expect(api.uploadRun(new File(['x'], 'big.csv'))).rejects.toMatchObject({
      status: 413,
      message: 'File upload failed',
    });
  });

  it('requests downsampled samples with the configured point limit', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    await api.getSamples(42, 250);

    expect(fetchMock.mock.calls[0][0]).toBe('/api/runs/42/samples?maxPoints=250');
  });

  it('does not treat malformed stored user JSON as a logged-in user', () => {
    storage.set('perftrace_user', '{not-json');

    expect(api.getCurrentUser()).toBeNull();
  });
});
