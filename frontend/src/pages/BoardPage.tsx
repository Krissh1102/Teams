import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, errorText, idempotent } from '../api'
import { useAuth } from '../auth'
import { Alert, Avatar, AvatarStack, Chips, Donut, Field, IconArrow, IconFilter, IconPlus, IconUsers, parseTags, Spinner } from '../components/ui'
import { useFocusHeading, useStream } from '../hooks'
import type { BoardPage as Board, Hackathon, TeamDetail, TeamSummary } from '../types'

const TILE = ['bg-lime', 'bg-grape', 'bg-ink text-white']
const pctOf = (t: TeamSummary) => Math.round((t.memberCount / t.maxSize) * 100)

export default function BoardPage() {
  const { user } = useAuth()
  const nav = useNavigate()
  const h1 = useFocusHeading('board')
  const [hack, setHack] = useState<Hackathon | null>(null)
  const [board, setBoard] = useState<Board | null>(null)
  const [mine, setMine] = useState<TeamSummary | null>(null)
  const [squad, setSquad] = useState<TeamDetail | null>(null)
  const [q, setQ] = useState('')
  const [skill, setSkill] = useState('')
  const [applied, setApplied] = useState({ q: '', skill: '' })
  const [filtersOpen, setFiltersOpen] = useState(false)
  const [page, setPage] = useState(0)
  const [requested, setRequested] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  useEffect(() => { api.get<Hackathon[]>('/api/hackathons').then(h => setHack(h[0] ?? null)).catch(e => setError(errorText(e))) }, [])

  const load = useCallback(async () => {
    if (!hack) return
    try {
      const params = new URLSearchParams({ page: String(page), size: '12' })
      if (applied.q) params.set('q', applied.q)
      if (applied.skill) params.set('skill', applied.skill)
      const [b, m] = await Promise.all([
        api.get<Board>(`/api/hackathons/${hack.id}/board?${params}`),
        api.get<TeamSummary>(`/api/hackathons/${hack.id}/my-team`).then(t => t ?? null),
      ])
      setBoard(b); setMine(m ?? null); setError(null)
      if (m) api.get<TeamDetail>(`/api/teams/${m.id}`).then(setSquad).catch(() => {}); else setSquad(null)
    } catch (e) { setError(errorText(e)) }
  }, [hack, page, applied])

  useEffect(() => { load() }, [load])
  const live = useStream(hack ? `/api/stream/hackathons/${hack.id}` : null, load)

  // Optimistic UI: show "requested" instantly, roll back and explain if the server says no.
  async function requestToJoin(t: TeamSummary) {
    setRequested(s => new Set(s).add(t.id)); setError(null)
    try {
      await idempotent(`request:${t.id}`, key => api.post(`/api/teams/${t.id}/requests`, {}, key))
    } catch (e) {
      setRequested(s => { const n = new Set(s); n.delete(t.id); return n })
      setError(`Could not request "${t.name}": ${errorText(e)}`)
      load()
    }
  }

  function search(e: FormEvent) { e.preventDefault(); setPage(0); setApplied({ q: q.trim(), skill: skill.trim().toLowerCase() }) }

  if (!hack) return error ? <main id="main"><Alert>{error}</Alert></main> : <Spinner label="Loading hackathon" />

  const items = board?.items ?? []
  const canJoin = (t: TeamSummary) => !mine && t.leaderId !== user?.id
  const recommended = items.filter(t => t.leaderId !== user?.id && t.id !== mine?.id).slice(0, 3)
  const chart = items.slice(0, 6)
  const best = chart.reduce((m, t) => Math.max(m, pctOf(t)), 0)
  const bestIdx = chart.findIndex(t => pctOf(t) === best)
  const avg = items.length ? items.reduce((s, t) => s + t.memberCount, 0) / items.length : 0
  const ready = items.filter(t => t.status === 'READY').length
  const forming = items.length - ready

  return (
    <main id="main">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div className="max-w-2xl">
          <h1 ref={h1} tabIndex={-1} className="h-title">{hack.name}</h1>
          <p className="mt-3 text-sm text-neutral-600">Teams of {hack.minTeamSize} to {hack.maxTeamSize}. Find the squad with an open seat, or build your own. Everything here updates live.</p>
        </div>
        <div className="flex items-center gap-3">
          <AvatarStack names={items.map(t => t.leaderName)} max={3} />
          <p aria-live="polite" className={`rounded-full px-4 py-2 text-sm font-semibold ${live ? 'bg-lime text-ink' : 'bg-fog text-neutral-700'}`}>{live ? '● Live' : '○ Reconnecting'}</p>
          {!mine && <button className="btn-primary" aria-expanded={creating} onClick={() => setCreating(c => !c)}><IconPlus />Create team</button>}
        </div>
      </div>

      {error && <div className="mb-4"><Alert>{error}</Alert></div>}
      {creating && !mine && <div className="mb-4"><CreateTeam hack={hack} onCancel={() => setCreating(false)} onCreated={id => nav(`/teams/${id}`)} /></div>}

      <div className="grid gap-4 lg:grid-cols-[minmax(250px,300px)_minmax(250px,300px)_1fr]">
        {/* ---------- left column ---------- */}
        <div className="order-2 space-y-4 lg:order-1">
          <section className="card space-y-4" aria-labelledby="your-h">
            <h2 id="your-h" className="text-2xl font-medium">Your team</h2>
            {mine ? (
              <Link to={`/teams/${mine.id}`} className="flex items-center gap-3 rounded-2xl bg-grape p-3">
                <span className="grid h-12 w-12 shrink-0 place-items-center rounded-xl bg-ink text-lg font-bold text-white">{mine.name.slice(0, 1).toUpperCase()}</span>
                <span className="min-w-0"><span className="block truncate font-semibold">{mine.name}</span><span className="text-sm">{mine.memberCount}/{mine.maxSize} members · {mine.status === 'READY' ? 'Ready' : 'Forming'}</span></span>
              </Link>
            ) : <p className="text-sm text-neutral-600">You have not joined a team yet.</p>}
            {!mine && (
              <button onClick={() => setCreating(c => !c)} aria-expanded={creating}
                className="flex w-full items-center justify-between rounded-3xl bg-ink p-3 text-left text-white">
                <span className="flex items-center gap-3">
                  <span className="grid h-11 w-11 place-items-center rounded-xl border-2 border-dashed border-white/70"><IconPlus /></span>
                  <span className="text-lg font-medium">Create team</span>
                </span>
              </button>
            )}
          </section>

          <section className="card" aria-labelledby="rec-h">
            <div className="mb-3 flex items-center justify-between"><h2 id="rec-h" className="text-lg font-semibold">Recommended teams</h2><a href="#teams" className="text-xs font-semibold text-neutral-600 underline">See all</a></div>
            {recommended.length === 0 ? <p className="text-sm text-neutral-600">Nothing to recommend yet.</p> : (
              <ul className="space-y-3">
                {recommended.map((t, i) => (
                  <li key={t.id} className="flex items-center gap-3">
                    <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl font-bold ${TILE[i % TILE.length]}`}>{t.name.slice(0, 1).toUpperCase()}</span>
                    <span className="min-w-0 flex-1"><Link to={`/teams/${t.id}`} className="block truncate text-sm font-semibold underline decoration-transparent hover:decoration-ink">{t.name}</Link><span className="block truncate text-xs text-neutral-600">{t.leaderName}</span></span>
                    {canJoin(t) && <button className="btn-secondary min-h-[36px] px-4" disabled={requested.has(t.id)} onClick={() => requestToJoin(t)} aria-label={`Request to join ${t.name}`}>{requested.has(t.id) ? 'Sent' : 'Join'}</button>}
                  </li>
                ))}
              </ul>
            )}
          </section>

          {squad && (
            <section className="card" aria-labelledby="squad-h">
              <div className="mb-3 flex items-center justify-between"><h2 id="squad-h" className="text-2xl font-medium">Squad</h2><span className="chip inline-flex items-center gap-1"><IconUsers />{squad.members.length}</span></div>
              <ul className="space-y-3">
                {squad.members.slice(0, 4).map(m => (
                  <li key={m.userId} className="flex items-center gap-3">
                    <Avatar name={m.displayName} size={44} />
                    <span className="min-w-0 flex-1"><span className="block truncate font-semibold">{m.displayName}</span><span className="text-xs text-neutral-600">{m.leader ? 'Team leader' : 'Member'}</span></span>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </div>

        {/* ---------- middle column ---------- */}
        <div className="order-3 space-y-4 lg:order-2">
          <section className="card" aria-labelledby="chart-h">
            <div className="mb-4 flex items-center justify-between gap-2">
              <h2 id="chart-h" className="text-lg font-semibold">Seats filled</h2>
              <span className="rounded-full bg-fog px-3 py-1 text-xs font-semibold">Open teams</span>
            </div>
            <p className="mb-3"><span className="rounded-full bg-ink px-3 py-1 text-xs font-bold text-white">{items.length}</span> <span className="text-xs text-neutral-600">teams with a free seat</span></p>
            {chart.length === 0 ? <p className="py-10 text-center text-sm text-neutral-600">No data yet.</p> : (
              <div className="flex h-44 items-end gap-2" role="img" aria-label={`Seat fill of the first ${chart.length} teams: ${chart.map(t => `${t.name} ${pctOf(t)} percent`).join(', ')}`}>
                {chart.map((t, i) => (
                  <div key={t.id} className="flex h-full min-w-0 flex-1 flex-col justify-end">
                    <div className={`relative rounded-2xl ${i === bestIdx ? 'bg-ink' : 'hatch'}`} style={{ height: `${Math.max(pctOf(t), 14)}%` }}>
                      <span className={`absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2 rounded-full bg-lime px-2 py-0.5 text-[11px] font-bold text-ink`}>{pctOf(t)}%</span>
                    </div>
                    <span className="mt-2 truncate text-center text-xs text-neutral-600" aria-hidden>{t.name.split(' ')[0]!.slice(0, 7)}</span>
                  </div>
                ))}
              </div>
            )}
            <a href="#teams" className="btn-primary mt-4 w-full">Browse teams</a>
          </section>

          <section className="flex items-center justify-between rounded-3xl bg-grape p-5" aria-label="Average team size">
            <div><p className="text-4xl font-medium tracking-tight">{avg.toFixed(1)}</p><p className="text-sm font-medium">Average team size</p></div>
            <span className="grid h-14 w-14 place-items-center rounded-full bg-ink text-white"><IconUsers /></span>
          </section>

          <section className="card-dark space-y-4" aria-labelledby="arena-h">
            <h2 id="arena-h" className="text-3xl font-medium leading-tight">Race Arena</h2>
            <p className="flex items-center gap-2 text-sm"><span className="rounded-full bg-lime px-3 py-1 font-bold text-ink">One seat</span><span className="text-white/80">50 people, same instant</span></p>
            <div className="flex items-center justify-between">
              <AvatarStack names={['Ada L', 'Bo K', 'Cy M', 'Di N']} max={3} />
              <Link to="/arena" aria-label="Open the Race Arena" className="grid h-14 w-14 place-items-center rounded-full bg-white text-ink"><IconArrow /></Link>
            </div>
          </section>
        </div>

        {/* ---------- right column ---------- */}
        <section id="teams" className="card order-1 scroll-mt-4 space-y-4 lg:order-3" aria-labelledby="teams-h">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 id="teams-h" className="text-3xl font-medium">Looking for teammates</h2>
            <button className="btn-secondary" aria-expanded={filtersOpen} aria-controls="filters" onClick={() => setFiltersOpen(o => !o)}><IconFilter />Filter{(applied.q || applied.skill) ? ' (on)' : ''}</button>
          </div>

          {filtersOpen && (
            <form id="filters" role="search" onSubmit={search} className="grid gap-3 rounded-2xl bg-canvas p-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
              <Field id="q" label="Team name"><input id="q" className="input" value={q} onChange={e => setQ(e.target.value)} /></Field>
              <Field id="skill" label="Needs skill"><input id="skill" className="input" placeholder="e.g. react" value={skill} onChange={e => setSkill(e.target.value)} /></Field>
              <button className="btn-primary">Apply</button>
            </form>
          )}

          {!board ? <Spinner label="Loading teams" /> : items.length === 0 ? (
            <p className="rounded-2xl bg-canvas p-8 text-center text-neutral-600">No teams with an open seat match. Be the first to create one!</p>
          ) : (
            <ul className="space-y-3" aria-label="Teams looking for teammates">
              {items.map(t => {
                const pct = pctOf(t)
                const tone = t.id === mine?.id ? 'bg-grape text-ink' : t.status === 'READY' ? 'bg-lime text-ink' : 'bg-ink text-white'
                return (
                  <li key={t.id} className="grid items-center gap-2 sm:grid-cols-[1fr_auto]">
                    <div className="hatch rounded-2xl p-1">
                      <Link to={`/teams/${t.id}`} className={`flex min-h-[56px] items-center gap-3 rounded-[0.9rem] p-2 pr-4 ${tone}`} style={{ width: `${Math.max(pct, 62)}%`, minWidth: '15rem' }}>
                        <span className="rounded-lg bg-white px-2 py-1 text-sm font-bold text-ink">{pct}%</span>
                        <span className="min-w-0 flex-1"><span className="block truncate font-semibold">{t.name}</span><span className="block truncate text-xs opacity-80">{t.memberCount}/{t.maxSize} · led by {t.leaderName}{t.leaderId === user?.id ? ' (you)' : ''}</span></span>
                      </Link>
                      <div className="px-2 pb-1 pt-1.5"><Chips items={t.lookingFor} empty="Open to anyone" /></div>
                    </div>
                    {canJoin(t) && (
                      <button className="btn-secondary" disabled={requested.has(t.id)} onClick={() => requestToJoin(t)} aria-label={`Request to join ${t.name}`}>
                        {requested.has(t.id) ? 'Requested ✓' : <><IconPlus />Request</>}
                      </button>
                    )}
                  </li>
                )
              })}
            </ul>
          )}

          {board && (page > 0 || board.hasMore) && (
            <nav className="flex items-center justify-between" aria-label="Pagination">
              <button className="btn-secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</button>
              <span className="text-sm font-medium" aria-current="page">Page {page + 1}</span>
              <button className="btn-secondary" disabled={!board.hasMore} onClick={() => setPage(p => p + 1)}>Next</button>
            </nav>
          )}

          <ul className="grid gap-2 sm:grid-cols-3" aria-label="Summary of this page">
            <Summary dark value={items.length} total={Math.max(items.length, 1)} label="Open teams" sub="on this page" />
            <Summary value={ready} total={Math.max(items.length, 1)} label="Ready" sub="at minimum size" color="#a68cf4" />
            <Summary value={forming} total={Math.max(items.length, 1)} label="Forming" sub="need members" color="#ff3b5c" />
          </ul>
        </section>
      </div>
    </main>
  )
}

function Summary({ value, total, label, sub, dark = false, color }: { value: number; total: number; label: string; sub: string; dark?: boolean; color?: string }) {
  return (
    <li className={`flex items-center gap-3 rounded-full px-4 py-2 ${dark ? 'bg-ink text-white' : 'bg-fog text-ink'}`}>
      <Donut value={value} total={total} color={dark ? '#d8f542' : color} track={dark ? 'rgba(255,255,255,.25)' : 'rgba(17,17,17,.12)'} />
      <span><span className="block text-sm font-bold leading-tight">{value} {label}</span><span className={`block text-xs ${dark ? 'text-white/70' : 'text-neutral-600'}`}>{sub}</span></span>
    </li>
  )
}

function CreateTeam({ hack, onCreated, onCancel }: { hack: Hackathon; onCreated: (id: string) => void; onCancel: () => void }) {
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [lookingFor, setLookingFor] = useState('')
  const [maxSize, setMaxSize] = useState(String(hack.maxTeamSize))
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return                       // double-click guard; the Idempotency-Key covers retries
    setBusy(true); setError(null)
    const body = { hackathonId: hack.id, name: name.trim(), description: description.trim() || undefined, lookingFor: parseTags(lookingFor), maxSize: Number(maxSize) }
    try {
      const t = await idempotent(`create-team:${JSON.stringify(body)}`, key => api.post<TeamDetail>('/api/teams', body, key))
      onCreated(t.team.id)
    } catch (err) { setError(errorText(err)) } finally { setBusy(false) }
  }

  const sizes = Array.from({ length: hack.maxTeamSize - hack.minTeamSize + 1 }, (_, i) => hack.minTeamSize + i)
  return (
    <form onSubmit={submit} className="card grid gap-4 md:grid-cols-2" aria-label="Create a team">
      <h2 className="text-2xl font-medium md:col-span-2">Create a team</h2>
      {error && <div className="md:col-span-2"><Alert>{error}</Alert></div>}
      <Field id="tname" label="Team name"><input id="tname" className="input" required minLength={2} maxLength={80} value={name} onChange={e => setName(e.target.value)} /></Field>
      <Field id="tsize" label="Maximum team size"><select id="tsize" className="input" value={maxSize} onChange={e => setMaxSize(e.target.value)}>{sizes.map(s => <option key={s}>{s}</option>)}</select></Field>
      <Field id="tdesc" label="What are you building?"><textarea id="tdesc" className="input" rows={2} maxLength={500} value={description} onChange={e => setDescription(e.target.value)} /></Field>
      <Field id="tlook" label="Skills you are looking for" hint="Comma separated, e.g. react, ui, ml"><input id="tlook" className="input" value={lookingFor} onChange={e => setLookingFor(e.target.value)} /></Field>
      <div className="flex gap-2 md:col-span-2"><button className="btn-primary" disabled={busy}>{busy ? 'Creating...' : 'Create team'}</button><button type="button" className="btn-secondary" onClick={onCancel}>Cancel</button></div>
    </form>
  )
}
