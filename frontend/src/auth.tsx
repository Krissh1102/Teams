import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, getToken, setToken, setUnauthorizedHandler } from './api'
import type { Profile } from './types'

interface AuthState {
  user: Profile | null
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  register: (b: { email: string; password: string; displayName: string; utcOffsetMin: number; skills: string[]; interests: string[] }) => Promise<void>
  logout: () => void
  setUser: (p: Profile) => void
}

const Ctx = createContext<AuthState | null>(null)
export const useAuth = () => {
  const c = useContext(Ctx)
  if (!c) throw new Error('useAuth outside provider')
  return c
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Profile | null>(null)
  const [loading, setLoading] = useState<boolean>(!!getToken())

  const logout = useCallback(() => { setToken(null); setUser(null) }, [])

  useEffect(() => {
    setUnauthorizedHandler(logout)
    if (getToken()) api.get<Profile>('/api/users/me').then(setUser).catch(logout).finally(() => setLoading(false))
  }, [logout])

  const value = useMemo<AuthState>(() => ({
    user, loading, logout, setUser,
    login: async (email, password) => {
      const r = await api.post<{ token: string; user: Profile }>('/api/auth/login', { email, password })
      setToken(r.token); setUser(r.user)
    },
    register: async (b) => {
      const r = await api.post<{ token: string; user: Profile }>('/api/auth/register', b)
      setToken(r.token); setUser(r.user)
    },
  }), [user, loading, logout])

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}
