# JANNet AI — Entity Relationship Diagram (Phase 2)

Source of truth: `database/migrations/V1`–`V15`. Regenerate this diagram
whenever a migration changes the schema shape.

```mermaid
erDiagram
    WARDS ||--o{ USERS : "registers in"
    WARDS ||--o{ LOCATIONS : "resolves to"
    DEPARTMENTS ||--o{ USERS : "employs"
    DEPARTMENTS |o--o| USERS : "headed by"
    DEPARTMENTS ||--o{ COMPLAINTS : "handles"
    DEPARTMENTS ||--o{ ROUTING_RULES : "routed to"
    USERS ||--o{ COMPLAINTS : "submits (citizen)"
    USERS |o--o{ COMPLAINTS : "assigned to (officer)"
    USERS ||--o{ IMAGES : "uploads"
    USERS ||--o{ NOTIFICATIONS : "receives"
    USERS |o--o{ STATUS_HISTORY : "acts as"
    USERS |o--o{ AUDIT_LOGS : "acts as"
    USERS ||--o{ SETTINGS : "updates"
    USERS ||--o{ ROUTING_RULES : "creates"
    USERS |o--o{ BUDGET : "approves"
    LOCATIONS |o--o{ COMPLAINTS : "resolved location"
    COMPLAINTS ||--o{ IMAGES : "has"
    COMPLAINTS ||--o| PREDICTIONS : "classified by (1..n, latest wins)"
    COMPLAINTS ||--o| BUDGET : "estimated by"
    COMPLAINTS ||--o{ STATUS_HISTORY : "logs"
    COMPLAINTS ||--o{ NOTIFICATIONS : "triggers"
    COMPLAINTS |o--o{ COMPLAINTS : "merges into (parent_complaint_id)"

    WARDS {
        bigint ward_id PK
        varchar name
        varchar code
        json boundary_geojson
        boolean is_active
    }
    DEPARTMENTS {
        bigint department_id PK
        varchar name
        bigint head_user_id FK
        boolean is_active
    }
    USERS {
        bigint user_id PK
        varchar full_name
        varchar mobile_number
        varchar email
        varchar role
        bigint department_id FK
        bigint ward_id FK
        int reputation_score
        varchar status
    }
    LOCATIONS {
        bigint location_id PK
        decimal latitude
        decimal longitude
        bigint ward_id FK
        varchar source
        boolean out_of_jurisdiction
    }
    COMPLAINTS {
        bigint complaint_id PK
        varchar reference_number
        bigint citizen_id FK
        varchar category
        bigint location_id FK
        bigint department_id FK
        bigint assigned_officer_id FK
        varchar status
        varchar severity
        bigint parent_complaint_id FK
        int corroboration_count
    }
    IMAGES {
        bigint image_id PK
        bigint complaint_id FK
        varchar image_type
        varchar storage_key
        bigint uploaded_by FK
    }
    PREDICTIONS {
        bigint prediction_id PK
        bigint complaint_id FK
        decimal ai_confidence
        varchar model_version
        varchar predicted_severity
        decimal priority_score
        json raw_model_output
    }
    BUDGET {
        bigint budget_id PK
        bigint complaint_id FK
        decimal estimated_cost_min
        decimal estimated_cost_max
        int estimated_resolution_days
        bigint approved_by FK
    }
    STATUS_HISTORY {
        bigint history_id PK
        bigint complaint_id FK
        varchar previous_status
        varchar new_status
        bigint actor_id FK
        varchar actor_type
    }
    NOTIFICATIONS {
        bigint notification_id PK
        bigint user_id FK
        bigint complaint_id FK
        varchar channel
        varchar delivery_status
    }
    AUDIT_LOGS {
        bigint log_id PK
        bigint actor_id FK
        varchar action_type
        varchar entity_type
        bigint entity_id
        json details
    }
    SETTINGS {
        bigint setting_id PK
        varchar scope
        bigint scope_id
        varchar key
        varchar value
        bigint updated_by FK
    }
    ROUTING_RULES {
        bigint routing_rule_id PK
        varchar issue_category
        bigint department_id FK
        decimal ai_confidence_threshold
        decimal duplicate_similarity_threshold
        int sla_hours
        date effective_from
        bigint created_by FK
    }
```

## Notes on cardinality deviations from the SRS's own ER summary (19.12)

- **Complaints -> Predictions**: SRS describes this as 1:1. This schema
  allows 1:many to accommodate the AI Analysis Module's documented retry
  behavior (SRS 15.3). See `V8__create_predictions.sql` for the full
  rationale.
- **Departments -> jurisdiction**: SRS's Departments table references a
  `jurisdiction_id -> Locations.ward_id` column that this schema omits
  (single-jurisdiction pilot assumption). See `V2__create_departments.sql`.
- **Users.ward_id target**: corrected from the SRS's stated
  `Locations.ward_id` (which doesn't exist — Locations' PK is
  `location_id`) to `Wards.ward_id`. See `V3__create_users.sql`.
