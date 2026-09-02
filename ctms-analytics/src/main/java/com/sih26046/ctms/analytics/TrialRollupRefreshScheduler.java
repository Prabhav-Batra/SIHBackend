package com.sih26046.ctms.analytics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Times {@link TrialRollupRefresher}. Separate from it for the same reason {@code
 * DocumentScanScheduler} is separate from its worker: a test refreshing the view on data it
 * just wrote must not race a timer doing the same thing mid-assertion. Disabled in tests, which
 * call {@code TrialRollupRefresher.refresh()} directly.
 */
@Component
@ConditionalOnProperty(
        name = "ctms.analytics.rollup-refresh-scheduled",
        havingValue = "true",
        matchIfMissing = true)
public class TrialRollupRefreshScheduler {

    private final TrialRollupRefresher refresher;

    public TrialRollupRefreshScheduler(TrialRollupRefresher refresher) {
        this.refresher = refresher;
    }

    @Scheduled(fixedDelayString = "${ctms.analytics.rollup-refresh-interval:60s}")
    public void run() {
        refresher.refresh();
    }
}
