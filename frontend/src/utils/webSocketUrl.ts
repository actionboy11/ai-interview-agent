export type BrowserLocation = Pick<Location, 'protocol' | 'host' | 'hostname'>;

export function resolveVoiceWebSocketUrl(
  sessionId: number,
  candidate?: string,
  browserLocation?: BrowserLocation,
): string {
  const currentLocation = browserLocation ?? window.location;
  const expectedPath = `/ws/voice-interview/${sessionId}`;
  const currentIsLocal = currentLocation.hostname === 'localhost'
    || currentLocation.hostname === '127.0.0.1';

  if (currentIsLocal && candidate) {
    try {
      const candidateUrl = new URL(candidate);
      const candidateIsLocal = candidateUrl.hostname === 'localhost'
        || candidateUrl.hostname === '127.0.0.1';
      if (candidateIsLocal && candidateUrl.pathname === expectedPath) {
        return candidateUrl.toString();
      }
    } catch {
      // Relative and malformed candidates are normalized to the current origin below.
    }
  }

  const protocol = currentLocation.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${currentLocation.host}${expectedPath}`;
}
