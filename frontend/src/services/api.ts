import {
  AuthResponse,
  AnalysisRun,
  DetectedSegment,
  TelemetrySample,
  RunComparison,
  UploadResponse,
  User,
} from '../types';

const API_BASE = '/api';

export class ApiError extends Error {
  status: number;
  data: any;

  constructor(status: number, message: string, data?: any) {
    super(message);
    this.status = status;
    this.data = data;
    this.name = 'ApiError';
  }
}

function getAuthHeader(): Record<string, string> {
  const token = localStorage.getItem('perftrace_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const headers = {
    ...getAuthHeader(),
    ...(options.headers || {}),
  };

  const response = await fetch(`${API_BASE}${endpoint}`, {
    ...options,
    headers,
  });

  if (response.status === 204) {
    return {} as T;
  }

  let body: any;
  const contentType = response.headers.get('content-type');
  if (contentType && contentType.includes('application/json')) {
    body = await response.json();
  } else {
    body = await response.text();
  }

  if (!response.ok) {
    const errorMsg = (typeof body === 'object' && body?.message)
      ? body.message
      : (typeof body === 'string' && body.length > 0 ? body : `Request failed with status ${response.status}`);

    if (response.status === 401) {
      localStorage.removeItem('perftrace_token');
      localStorage.removeItem('perftrace_user');
      window.dispatchEvent(new Event('perftrace_auth_change'));
    }

    throw new ApiError(response.status, errorMsg, body);
  }

  return body as T;
}

export const api = {
  // Auth
  async register(username: string, email: string, password: string): Promise<AuthResponse> {
    const res = await request<AuthResponse>('/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, email, password }),
    });
    localStorage.setItem('perftrace_token', res.token);
    localStorage.setItem('perftrace_user', JSON.stringify({ id: res.id, username: res.username, email: res.email }));
    window.dispatchEvent(new Event('perftrace_auth_change'));
    return res;
  },

  async login(username: string, password: string): Promise<AuthResponse> {
    const res = await request<AuthResponse>('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    localStorage.setItem('perftrace_token', res.token);
    localStorage.setItem('perftrace_user', JSON.stringify({ id: res.id, username: res.username, email: res.email }));
    window.dispatchEvent(new Event('perftrace_auth_change'));
    return res;
  },

  async getMe(): Promise<User> {
    return request<User>('/auth/me');
  },

  logout(): void {
    localStorage.removeItem('perftrace_token');
    localStorage.removeItem('perftrace_user');
    window.dispatchEvent(new Event('perftrace_auth_change'));
  },

  getCurrentUser(): { id: number; username: string; email: string } | null {
    const raw = localStorage.getItem('perftrace_user');
    if (!raw) return null;
    try {
      return JSON.parse(raw);
    } catch {
      return null;
    }
  },

  isAuthenticated(): boolean {
    return !!localStorage.getItem('perftrace_token');
  },

  // Runs
  async uploadRun(file: File, description?: string): Promise<UploadResponse> {
    const formData = new FormData();
    formData.append('file', file);
    if (description) {
      formData.append('description', description);
    }

    const headers = getAuthHeader();
    const response = await fetch(`${API_BASE}/runs/upload`, {
      method: 'POST',
      headers,
      body: formData,
    });

    const body = await response.json();
    if (!response.ok) {
      throw new ApiError(response.status, body?.message || 'File upload failed', body);
    }
    return body;
  },

  async listRuns(page = 0, size = 20): Promise<{ content: AnalysisRun[]; totalElements: number; totalPages: number }> {
    return request<{ content: AnalysisRun[]; totalElements: number; totalPages: number }>(`/runs?page=${page}&size=${size}`);
  },

  async getRun(id: number): Promise<AnalysisRun> {
    return request<AnalysisRun>(`/runs/${id}`);
  },

  async getSegments(id: number): Promise<DetectedSegment[]> {
    return request<DetectedSegment[]>(`/runs/${id}/segments`);
  },

  async getSamples(id: number, maxPoints = 500): Promise<TelemetrySample[]> {
    return request<TelemetrySample[]>(`/runs/${id}/samples?maxPoints=${maxPoints}`);
  },

  async compareRuns(idA: number, idB: number): Promise<RunComparison> {
    return request<RunComparison>(`/runs/compare?a=${idA}&b=${idB}`);
  },

  async deleteRun(id: number): Promise<void> {
    await request<void>(`/runs/${id}`, { method: 'DELETE' });
  },
};
