import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'
import { errorText } from '../api'
import { Alert, Field, OFFSETS, parseTags, utcLabel } from '../components/ui'
import { useFocusHeading } from '../hooks'

export default function AuthPage() {
  const { login, register } = useAuth()
  const nav = useNavigate()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const h1 = useFocusHeading(mode)
  const [f, setF] = useState({ email: '', password: '', displayName: '', tz: String(-new Date().getTimezoneOffset()), skills: '', interests: '' })
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    setBusy(true); setError(null)
    try {
      if (mode === 'login') await login(f.email, f.password)
      else await register({ email: f.email, password: f.password, displayName: f.displayName, utcOffsetMin: Number(f.tz), skills: parseTags(f.skills), interests: parseTags(f.interests) })
      nav('/')
    } catch (err) { setError(errorText(err)) } finally { setBusy(false) }
  }

  const tzOptions = OFFSETS.includes(Number(f.tz)) ? OFFSETS : [...OFFSETS, Number(f.tz)].sort((a, b) => a - b)

  return (
    <main id="main" className="grid gap-4 lg:grid-cols-[1.2fr_1fr]">
      <section className="space-y-4">
        <h1 ref={h1} tabIndex={-1} className="h-title">{mode === 'login' ? 'Welcome back' : 'Build your squad'}</h1>
        <p className="max-w-lg text-neutral-600">Find teammates, join a hackathon team and watch the board update live. Built so that exactly one person gets the last seat.</p>
        <div className="grid gap-3 sm:grid-cols-3" aria-hidden>
          <div className="rounded-3xl bg-lime p-5"><p className="text-4xl font-medium">1</p><p className="text-sm font-semibold">winner for the last seat</p></div>
          <div className="rounded-3xl bg-grape p-5"><p className="text-4xl font-medium">Live</p><p className="text-sm font-semibold">board, no refresh</p></div>
          <div className="rounded-3xl bg-ink p-5 text-white"><p className="text-4xl font-medium">0</p><p className="text-sm font-semibold text-white/80">duplicate joins by design</p></div>
        </div>
      </section>
      <section>
        <form onSubmit={submit} className="card space-y-4" noValidate aria-label={mode === 'login' ? 'Log in' : 'Sign up'}>
          {error && <Alert>{error}</Alert>}
          {mode === 'register' && (
            <Field id="name" label="Display name"><input id="name" className="input" required minLength={2} maxLength={80} autoComplete="name" value={f.displayName} onChange={set('displayName')} /></Field>
          )}
          <Field id="email" label="Email"><input id="email" type="email" className="input" required autoComplete="email" value={f.email} onChange={set('email')} /></Field>
          <Field id="password" label="Password" hint={mode === 'register' ? 'At least 8 characters.' : undefined}>
            <input id="password" type="password" className="input" required minLength={8} maxLength={72} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} value={f.password} onChange={set('password')} />
          </Field>
          {mode === 'register' && (
            <>
              <Field id="tz" label="Your time zone" hint="Used to suggest teammates who work at the same hours.">
                <select id="tz" className="input" value={f.tz} onChange={set('tz')}>{tzOptions.map(o => <option key={o} value={o}>{utcLabel(o)}</option>)}</select>
              </Field>
              <Field id="skills" label="Your skills" hint="Comma separated, e.g. java, react, ui"><input id="skills" className="input" value={f.skills} onChange={set('skills')} /></Field>
              <Field id="interests" label="Interests" hint="Comma separated, e.g. fintech, climate"><input id="interests" className="input" value={f.interests} onChange={set('interests')} /></Field>
            </>
          )}
          <button className="btn-primary w-full" disabled={busy}>{busy ? 'Please wait...' : mode === 'login' ? 'Log in' : 'Sign up'}</button>
          <p className="text-center text-sm">
            {mode === 'login' ? 'New here?' : 'Already registered?'}{' '}
            <button type="button" className="font-semibold underline" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null) }}>
              {mode === 'login' ? 'Create an account' : 'Log in'}
            </button>
          </p>
        </form>
      </section>
    </main>
  )
}
