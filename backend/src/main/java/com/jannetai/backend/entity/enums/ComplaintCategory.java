package com.jannetai.backend.entity.enums;

/**
 * AI issue categories (PROJECT_INTEGRATION.md Section 3). Matches
 * complaints.category and routing_rules.issue_category CHECK constraints
 * exactly. GENERAL is the Department Assignment Module's documented
 * unmapped-category fallback (SRS 15.7).
 */
public enum ComplaintCategory {
    POTHOLE,
    GARBAGE_OVERFLOW,
    WATER_LEAKAGE,
    BROKEN_STREET_LIGHT,
    OPEN_MANHOLE,
    ILLEGAL_CONSTRUCTION,
    GENERAL
}
