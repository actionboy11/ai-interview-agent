export function proposalActions(
  status: string,
  version: number,
  currentSession: boolean,
  busy: boolean,
  evidenceLoading: boolean,
  evidenceError: boolean,
  evidenceCount: number,
  lowEvidenceConfirmed: boolean,
): { accept: boolean; reject: boolean } {
  const reject = status === 'PROPOSED' && Number.isSafeInteger(version) && version >= 0 && currentSession && !busy;
  const accept = reject && !evidenceLoading && !evidenceError && evidenceCount > 0
    && (evidenceCount >= 3 || lowEvidenceConfirmed);
  return { accept, reject };
}
