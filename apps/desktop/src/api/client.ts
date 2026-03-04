import type {ApiResponse} from '@sqlcopilot/shared-contracts';

const BASE_URL = 'http://localhost:18080';

export async function getApi<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`);
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  const json = (await res.json()) as ApiResponse<T>;
  if (json.code !== 0) {
    throw new Error(json.message);
  }
  return json.data;
}

interface PostApiOptions {
  signal?: AbortSignal;
}

export async function postApi<T>(path: string, payload: unknown, options?: PostApiOptions): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
    signal: options?.signal,
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  const json = (await res.json()) as ApiResponse<T>;
  if (json.code !== 0) {
    throw new Error(json.message);
  }
  return json.data;
}
