export const ROUTES = {
  home: '/history',
  login: '/login',
  inviteRegister: '/register-by-invite',
  adminUsers: '/admin/users',
  abilityProfile: '/ability-profile',
  interviewDetail: (sessionId: string) => `/interviews/${encodeURIComponent(sessionId)}`,
  resumeUpload: '/upload',
  knowledgebaseUpload: '/knowledgebase/upload',
} as const;

