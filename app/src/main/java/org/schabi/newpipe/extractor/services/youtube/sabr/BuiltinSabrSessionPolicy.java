package org.schabi.newpipe.extractor.services.youtube.sabr;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

/** Bundled protocol behavior used when no verified JavaScript policy is active. */
public final class BuiltinSabrSessionPolicy implements SabrSessionPolicy {
    /**
     * LOCAL ADDITION. Consecutive protection-boundary responses, used to enforce the server's
     * stated {@code maxRetries}. Deliberately not {@link State#getPoTokenRefreshes()} — the host
     * validates that upstream's counter is untouched on control responses.
     */
    private int protectedRounds;

    @Nonnull
    @Override
    public Result evaluate(@Nonnull final State state, @Nonnull final Event event) {
        if (event instanceof RequestEvent) {
            return Result.request(state, state.getRequestNumber() == 0
                            ? ActionType.SEND_INITIAL_REQUEST : ActionType.SEND_FOLLOW_UP_REQUEST,
                    ((RequestEvent) event).getProposedBody());
        }
        final ControlResponseEvent control = (ControlResponseEvent) event;
        final SabrDecodedResponse response = control.getResponse();
        final List<Action> actions = new ArrayList<>();
        State next = state;
        actions.add(new Action(ActionType.APPLY_RESPONSE_STATE));
        final String redirectUrl = response.getRedirectUrl();
        if (redirectUrl != null && !redirectUrl.isEmpty()) {
            actions.add(new Action(ActionType.APPLY_REDIRECT));
            next = new State(state.getRequestNumber(), state.getRedirectCount() + 1,
                    state.getPoTokenRefreshes(), state.getReloads());
        }
        if (response.getSabrErrorDetails() != null) {
            actions.add(new Action(ActionType.FAIL_SABR_ERROR));
            return Result.control(next, actions, new ControlDecision(0, redirectUrl,
                    response.getSabrErrorDetails().summarize()),
                    SabrResponseStatePatch.builtin(response));
        }
        if (response.isReloadRequested()) {
            actions.add(new Action(ActionType.TRY_RELOAD));
            return Result.control(next, actions, new ControlDecision(0, redirectUrl, null),
                    SabrResponseStatePatch.builtin(response));
        }
        if (response.isProtectionBoundaryNoMediaResponse()) {
            // LOCAL ADDITION: honour the retry budget the server states alongside the protection
            // status (StreamProtectionStatus field 2, e.g. "status=3, maxRetries=10"). Upstream
            // decodes maxRetries but nothing consumes it, so a session that keeps getting status=3
            // re-mints the PO token and retries forever. Observed in the field: ~35 protected
            // rounds over 90s with ~8 pointless re-mints against a stated budget of 10, until the
            // app-level buffering watchdog finally tore the player down and rebuilt it — which is
            // what actually recovered playback. Reloading at the budget gets us there ~80s sooner.
            //
            // The count lives in a policy field, NOT in State.poTokenRefreshes: the host's
            // validateResult() requires next.getPoTokenRefreshes() == state.getPoTokenRefreshes()
            // on every control response unless RESET_RECOVERY_BUDGETS fires, so incrementing it
            // here threw "Invalid SABR recovery state transition" and killed the pump thread.
            // Upstream owns that counter; this one is ours.
            protectedRounds++;
            final int budget = response.getStreamProtectionMaxRetries();
            if (budget > 0 && protectedRounds > budget) {
                protectedRounds = 0;
                actions.add(new Action(ActionType.TRY_RELOAD));
                // Backoff MUST be 0 here, exactly as upstream's isReloadRequested() branch does.
                // validateResult() requires seen(SLEEP_BACKOFF) == (backoff > 0 && honor) and
                // seen(DEFER_BACKOFF) == (backoff > 0 && !honor); an early return that carries a
                // nonzero backoff without adding one of those actions throws "SABR Host
                // action/event mismatch". Passing the server's 2000ms through here did exactly
                // that and killed the pump thread. We are tearing the session down anyway, so
                // there is nothing to back off from.
                return Result.control(next, actions, new ControlDecision(0, redirectUrl, null),
                        SabrResponseStatePatch.builtin(response));
            }
            actions.add(new Action(control.getMode() == ControlMode.FETCH_SEGMENT
                    ? ActionType.REQUIRE_PO_TOKEN : ActionType.REFRESH_PO_TOKEN));
        }
        if (control.getMode() == ControlMode.PUMP && control.getSegmentCount() > 0) {
            next = state.resetRecoveryBudgets();
            protectedRounds = 0; // LOCAL: media flowed again, so the protected streak is over
            actions.add(new Action(ActionType.RESET_RECOVERY_BUDGETS));
        }
        final int backoff = Math.max(0, response.getBackoffTimeMs());
        if (backoff > 0) {
            actions.add(new Action(control.shouldHonorBackoff()
                    ? ActionType.SLEEP_BACKOFF : ActionType.DEFER_BACKOFF));
        } else if (!control.shouldHonorBackoff()) {
            actions.add(new Action(ActionType.CLEAR_DEMAND_BACKOFF));
        }
        actions.add(new Action(control.getMode() == ControlMode.FETCH_SEGMENT
                && response.isProtectionBoundaryNoMediaResponse()
                ? ActionType.RETRY : ActionType.CONTINUE));
        return Result.control(next, actions, new ControlDecision(backoff, redirectUrl, null),
                SabrResponseStatePatch.builtin(response));
    }
}
