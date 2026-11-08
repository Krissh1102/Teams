import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth'
import { api, errorText } from '../api'
import { Alert, Avatar, Field, OFFSETS, parseTags, utcLabel } from '../components/ui'
import { useFocusHeading } from '../hooks'
import type { Profile } from '../types'

export default function ProfilePage() {
  const { user, setUser } = useAuth()
  const h1 = useFocusHeading('profile')
  const [name, setName] = useState(user?.displayName ?? '')
  const [tz, setTz] = useState(String(user?.utcOffsetMin ?? 0))
  const [skills, setSkills] = useState(user?.skills.join(', ') ?? '')
  const [interests, setInterests] = useState(user?.interests.join(', ') ?? '')
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [busy, setBusy] = useState(false)

  async function save(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setMsg(null)
    try {
      setUser(await api.put<Profile>('/api/users/me', { displayName: name, utcOffsetMin: Number(tz), skills: parseTags(skills), interests: parseTags(interests) }))
      setMsg({ ok: true, text: 'Profile saved. Teammate suggestions will use it.' })
    } catch (err) { setMsg({ ok: false, text: errorText(err) }) } finally { setBusy(false) }
  }

  return (
    <main id="main" className="mx-auto max-w-2xl space-y-4">
      <div className="flex items-center gap-4"><Avatar name={user?.displayName ?? '?'} size={72} /><h1 ref={h1} tabIndex={-1} className="h-title">Your profile</h1></div>
      <form onSubmit={save} className="card space-y-4">
        {msg && <Alert kind={msg.ok ? 'success' : 'error'}>{msg.text}</Alert>}
        <Field id="pname" label="Display name"><input id="pname" className="input" required minLength={2} maxLength={80} value={name} onChange={e => setName(e.target.value)} /></Field>
        <Field id="ptz" label="Time zone"><select id="ptz" className="input" value={tz} onChange={e => setTz(e.target.value)}>{OFFSETS.map(o => <option key={o} value={o}>{utcLabel(o)}</option>)}</select></Field>
        <Field id="pskills" label="Skills" hint="Comma separated"><input id="pskills" className="input" value={skills} onChange={e => setSkills(e.target.value)} /></Field>
        <Field id="pint" label="Interests" hint="Comma separated"><input id="pint" className="input" value={interests} onChange={e => setInterests(e.target.value)} /></Field>
        <button className="btn-primary" disabled={busy}>{busy ? 'Saving...' : 'Save profile'}</button>
      </form>
    </main>
  )
}
