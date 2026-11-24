package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.UserSummary;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VoteTallyTest {
    private static UserSummary user(String token, String name, boolean guest) {
        return new UserSummary(token, "s-" + token, name, guest);
    }

    @Test void theEnqueuerIsNotPartOfTheDenominator() {
        List<UserSummary> online = List.of(
                user("enq", "点歌的人", false),
                user("a", "甲", false),
                user("b", "乙", false));

        // Three people in the room, one is the enqueuer: two can vote.
        assertThat(VoteTally.eligible(online, "enq")).isEqualTo(2);
    }

    @Test void guestsCountForNeitherSide() {
        List<UserSummary> online = List.of(
                user("a", "甲", false),
                user("g1", "游客", true),
                user("g2", "游客", true));

        assertThat(VoteTally.eligible(online, "enq")).isEqualTo(1);
        assertThat(VoteTally.votes(online, Set.of("g1", "g2", "a"), "enq")).isEqualTo(1);
    }

    @Test void aVoteStopsCountingWhenTheVoterLeaves() {
        List<UserSummary> online = List.of(user("a", "甲", false), user("b", "乙", false));
        Set<String> votes = Set.of("a", "b", "gone");

        assertThat(VoteTally.votes(online, votes, "enq")).isEqualTo(2);

        // Someone closes their tab: the roster shrinks, so must the tally.
        assertThat(VoteTally.votes(List.of(user("a", "甲", false)), votes, "enq")).isEqualTo(1);
    }

    @Test void theEnqueuersOwnVoteIsNeverCounted() {
        List<UserSummary> online = List.of(user("enq", "点歌的人", false), user("a", "甲", false));

        assertThat(VoteTally.votes(online, Set.of("enq", "a"), "enq")).isEqualTo(1);
    }

    @Test void theTallyAndDenominatorComeFromTheSameRoster() {
        // The regression this guards: reading the roster twice let a disconnect in
        // between show "3 online" next to a denominator of 2 for the same frame.
        List<UserSummary> snapshot = List.of(
                user("a", "甲", false),
                user("b", "乙", false),
                user("c", "丙", false));

        int eligible = VoteTally.eligible(snapshot, "nobody");
        int votes = VoteTally.votes(snapshot, Set.of("a", "b"), "nobody");

        assertThat(eligible).isEqualTo(3);
        assertThat(votes).isEqualTo(2);
        assertThat(votes).isLessThanOrEqualTo(eligible);
    }

    @Test void thresholdIsInclusiveAtTheBoundary() {
        assertThat(VoteTally.passes(2, 4, 0.5)).isTrue();
        assertThat(VoteTally.passes(1, 4, 0.5)).isFalse();
        assertThat(VoteTally.passes(3, 3, 1.0)).isTrue();
        assertThat(VoteTally.passes(2, 3, 1.0)).isFalse();
    }

    @Test void anEmptyRoomNeverPassesEvenWithStaleVotes() {
        assertThat(VoteTally.passes(5, 0, 0.5)).isFalse();
        assertThat(VoteTally.passes(0, 0, 0.5)).isFalse();
    }
}
