import { useEffect, useRef, useState } from 'react'
import { streamUrl } from './api'

/**
 * Subscribes to a server-sent-events topic. Events are notifications only; on each one (debounced)
 * we re-fetch over the authenticated REST API. After a reconnect we also re-fetch, so a dropped
 * connection can never leave the screen stale. Returns whether the live connection is up.
 */
export function useStream(path: string | null, onChange: () => void): boolean {
  const cb = useRef(onChange)
  cb.current = onChange
  const [live, setLive] = useState(false)
  useEffect(() => {
    if (!path) return
    const es = new EventSource(streamUrl(path))
    let timer: number | undefined
    let first = true
    es.addEventListener('ready', () => { setLive(true); if (!first) cb.current(); first = false })
    es.addEventListener('change', () => { window.clearTimeout(timer); timer = window.setTimeout(() => cb.current(), 150) })
    es.onerror = () => setLive(false)
    return () => { window.clearTimeout(timer); es.close(); setLive(false) }
  }, [path])
  return live
}

/** Moves focus to the page heading on navigation so keyboard and screen-reader users land in the right place. */
export function useFocusHeading(dep: unknown) {
  const ref = useRef<HTMLHeadingElement>(null)
  useEffect(() => { ref.current?.focus() }, [dep])
  return ref
}
