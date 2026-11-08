import { useEffect, useState } from 'react'
import { api, ApiError, errorText } from '../api'
import { Alert, Spinner } from '../components/ui'
import { useFocusHeading } from '../hooks'
import type { ArenaMode, ArenaReport } from '../types'

const MODES: { id: ArenaMode; title: string; blurb: string }[] = [
  { id: 'SAFE', title: 'Safe (our design)', blurb: 'Lock the team row, check capacity, insert. The real production join path.' },
  { id: 'NO_LOCK', title: 'No lock', blurb: 'Check-then-act. Only the database CHECK constraint stands between you and overbooking.' },
  { id: 'NAIVE', title: 'Naive', blurb: 'No lock, no counter: count the rows, then insert. What most first drafts do.' },
]
const modeTitle = (m: ArenaMode) => MODES.find(x => x.id === m)?.title ?? m

export default function ArenaPage() {
  const h1 = useFocusHeading('arena')
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [mode, setMode] = useState<ArenaMode>('SAFE')
  const [contenders, setContenders] = useState(50)
  const [busy, setBusy] = useState<string | null>(null)
  const [results, setResults] = useState<Partial<Record<ArenaMode, ArenaReport>>>({})
  const [current, setCurrent] = useState<ArenaReport | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.get('/api/arena/status').then(() => setEnabled(true)).catch(e => setEnabled(e instanceof ApiError && e.status === 404 ? false : true))
  }, [])

  async function runOne(m: ArenaMode) {
    setBusy(`Racing ${contenders} people for the last seat: ${modeTitle(m)}...`)
    const r = await api.post<ArenaReport>('/api/arena/race', { mode: m, contenders, capacity: 5 })
    setResults(prev => ({ ...prev, [m]: r }))
    setCurrent(r)
  }

  async function run(all: boolean) {
    if (busy) return
    setError(null)
    try {
      if (all) { setResults({}); for (const m of MODES) await runOne(m.id) } else await runOne(mode)
    } catch (e) { setError(errorText(e)) } finally { setBusy(null) }
  }

  if (enabled === null) return <Spinner label="Loading arena" />
  if (!enabled) return (
    <main id="main" className="mx-auto max-w-2xl space-y-4">
      <h1 ref={h1} tabIndex={-1} className="h-title">Race Arena</h1>
      <Alert kind="info">The arena is switched off on this server. Start the backend with <code>ARENA_ENABLED=true</code> to enable it.</Alert>
    </main>
  )

  const compared = MODES.filter(m => results[m.id])

  return (
    <main id="main" className="mx-auto max-w-5xl space-y-4">
      <header className="card-dark space-y-3 p-6 sm:p-8">
        <h1 ref={h1} tabIndex={-1} className="text-4xl font-medium tracking-tight sm:text-6xl">Race Arena</h1>
        <p className="flex flex-wrap items-center gap-2 text-sm"><span className="rounded-full bg-lime px-3 py-1 font-bold text-ink">One seat left</span><span className="rounded-full bg-grape px-3 py-1 font-bold text-ink">Everyone clicks at once</span></p>
        <p className="max-w-2xl text-white/80">Watch what each design does with real PostgreSQL transactions. The arena only touches temporary rows and deletes them afterwards.</p>
      </header>

      <section className="card space-y-4" aria-labelledby="ctl-h">
        <h2 id="ctl-h" className="text-2xl font-medium">Set up the race</h2>
        <fieldset>
          <legend className="mb-2 text-sm font-semibold">Join implementation</legend>
          <div className="grid gap-2 sm:grid-cols-3">
            {MODES.map(m => (
              <label key={m.id} className={`flex cursor-pointer flex-col gap-1 rounded-2xl p-4 text-sm ${mode === m.id ? 'bg-ink text-white' : 'bg-canvas text-ink'}`}>
                <span className="flex items-center gap-2 font-semibold">
                  <input type="radio" name="mode" className="h-4 w-4 accent-[#d8f542]" checked={mode === m.id} onChange={() => setMode(m.id)} />{m.title}
                </span>
                <span className={mode === m.id ? 'text-white/80' : 'text-neutral-700'}>{m.blurb}</span>
              </label>
            ))}
          </div>
        </fieldset>
        <div className="max-w-xs space-y-1">
          <label htmlFor="n" className="block text-sm font-semibold">People racing: {contenders}</label>
          <input id="n" type="range" min={2} max={100} value={contenders} onChange={e => setContenders(Number(e.target.value))} className="w-full accent-ink" />
        </div>
        <div className="flex flex-wrap gap-2">
          <button className="btn-lime" disabled={!!busy} onClick={() => run(false)}>Run the race</button>
          <button className="btn-primary" disabled={!!busy} onClick={() => run(true)}>Compare all three designs</button>
        </div>
        <p role="status" aria-live="polite" className="min-h-[1.25rem] text-sm text-neutral-700">{busy}</p>
        {error && <Alert>{error}</Alert>}
      </section>

      {compared.length > 1 && (
        <section className="card overflow-x-auto" aria-labelledby="cmp-h">
          <h2 id="cmp-h" className="mb-3 text-lg font-semibold">Side by side</h2>
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Outcome of the same race under each join implementation</caption>
            <thead><tr className="border-b border-neutral-300">
              <th scope="col" className="py-2 pr-3">Design</th><th scope="col" className="pr-3">Got the seat</th>
              <th scope="col" className="pr-3">Cleanly rejected</th><th scope="col" className="pr-3">Raw DB errors</th>
              <th scope="col" className="pr-3">People / seats</th><th scope="col">Verdict</th>
            </tr></thead>
            <tbody>
              {compared.map(m => { const r = results[m.id]!; return (
                <tr key={m.id} className="border-b border-neutral-200">
                  <th scope="row" className="py-2 pr-3 font-semibold">{m.title}</th>
                  <td className="pr-3">{r.joined}</td><td className="pr-3">{r.rejected}</td><td className="pr-3">{r.errors}</td>
                  <td className="pr-3">{r.finalMembers} / {r.capacity}</td>
                  <td className={r.consistent ? 'font-bold text-ink' : 'font-bold text-[#b3002b]'}>{r.consistent ? '✓ Consistent' : '✗ Corrupted'}</td>
                </tr>) })}
            </tbody>
          </table>
        </section>
      )}

      {current && <Result r={current} />}
    </main>
  )
}

