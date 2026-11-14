const BASE: string = import.meta.env.VITE_API_BASE ?? ''
console.log('[API CONFIG]', {
  base: BASE,
  configured: Boolean(BASE),
})

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public fields?: Record<string, string>) {
    super(message)
  }
}

// Storage can be unavailable (private modes, blocked cookies): never let that blank the whole app.
const store = {
  get: (k: string): string | null => { try { return localStorage.getItem(k) } catch { return null } },
  set: (k: string, v: string) => { try { localStorage.setItem(k, v) } catch { /* in-memory token only */ } },
  del: (k: string) => { try { localStorage.removeItem(k) } catch { /* ignore */ } },
}
let token: string | null = store.get('token')
let onUnauthorized: () => void = () => {}
export const setToken = (t: string | null) => {
  token = t
  if (t) store.set('token', t); else store.del('token')
}
export const getToken = () => token
export const setUnauthorizedHandler = (fn: () => void) => { onUnauthorized = fn }
export const streamUrl = (path: string) => BASE + path

async function request<T>(method: string, path: string, body?: unknown, idemKey?: string): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers['Authorization'] = `Bearer ${token}`
  if (idemKey) headers['Idempotency-Key'] = idemKey
  let res: Response
  try {
    res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Network problem - please check your connection and try again.')
  }
  if (res.status === 204) return undefined as T
  const text = await res.text()
  const data = text ? JSON.parse(text) : null
  if (!res.ok) {
    if (res.status === 401 && token && !path.startsWith('/api/auth/')) onUnauthorized()
    throw new ApiError(res.status, data?.code ?? 'ERROR', data?.message ?? res.statusText, data?.fields)
  }
  return data as T
}

export const api = {
  get: <T,>(path: string) => request<T>('GET', path),
  post: <T,>(path: string, body?: unknown, idemKey?: string) => request<T>('POST', path, body ?? {}, idemKey),
  patch: <T,>(path: string, body: unknown, idemKey?: string) => request<T>('PATCH', path, body, idemKey),
  put: <T,>(path: string, body: unknown) => request<T>('PUT', path, body),
  del: <T,>(path: string) => request<T>('DELETE', path),
}

/**
 * One Idempotency-Key per logical action. The key survives network errors / 5xx (so a retry or a
 * double click is replayed by the server, never executed twice) and is dropped once the server
 * gave a definitive answer.
 */
const pendingKeys = new Map<string, string>()
export async function idempotent<T>(op: string, fn: (key: string) => Promise<T>): Promise<T> {
  let key = pendingKeys.get(op)
  if (!key) { key = crypto.randomUUID(); pendingKeys.set(op, key) }
  try {
    const out = await fn(key)
    pendingKeys.delete(op)
    return out
  } catch (e) {
    if (e instanceof ApiError && e.status > 0 && e.status < 500) pendingKeys.delete(op)
    throw e
  }
}

export const errorText = (e: unknown): string =>
  e instanceof ApiError
    ? e.fields ? `${e.message}: ${Object.entries(e.fields).map(([k, v]) => `${k} ${v}`).join('; ')}` : e.message
    : 'Something went wrong. Please try again.'
