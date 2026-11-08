import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, ApiError, errorText, idempotent } from '../api'
import { useAuth } from '../auth'
import { Alert, Avatar, AvatarStack, Chips, IconArrow, Seats, Spinner, utcLabel } from '../components/ui'
import { useFocusHeading, useStream } from '../hooks'
import type { AuditEntry, InviteView, RequestView, SuggestionResponse, TeamDetail } from '../types'

const ACTIONS: Record<string, string> = {
  TEAM_CREATED: 'created the team', TEAM_UPDATED: 'updated the team', INVITE_CREATED: 'generated an invite code',
  MEMBER_JOINED: 'joined', MEMBER_LEFT: 'left the team', MEMBER_REMOVED: 'removed a member',
  LEADER_TRANSFERRED: 'passed leadership on', REQUEST_CREATED: 'asked to join', REQUEST_ACCEPTED: 'accepted a request',
  REQUEST_DECLINED: 'declined a request', TEAM_DELETED: 'deleted the team',
}

export default function TeamPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const nav = useNavigate()
  const [params] = useSearchParams()
  const [data, setData] = useState<TeamDetail | null>(null)
  const [requests, setRequests] = useState<RequestView[]>([])
  const [audit, setAudit] = useState<AuditEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [invite, setInvite] = useState<InviteView | null>(null)
  const [code, setCode] = useState(params.get('invite') ?? '')
  const [busy, setBusy] = useState(false)
  const [suggestions, setSuggestions] = useState<SuggestionResponse | null>(null)
  const h1 = useFocusHeading(id)

  const isMember = !!data?.members.some(m => m.userId === user?.id)
  const isLeader = data?.team.leaderId === user?.id

  const load = useCallback(async () => {
    try {
      const d = await api.get<TeamDetail>(`/api/teams/${id}`)
      setData(d); setError(null)
      if (d.members.some(m => m.userId === user?.id)) {
        api.get<AuditEntry[]>(`/api/teams/${id}/audit`).then(setAudit).catch(() => {})
        if (d.team.leaderId === user?.id) api.get<RequestView[]>(`/api/teams/${id}/requests`).then(setRequests).catch(() => {})
        else setRequests([])
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) { setData(null); setNotice('This team no longer exists.'); nav('/', { replace: true }) }
      else setError(errorText(e))
    }
  }, [id, user?.id, nav])

  useEffect(() => { load() }, [load])
  const live = useStream(id ? `/api/stream/teams/${id}` : null, load)

  async function run(fn: () => Promise<void>, ok?: string) {
    if (busy) return
    setBusy(true); setError(null); setNotice(null)
    try { await fn(); if (ok) setNotice(ok) } catch (e) { setError(errorText(e)); load() } finally { setBusy(false) }
  }

  const joinWithCode = (e: FormEvent) => { e.preventDefault(); run(async () => {
    const t = await idempotent(`join:${id}:${code.trim()}`, key => api.post<TeamDetail>(`/api/teams/${id}/join`, { inviteCode: code.trim() }, key))
    setData(t); setCode(''); load()
  }, 'You joined the team!') }

  const requestJoin = () => run(async () => { await idempotent(`request:${id}`, key => api.post(`/api/teams/${id}/requests`, {}, key)) }, 'Request sent. The leader will see it instantly.')

  const makeInvite = () => run(async () => setInvite(await api.post<InviteView>(`/api/teams/${id}/invites`)))

  // Optimistic: the request leaves the list immediately; restored with an explanation if the server refuses.
  async function decide(r: RequestView, status: 'ACCEPTED' | 'DECLINED') {
    const before = requests
    setRequests(rs => rs.filter(x => x.id !== r.id)); setError(null)
    try {
      await idempotent(`decide:${r.id}:${status}`, key => api.patch(`/api/requests/${r.id}`, { status }, key))
      load()
    } catch (e) {
      setRequests(before)
      setError(`${status === 'ACCEPTED' ? 'Accept' : 'Decline'} failed for ${r.displayName}: ${errorText(e)}`)
      load()
    }
  }

  const leave = () => { if (window.confirm('Leave this team?')) run(async () => { await api.del(`/api/teams/${id}/leave`); nav('/') }) }
  const remove = (uid: string, name: string) => { if (window.confirm(`Remove ${name} from the team?`)) run(async () => { await api.del(`/api/teams/${id}/members/${uid}`); load() }, `${name} was removed.`) }
  const loadSuggestions = () => run(async () => setSuggestions(await api.get<SuggestionResponse>(`/api/teams/${id}/suggestions?limit=8`)))

  if (!data) return error ? <main id="main"><Alert>{error}</Alert></main> : <Spinner label="Loading team" />
  const t = data.team
  const link = invite ? `${window.location.origin}/teams/${t.id}?invite=${invite.code}` : ''

  return (
    <main id="main" className="space-y-5">
      <p><Link to="/" className="btn-secondary min-h-[40px]">← Dashboard</Link></p>

      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="max-w-2xl space-y-3">
          <h1 ref={h1} tabIndex={-1} className="h-title">{t.name}</h1>
          {t.description && <p className="text-neutral-700">{t.description}</p>}
          <div className="flex flex-wrap items-center gap-3">
            <span className={`rounded-full px-4 py-1.5 text-sm font-bold ${t.status === 'READY' ? 'bg-lime text-ink' : 'bg-grape text-ink'}`}>{t.status === 'READY' ? 'Ready' : 'Needs more members'}</span>
            <span className="rounded-full bg-white px-4 py-1.5"><Seats count={t.memberCount} max={t.maxSize} /></span>
            <Chips items={t.lookingFor} empty="Open to anyone" />
          </div>
        </div>
        <div className="flex items-center gap-3">
          <AvatarStack names={data.members.map(m => m.displayName)} max={4} />
          <p aria-live="polite" className={`rounded-full px-4 py-2 text-sm font-semibold ${live ? 'bg-lime text-ink' : 'bg-fog text-neutral-700'}`}>{live ? '● Live' : '○ Reconnecting'}</p>
        </div>
      </header>

      <div aria-live="polite" className="space-y-2 empty:hidden">
        {error && <Alert>{error}</Alert>}
        {notice && <Alert kind="success">{notice}</Alert>}
      </div>

      <div className="grid gap-4 lg:grid-cols-[1fr_minmax(320px,420px)]">
        <div className="space-y-4">
          {!isMember && (
            <section className="card-dark space-y-4" aria-labelledby="join-h">
              <h2 id="join-h" className="text-3xl font-medium">Join this team</h2>
              {t.memberCount >= t.maxSize && <p className="rounded-2xl bg-white/10 p-3 text-sm">This team is full right now. A seat may open up, and this page updates live.</p>}
              <form onSubmit={joinWithCode} className="space-y-3">
                <div className="space-y-1">
                  <label htmlFor="code" className="block text-sm font-semibold">Invite code</label>
                  <input id="code" className="input" value={code} onChange={e => setCode(e.target.value)} autoComplete="off" spellCheck={false} aria-describedby="code-hint" />
                  <p id="code-hint" className="text-xs text-white/70">Have a code from the team leader? Paste it here.</p>
                </div>
                <button className="btn-lime" disabled={busy || code.trim().length < 16}>Join with code</button>
              </form>
              <button className="btn-secondary" disabled={busy} onClick={requestJoin}>Or ask the leader to let you in</button>
            </section>
          )}

          <section className="card" aria-labelledby="members-h">
            <h2 id="members-h" className="mb-4 text-2xl font-medium">Members <span className="chip align-middle">{data.members.length}</span></h2>
            <ul className="space-y-3">
              {data.members.map(m => (
                <li key={m.userId} className="flex flex-wrap items-center gap-3 rounded-2xl bg-canvas p-3">
                  <Avatar name={m.displayName} size={48} />
                  <div className="min-w-0 flex-1 space-y-1">
                    <p className="font-semibold">{m.displayName}{m.leader && <span className="ml-2 rounded-full bg-ink px-2 py-0.5 text-xs text-white">Leader</span>}{m.userId === user?.id && <span className="ml-2 text-xs text-neutral-600">(you)</span>}</p>
                    <Chips items={m.skills} />
                  </div>
                  {isLeader && !m.leader && <button className="btn-danger" disabled={busy} onClick={() => remove(m.userId, m.displayName)} aria-label={`Remove ${m.displayName}`}>Remove</button>}
                </li>
              ))}
            </ul>
            {isMember && <button className="btn-danger mt-4" disabled={busy} onClick={leave}>Leave team</button>}
          </section>

          {isMember && (
            <section className="card space-y-3" aria-labelledby="sug-h">
              <h2 id="sug-h" className="text-2xl font-medium">Suggested teammates</h2>
              <button className="btn-primary" disabled={busy} onClick={loadSuggestions}>{suggestions ? 'Refresh suggestions' : 'Find teammates'}</button>
              {suggestions && (
                <>
                  <p className="rounded-2xl bg-canvas p-3 text-xs text-neutral-700">{suggestions.explanation}</p>
                  <ol className="space-y-3">
                    {suggestions.suggestions.map(s => (
                      <li key={s.userId} className="rounded-2xl bg-canvas p-4">
                        <div className="flex items-center gap-3">
                          <Avatar name={s.displayName} size={44} />
                          <p className="min-w-0 flex-1 font-semibold">{s.displayName} <span className="text-xs font-normal text-neutral-600">{utcLabel(s.utcOffsetMin)}</span></p>
                          <p className="grid h-12 w-12 place-items-center rounded-full bg-lime text-lg font-extrabold" aria-label={`score ${s.score} out of 100`}>{Math.round(s.score)}</p>
                        </div>
                        <div className="mt-2"><Chips items={s.skills} /></div>
                        <p className="mt-2 text-xs font-medium text-neutral-700">Skills {Math.round(s.breakdown.skills * 100)}% · Time zone {Math.round(s.breakdown.timezone * 100)}% · Interests {Math.round(s.breakdown.interests * 100)}%</p>
                        <ul className="mt-1 list-disc pl-5 text-xs text-neutral-700">{s.reasons.map(r => <li key={r}>{r}</li>)}</ul>
                      </li>
                    ))}
                  </ol>
                </>
              )}
            </section>
          )}
        </div>

        <div className="space-y-4">
          {isLeader && (
            <>
              <section className="card-dark space-y-3" aria-labelledby="invite-h">
                <h2 id="invite-h" className="text-3xl font-medium">Invite someone</h2>
                <p className="text-sm text-white/75">Codes expire after 48 hours and only one is active at a time.</p>
                <button className="btn-lime" disabled={busy} onClick={makeInvite}>{invite ? 'Generate a new code' : 'Generate invite code'}</button>
                {invite && (
                  <div className="space-y-2 rounded-2xl bg-white/10 p-3 text-sm">
                    <p>Code (shown once): <code className="break-all font-mono font-bold text-lime">{invite.code}</code></p>
                    <p className="text-white/75">Expires {new Date(invite.expiresAt).toLocaleString()}</p>
                    <button className="btn-secondary" onClick={() => navigator.clipboard?.writeText(link).then(() => setNotice('Invite link copied.'))}>Copy invite link</button>
                  </div>
                )}
              </section>

              <section className="rounded-3xl bg-grape p-5" aria-labelledby="req-h">
                <h2 id="req-h" className="mb-3 text-2xl font-medium">Join requests <span className="rounded-full bg-ink px-2.5 py-0.5 align-middle text-sm text-white">{requests.length}</span></h2>
                {requests.length === 0 ? <p className="text-sm">No pending requests.</p> : (
                  <ul className="space-y-3">
                    {requests.map(r => (
                      <li key={r.id} className="space-y-2 rounded-2xl bg-white p-3">
                        <div className="flex items-center gap-3"><Avatar name={r.displayName} size={40} /><p className="font-semibold">{r.displayName}</p></div>
                        <Chips items={r.skills} />
                        {r.message && <p className="text-sm text-neutral-700">“{r.message}”</p>}
                        <div className="flex gap-2">
                          <button className="btn-primary" onClick={() => decide(r, 'ACCEPTED')} aria-label={`Accept ${r.displayName}`}>Accept</button>
                          <button className="btn-secondary" onClick={() => decide(r, 'DECLINED')} aria-label={`Decline ${r.displayName}`}>Decline</button>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </>
          )}

          {isMember && (
            <details className="card">
              <summary className="flex cursor-pointer list-none items-center justify-between text-2xl font-medium">Team history <span className="chip">{audit.length}</span></summary>
              <ol className="mt-4 space-y-3 text-sm">
                {audit.map(a => (
                  <li key={a.id} className="rounded-2xl bg-canvas p-3"><time dateTime={a.at} className="block text-xs text-neutral-600">{new Date(a.at).toLocaleString()}</time>
                    <strong>{a.actorName ?? 'Someone'}</strong> {ACTIONS[a.action] ?? a.action}{a.targetName && a.targetName !== a.actorName ? ` (${a.targetName})` : ''}</li>
                ))}
              </ol>
            </details>
          )}

          {!isMember && (
            <Link to="/arena" className="card-dark flex items-center justify-between"><span className="text-xl font-medium">See how joining stays race-proof</span><span className="grid h-12 w-12 place-items-center rounded-full bg-white text-ink"><IconArrow /></span></Link>
          )}
        </div>
      </div>
    </main>
  )
}
