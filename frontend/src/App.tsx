import { Link, NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import AuthPage from './pages/AuthPage'
import BoardPage from './pages/BoardPage'
import ArenaPage from './pages/ArenaPage'
import ProfilePage from './pages/ProfilePage'
import TeamPage from './pages/TeamPage'
import { Avatar, IconLogout, Logo, Spinner } from './components/ui'

const NAV = [{ to: '/', label: 'Dashboard', end: true }, { to: '/arena', label: 'Race Arena', end: false }, { to: '/profile', label: 'Profile', end: false }]

export default function App() {
  const { user, loading, logout } = useAuth()
  if (loading) return <Spinner label="Loading" />
  return (
    <div className="min-h-screen sm:p-6 lg:p-8">
      <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded-full focus:bg-ink focus:px-4 focus:py-2 focus:font-semibold focus:text-white">Skip to content</a>
      <div className="mx-auto max-w-[1320px] bg-canvas p-4 sm:rounded-[2.5rem] sm:p-8 sm:shadow-[0_40px_90px_-40px_rgba(0,0,0,.35)]">
        <header className="mb-6 flex flex-wrap items-center gap-3">
          <Link to="/" className="inline-flex h-12 items-center gap-2 rounded-full bg-lime pl-1.5 pr-5 text-lg font-extrabold tracking-tight sm:h-14">
            <span className="grid h-9 w-9 place-items-center rounded-full bg-ink text-lime sm:h-11 sm:w-11"><Logo /></span>SquadUp
          </Link>
          {user && (
            <>
              <nav aria-label="Main" className="order-last flex w-full gap-2 overflow-x-auto pb-1 sm:order-none sm:w-auto sm:pb-0">
                {NAV.map(n => (
                  <NavLink key={n.to} to={n.to} end={n.end}
                    className={({ isActive }) => `pill-nav ${isActive ? 'bg-ink text-white' : 'bg-[#e6e6e6] text-ink hover:bg-[#dadada]'}`}>{n.label}</NavLink>
                ))}
              </nav>
              <div className="ml-auto flex items-center gap-2">
                <Link to="/profile" aria-label={`Profile: ${user.displayName}`} className="rounded-full"><Avatar name={user.displayName} size={48} /></Link>
                <button onClick={logout} aria-label="Log out" className="grid h-12 w-12 place-items-center rounded-full bg-[#e6e6e6] hover:bg-[#dadada] sm:h-14 sm:w-14"><IconLogout /></button>
              </div>
            </>
          )}
        </header>
        <Routes>
          <Route path="/login" element={user ? <Navigate to="/" replace /> : <AuthPage />} />
          <Route path="/" element={user ? <BoardPage /> : <Navigate to="/login" replace />} />
          <Route path="/teams/:id" element={user ? <TeamPage /> : <Navigate to="/login" replace />} />
          <Route path="/arena" element={user ? <ArenaPage /> : <Navigate to="/login" replace />} />
          <Route path="/profile" element={user ? <ProfilePage /> : <Navigate to="/login" replace />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </div>
    </div>
  )
}
