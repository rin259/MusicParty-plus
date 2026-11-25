// Vote-skip presentation rules, tested without a DOM.
// These decide what the skip button shows, which is exactly where "I clicked and
// nothing happened" used to come from.

/** Whether the skip button should render as an active (accent) vote toggle. */
export function isVoteActive({ enabled, canVote, hasVoted }) {
  return Boolean(enabled && canVote && hasVoted);
}

/** Label for the skip button title / accessible name. */
export function skipButtonLabel({ locked, enabled, canVote, waitSeconds, votes, eligible, hasVoted }) {
  if (locked) return '切歌功能已被锁定';
  if (!enabled) return 'Next';
  if (!canVote) return '强制切歌';
  if (waitSeconds > 0) return `${waitSeconds}s 后可投票`;
  const tally = `${votes}/${eligible}`;
  return hasVoted ? `已投票 ${tally} · 再点一次撤票` : `投票切歌 ${tally}`;
}

/** Badge text, or null when a badge would be noise. */
export function voteBadge({ enabled, canVote, votes, eligible }) {
  if (!enabled || !canVote || votes <= 0) return null;
  return `${votes}/${eligible}`;
}

/**
 * Countdown shown while voting is still blocked. Touch users have no hover, so
 * this must be visible without interaction. Long waits become mm:ss because a
 * bare "547s" reads like a glitch rather than a number.
 */
export function voteCountdown({ enabled, canVote, waitSeconds }) {
  if (!enabled || !canVote || waitSeconds <= 0) return null;
  if (waitSeconds < 60) return `${waitSeconds}s`;
  const minutes = Math.floor(waitSeconds / 60);
  const seconds = waitSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}
