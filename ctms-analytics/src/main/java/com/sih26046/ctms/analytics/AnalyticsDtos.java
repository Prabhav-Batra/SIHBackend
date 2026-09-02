package com.sih26046.ctms.analytics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** §21.4, §23 — one endpoint, a payload shaped by the caller's role. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {}

    /** One trial's rollup (§V25), as seen on a card. */
    public record TrialRollup(
            UUID trialId,
            String protocolNumber,
            String shortTitle,
            String status,
            int currentEnrollment,
            Integer targetEnrollment,
            long siteCount,
            long aeTotal,
            long aeSerious,
            long aeUnreviewed,
            long complianceTotal,
            long complianceCompliant,
            long complianceMandatoryOpen,
            boolean ethicsApprovedCurrent) {}

    public record AdminDashboard(
            String dashboardType,
            long userTotal,
            long userActive,
            long userLocked,
            long institutionCount,
            long activeTrialCount,
            long securityAlerts24h) {}

    public record InvestigatorDashboard(String dashboardType, List<TrialRollup> trials) {}

    public record CoordinatorDashboard(
            String dashboardType, long todaysVisits, long missedVisits, List<TrialRollup> trials) {}

    public record ResearchDashboard(
            String dashboardType, long todaysVisits, long assignedParticipants) {}

    public record EthicsDashboard(
            String dashboardType, long pendingSubmissions, long decisionsLast30Days) {}

    public record SafetyDashboard(
            String dashboardType,
            long pendingReview,
            long openSerious,
            long expeditedOverdue) {}

    public record RegulatoryDashboard(
            String dashboardType,
            long institutionCount,
            long trialCount,
            long siteCount,
            long compliantTrials,
            long nonCompliantTrials,
            long trialsWithoutCurrentApproval) {}

    // ── audit, §21.3 ─────────────────────────────────────────────────────────

    public record AuditEntry(
            UUID id,
            UUID userId,
            String action,
            String entityType,
            UUID entityId,
            UUID trialId,
            String outcome,
            Instant occurredAt) {}
}
