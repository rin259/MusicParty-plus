import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isVoteActive, skipButtonLabel, voteBadge, voteCountdown } from './voteUi.js';

const base = { enabled: true, canVote: true, hasVoted: false, waitSeconds: 0, votes: 0, eligible: 3, locked: false };

test('a user who has voted sees an active toggle, not a dead button', () => {
  assert.equal(isVoteActive({ ...base, hasVoted: true }), true);
  assert.equal(isVoteActive({ ...base, hasVoted: false }), false);
});

test('guests and the enqueuer never get an active vote toggle', () => {
  assert.equal(isVoteActive({ ...base, hasVoted: true, canVote: false }), false);
  assert.equal(isVoteActive({ ...base, hasVoted: true, enabled: false }), false);
});

test('the title explains voting, then how to withdraw', () => {
  assert.equal(skipButtonLabel(base), '投票切歌 0/3');
  assert.equal(skipButtonLabel({ ...base, hasVoted: true }), '已投票 0/3 · 再点一次撤票');
});

test('the countdown wins over the tally while voting is still blocked', () => {
  assert.equal(skipButtonLabel({ ...base, waitSeconds: 12 }), '12s 后可投票');
  assert.equal(voteCountdown({ ...base, waitSeconds: 12 }), '12s');
});

test('a lock is reported before anything else', () => {
  assert.equal(skipButtonLabel({ ...base, locked: true }), '切歌功能已被锁定');
});

test('the badge shows both halves so the threshold is legible', () => {
  assert.equal(voteBadge({ ...base, votes: 2 }), '2/3');
});

test('no badge on zero votes, which would just be a stray zero', () => {
  assert.equal(voteBadge(base), null);
  assert.equal(voteBadge({ ...base, votes: 2, canVote: false }), null);
  assert.equal(voteBadge({ ...base, votes: 2, enabled: false }), null);
});

test('a long wait reads as minutes, not a raw second count', () => {
  assert.equal(voteCountdown({ ...base, waitSeconds: 547 }), '9:07');
  assert.equal(voteCountdown({ ...base, waitSeconds: 60 }), '1:00');
  assert.equal(voteCountdown({ ...base, waitSeconds: 59 }), '59s');
});

test('no countdown when voting is open or unavailable', () => {
  assert.equal(voteCountdown(base), null);
  assert.equal(voteCountdown({ ...base, waitSeconds: 9, canVote: false }), null);
  assert.equal(voteCountdown({ ...base, waitSeconds: 9, enabled: false }), null);
});
