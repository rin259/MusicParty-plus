package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.UserSummary;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the receipt side of voting: a voter must be able to tell whether their
 * press registered, which used to be impossible.
 */
class VoteReceiptTest {
    private static UserSummary user(String token, boolean guest) {
        return new UserSummary(token, "s-" + token, token, guest);
    }

    @Test void aVoteIsReportedBackForTheSessionThatCastIt() {
        Set<String> votes = Set.of("alice");
        assertThat(VoteTally.votes(List.of(user("alice", false)), votes, "enq")).isEqualTo(1);
        assertThat(votes.contains("alice")).isTrue();
    }

    @Test void withdrawingRemovesTheVoteAndTheTally() {
        Set<String> votes = new java.util.HashSet<>(Set.of("alice"));
        votes.remove("alice");
        assertThat(VoteTally.votes(List.of(user("alice", false)), votes, "enq")).isZero();
    }

    @Test void aGuestIsStillNotCountedEvenIfTheirTokenWasRecorded() {
        // Guests can be recorded by older builds; the tally must ignore them.
        Set<String> votes = Set.of("guest-1");
        assertThat(VoteTally.votes(List.of(user("guest-1", true)), votes, "enq")).isZero();
    }
}
