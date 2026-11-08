import type { ReactNode } from 'react'

export function Alert({ kind = 'error', children }: { kind?: 'error' | 'success' | 'info'; children: ReactNode }) {
  const style = kind === 'error' ? 'bg-coral/10 text-[#8a0022] ring-1 ring-coral/40'
    : kind === 'success' ? 'bg-lime/40 text-ink ring-1 ring-lime' : 'bg-grape/25 text-ink ring-1 ring-grape/60'
  return <div role={kind === 'error' ? 'alert' : 'status'} className={`rounded-2xl px-4 py-3 text-sm font-medium ${style}`}>{children}</div>
}

export function Field({ id, label, hint, children }: { id: string; label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="block text-sm font-semibold">{label}</label>
      {children}
      {hint && <p id={`${id}-hint`} className="text-xs text-neutral-600">{hint}</p>}
    </div>
  )
}

export function Chips({ items, empty }: { items: string[]; empty?: string }) {
  if (!items.length) return empty ? <span className="text-xs text-neutral-600">{empty}</span> : null
  return <ul className="flex flex-wrap gap-1.5" aria-label="tags">{items.map(i => <li key={i} className="chip">{i}</li>)}</ul>
}

export function Seats({ count, max, dark = false }: { count: number; max: number; dark?: boolean }) {
  return (
    <span className="inline-flex items-center gap-1" role="img" aria-label={`${count} of ${max} seats filled`}>
      {Array.from({ length: max }, (_, i) => (
        <span key={i} aria-hidden className={`h-3 w-3 rounded-full border ${i < count
          ? (dark ? 'border-lime bg-lime' : 'border-ink bg-ink') : (dark ? 'border-neutral-500 bg-transparent' : 'border-neutral-400 bg-white')}`} />
      ))}
      <span className="ml-1 text-sm font-semibold" aria-hidden>{count}/{max}</span>
    </span>
  )
}

export function Spinner({ label = 'Loading' }: { label?: string }) {
  return <p role="status" className="py-10 text-center text-neutral-600">{label}...</p>
}

const PALETTE = ['#d8f542', '#a68cf4', '#ffb4a2', '#9be3d0', '#ffd966', '#bcd4ff']
export function Avatar({ name, size = 40 }: { name: string; size?: number }) {
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map(w => w[0]!.toUpperCase()).join('')
  let h = 0
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return (
    <span aria-hidden className="inline-flex shrink-0 items-center justify-center rounded-full font-bold text-ink ring-2 ring-white"
      style={{ width: size, height: size, background: PALETTE[h % PALETTE.length], fontSize: Math.round(size * 0.36) }}>{initials}</span>
  )
}

export function AvatarStack({ names, max = 3 }: { names: string[]; max?: number }) {
  const shown = names.slice(0, max)
  const extra = names.length - shown.length
  return (
    <span className="flex items-center" aria-hidden>
      {shown.map((n, i) => <span key={n + i} className={i ? '-ml-2' : ''}><Avatar name={n} size={40} /></span>)}
      {extra > 0 && <span className="-ml-2 inline-flex h-10 items-center rounded-full bg-fog px-3 text-sm font-semibold ring-2 ring-white">{extra}+</span>}
    </span>
  )
}

export function Donut({ value, total, color = '#d8f542', track = 'rgba(255,255,255,.25)' }: { value: number; total: number; color?: string; track?: string }) {
  const r = 15, c = 2 * Math.PI * r, frac = total ? Math.min(1, value / total) : 0
  return (
    <svg width="40" height="40" viewBox="0 0 40 40" aria-hidden>
      <circle cx="20" cy="20" r={r} fill="none" stroke={track} strokeWidth="5" />
      <circle cx="20" cy="20" r={r} fill="none" stroke={color} strokeWidth="5" strokeLinecap="round"
        strokeDasharray={`${c * frac} ${c}`} transform="rotate(-90 20 20)" />
    </svg>
  )
}

const svgProps = { width: 20, height: 20, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true }
export const IconPlus = () => <svg {...svgProps}><path d="M12 5v14M5 12h14" /></svg>
export const IconArrow = () => <svg {...svgProps}><path d="M7 17 17 7M8 7h9v9" /></svg>
export const IconUsers = () => <svg {...svgProps}><circle cx="9" cy="8" r="3.2" /><path d="M3 20c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5M16 5.2a3.2 3.2 0 0 1 0 6M18 14.8c1.8.7 3 2.4 3 5.2" /></svg>
export const IconLogout = () => <svg {...svgProps}><path d="M10 5H6a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h4M15 8l4 4-4 4M19 12H9" /></svg>
export const IconFilter = () => <svg {...svgProps}><path d="M4 7h16M7 12h10M10 17h4" /></svg>
export const IconMail = () => <svg {...svgProps}><rect x="3" y="5" width="18" height="14" rx="3" /><path d="m4 7 8 6 8-6" /></svg>
export const IconBolt = () => <svg {...svgProps}><path d="M13 3 5 14h6l-1 7 8-11h-6z" /></svg>
export const Logo = () => <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden><path fill="currentColor" d="M12 2 14.6 9.4 22 12l-7.4 2.6L12 22l-2.6-7.4L2 12l7.4-2.6z" /></svg>

export const utcLabel = (min: number) => {
  const sign = min < 0 ? '-' : '+'
  const a = Math.abs(min)
  return `UTC${sign}${Math.floor(a / 60)}${a % 60 ? ':' + String(a % 60).padStart(2, '0') : ''}`
}

export const OFFSETS = [-720, -660, -600, -540, -480, -420, -360, -300, -240, -180, -120, -60, 0, 60, 120, 180, 240, 270, 300, 330, 345, 360, 420, 480, 540, 600, 660, 720, 780, 840]

export const parseTags = (s: string) => s.split(',').map(x => x.trim()).filter(Boolean)
