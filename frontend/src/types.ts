export interface Profile { id: string; email: string; displayName: string; utcOffsetMin: number; skills: string[]; interests: string[] }
export interface Hackathon { id: string; name: string; minTeamSize: number; maxTeamSize: number }
export interface TeamSummary {
  id: string; hackathonId: string; name: string; description?: string; lookingFor: string[]
  maxSize: number; memberCount: number; status: 'FORMING' | 'READY'; leaderId: string; leaderName: string; createdAt: string
}
export interface Member { userId: string; displayName: string; skills: string[]; joinedAt: string; leader: boolean }
export interface TeamDetail { team: TeamSummary; members: Member[] }
export interface BoardPage { items: TeamSummary[]; page: number; size: number; hasMore: boolean }
export interface RequestView { id: string; userId: string; displayName: string; skills: string[]; message?: string; status: string; createdAt: string }
export interface AuditEntry { id: number; action: string; actorName?: string; targetName?: string; details?: string; at: string }
export interface Suggestion {
  userId: string; displayName: string; skills: string[]; utcOffsetMin: number; score: number
  breakdown: { skills: number; timezone: number; interests: number }; reasons: string[]
}
export interface SuggestionResponse { weights: Record<string, number>; explanation: string; suggestions: Suggestion[] }
export interface InviteView { code: string; expiresAt: string }

export type ArenaMode = 'SAFE' | 'NO_LOCK' | 'NAIVE'
export interface ArenaAttempt { contender: number; outcome: 'JOINED' | 'REJECTED' | 'ERROR'; code: string; durationMs: number; finishedAtMs: number }
export interface ArenaCheck { name: string; pass: boolean; detail: string }
export interface ArenaReport {
  mode: ArenaMode; capacity: number; initialMembers: number; contenders: number
  joined: number; rejected: number; errors: number
  finalMembers: number; memberCounter: number; duplicates: number; capacityViolations: number
  totalMs: number; consistent: boolean; verdict: string; checks: ArenaCheck[]; attempts: ArenaAttempt[]
}
