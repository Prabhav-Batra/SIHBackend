package com.sih26046.ctms.documents;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the orphan sweep on a timer (§16.7).
 *
 * <p>Separate from {@link DocumentOrphanSweepWorker} for the same reason {@link
 * DocumentScanScheduler} is separate from {@link DocumentScanWorker}: a test that drives {@code
 * sweep()} directly and a timer sweeping the same storage backend would race each other, and
 * the failure would look like flakiness rather than the design decision it is. Integration
 * tests disable this bean and call {@code sweep()} themselves.
 *
 * <p>Nightly by default, not on the scan worker's 5-second cadence — this walks the entire
 * storage namespace on every run rather than draining a queue of specific work items, so it is
 * priced in listing calls and object count, not in latency to the next upload.
 */
@Component
@ConditionalOnProperty(
        name = "ctms.documents.orphan-sweep.scheduled",
        havingValue = "true",
        matchIfMissing = true)
public class DocumentOrphanSweepScheduler {

    private final DocumentOrphanSweepWorker worker;

    public DocumentOrphanSweepScheduler(DocumentOrphanSweepWorker worker) {
        this.worker = worker;
    }

    @Scheduled(cron = "${ctms.documents.orphan-sweep.cron:0 0 3 * * *}")
    public void run() {
        worker.sweep();
    }
}
