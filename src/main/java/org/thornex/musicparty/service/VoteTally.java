package org.thornex.musicparty.service;

import org.thornex.musicparty.dto.UserSummary;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Vote-skip arithmetic, kept out of the player so it can be checked directly.
 *
 * <p>The room roster is the single source of truth: the percentage a vote needs to
 * pass and the vote tally shown in the UI must be derived from the same snapshot,
 * otherwise a disconnect between two reads shows "6 online, 5 eligible".</p>
 *
 * <p>Guests are excluded because they have no stable identity across reconnects, and
 * the enqueuer is excluded because their own skip is an immediate one.</p>
 */
final class VoteTally {
    private VoteTally() {}

    /** People who count towards the threshold: online, named, not the enqueuer. */
    static int eligible(List<UserSummary> onlineUsers, String enqueuerToken) {
        if (onlineUsers == null) return 0;
        return (int) onlineUsers.stream()
                .filter(user -> !user.isGuest())
                .filter(user -> enqueuerToken == null || !enqueuerToken.equals(user.token()))
                .count();
    }

    /** Votes that still count: the voter is in the room, named, and not the enqueuer. */
    static int votes(List<UserSummary> onlineUsers, Set<String> votes, String enqueuerToken) {
        if (onlineUsers == null || votes == null || votes.isEmpty()) return 0;
        Set<String> eligibleTokens = onlineUsers.stream()
                .filter(user -> !user.isGuest())
                .filter(user -> enqueuerToken == null || !enqueuerToken.equals(user.token()))
                .map(UserSummary::token)
                .collect(Collectors.toSet());
        return (int) votes.stream().filter(eligibleTokens::contains).count();
    }

    static boolean passes(int votes, int eligible, double threshold) {
        if (eligible <= 0) return false;
        double needed = Math.max(0.0, Math.min(1.0, threshold)) * eligible;
        return votes >= needed;
    }
}
