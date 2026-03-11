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

interface PostSseApiOptions<T> extends PostApiOptions {
  onEvent: (event: {event: string; data: T}) => void | Promise<void>;
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

export async function postSseApi<T>(path: string, payload: unknown, options: PostSseApiOptions<T>): Promise<void> {
  const res = await fetch(`${BASE_URL}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(payload),
    signal: options.signal,
  });
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}`);
  }
  if (!res.body) {
    throw new Error('SSE response body is empty');
  }
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let lastYieldAt = now();
  while (true) {
    const {done, value} = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), {stream: !done});
    let boundary = findSseBoundary(buffer);
    while (boundary.index >= 0) {
      const rawEvent = buffer.slice(0, boundary.index);
      buffer = buffer.slice(boundary.index + boundary.length);
      const parsed = parseSseEvent<T>(rawEvent);
      if (parsed) {
        await options.onEvent(parsed);
        if (now() - lastYieldAt >= 16) {
          await yieldToBrowser();
          lastYieldAt = now();
        }
      }
      boundary = findSseBoundary(buffer);
    }
    if (done) {
      const parsed = parseSseEvent<T>(buffer);
      if (parsed) {
        await options.onEvent(parsed);
      }
      break;
    }
  }
}

function now() {
  if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
    return performance.now();
  }
  return Date.now();
}

function yieldToBrowser() {
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function'
    && typeof document !== 'undefined' && document.visibilityState === 'visible') {
    return new Promise<void>((resolve) => {
      window.requestAnimationFrame(() => resolve());
    });
  }
  return new Promise<void>((resolve) => {
    window.setTimeout(resolve, 0);
  });
}

function findSseBoundary(buffer: string) {
  const lf = buffer.indexOf('\n\n');
  const crlf = buffer.indexOf('\r\n\r\n');
  if (lf < 0 && crlf < 0) {
    return {index: -1, length: 0};
  }
  if (lf >= 0 && (crlf < 0 || lf < crlf)) {
    return {index: lf, length: 2};
  }
  return {index: crlf, length: 4};
}

function parseSseEvent<T>(chunk: string): {event: string; data: T} | null {
  const normalized = chunk.replace(/\r/g, '').trim();
  if (!normalized) {
    return null;
  }
  let event = 'message';
  const dataLines: string[] = [];
  normalized.split('\n').forEach((line) => {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim() || event;
      return;
    }
    if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trim());
    }
  });
  if (!dataLines.length) {
    return null;
  }
  return {
    event,
    data: JSON.parse(dataLines.join('\n')) as T,
  };
}
