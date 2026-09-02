package com.sih26046.ctms.documents;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the scan queue.
 *
 * <p>Separate from {@link DocumentScanWorker} so the worker stays directly callable: a test
 * that also had a timer running against the same queue would race it, and the failure would
 * look like flakiness rather than like the design decision it is. Integration tests disable
 * this bean and drive {@code runOnce()} themselves.
 *
 * <p>The loop drains rather than taking one job per tick, so a burst of uploads is not spread
 * across a minute of polling — but it is bounded, so one poll cannot monopolise the thread.
 */
@Component
@ConditionalOnProperty(
        name = "ctms.documents.scan.scheduled",
        havingValue = "true",
        matchIfMissing = true)
public class DocumentScanScheduler {

    private static final int MAX_PER_TICK = 20;

    private final DocumentScanWorker worker;

    public DocumentScanScheduler(DocumentScanWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${ctms.documents.scan.poll-interval:5s}")
    public void drain() {
        for (int i = 0; i < MAX_PER_TICK && worker.runOnce(); i++) {
            // runOnce reports whether there was work; the loop ends when there is none.
        }
    }
}