function Result({ r }: { r: ArenaReport }) {
  const dots = Math.max(r.capacity, r.finalMembers)
  return (
    <section className="space-y-4" aria-labelledby="res-h" aria-live="polite">
      <h2 id="res-h" className="text-2xl font-medium">Result: {modeTitle(r.mode)} <span className="text-sm font-normal text-neutral-600">({r.contenders} people, {r.totalMs} ms)</span></h2>

      <Alert kind={r.consistent ? 'success' : 'error'}><strong>{r.consistent ? '✓ ' : '✗ '}</strong>{r.verdict}</Alert>

      <div className="grid grid-cols-3 gap-3 text-center">
        <Tile n={r.joined} label="Got the seat" tone="bg-lime text-ink" />
        <Tile n={r.rejected} label="Cleanly rejected" tone="bg-white text-ink" />
        <Tile n={r.errors} label="Raw DB errors" tone="bg-grape text-ink" />
      </div>

      <div className="card">
        <p className="mb-2 text-sm font-semibold">The team after the race: {r.finalMembers} people for {r.capacity} seats</p>
        <ul className="flex flex-wrap gap-1.5" aria-label="Seats">
          {Array.from({ length: dots }, (_, i) => (
            <li key={i} className={`flex h-7 w-7 items-center justify-center rounded-full border text-xs font-bold ${
              i >= r.capacity ? 'border-coral bg-coral text-white' : i < r.finalMembers ? 'border-ink bg-ink text-white' : 'border-neutral-400 bg-white'}`}>
              <span className="sr-only">{i >= r.capacity ? 'seat that does not exist, occupied' : i < r.finalMembers ? 'taken seat' : 'free seat'}</span>
              <span aria-hidden>{i >= r.capacity ? '!' : ''}</span>
            </li>
          ))}
        </ul>
        {r.capacityViolations > 0 && <p className="mt-2 text-sm font-semibold text-[#b3002b]">{r.capacityViolations} people are sitting in seats that do not exist.</p>}
      </div>

      <div className="card">
        <h3 className="mb-2 text-sm font-semibold">Database audit</h3>
        <ul className="space-y-1 text-sm">
          {r.checks.map(c => (
            <li key={c.name} className={c.pass ? 'text-ink' : 'text-[#b3002b]'}>
              <span aria-hidden>{c.pass ? '✓' : '✗'}</span> <span className="sr-only">{c.pass ? 'Pass:' : 'Fail:'}</span> {c.name} <span className="text-neutral-600">({c.detail})</span>
            </li>
          ))}
        </ul>
      </div>

      <div className="card">
        <h3 className="mb-2 text-sm font-semibold">Finish order (fastest first)</h3>
        <ol className="grid grid-cols-2 gap-1.5 text-xs sm:grid-cols-4 md:grid-cols-5">
          {r.attempts.map(a => (
            <li key={a.contender} title={`${a.durationMs} ms`} className={`rounded-xl border px-2 py-1.5 ${
              a.outcome === 'JOINED' ? 'border-ink bg-lime text-ink' : a.outcome === 'ERROR' ? 'border-coral bg-coral/10 text-[#8a0022]' : 'border-neutral-300 bg-white text-neutral-700'}`}>
              <span aria-hidden>{a.outcome === 'JOINED' ? '✓' : a.outcome === 'ERROR' ? '!' : '✕'}</span> #{a.contender}{' '}
              <span className="font-semibold">{a.outcome === 'JOINED' ? 'joined' : a.outcome === 'ERROR' ? 'error' : 'full'}</span>
              <span className="block truncate text-[11px] opacity-80">{a.code} · {a.finishedAtMs} ms</span>
            </li>
          ))}
        </ol>
      </div>
    </section>
  )
}

function Tile({ n, label, tone }: { n: number; label: string; tone: string }) {
  return <div className={`rounded-3xl p-4 ${tone}`}><p className="text-3xl font-extrabold">{n}</p><p className="text-xs font-semibold">{label}</p></div>
}
