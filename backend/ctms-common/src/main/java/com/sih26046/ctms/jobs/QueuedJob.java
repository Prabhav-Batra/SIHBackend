package com.sih26046.ctms.jobs;

import java.util.UUID;

/** A job claimed from the queue, ready to run. */
public record QueuedJob(UUID id, String jobType, String payload, int attempts) {}
