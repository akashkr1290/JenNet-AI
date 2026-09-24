-- dev_test_seed.sql
-- LOCAL DEVELOPMENT / MANUAL TESTING ONLY. Never run against staging or
-- production. Not a Flyway migration — see this folder's README.md.
--
-- password_hash values below are intentionally NOT valid bcrypt/Argon2
-- output; they are placeholder strings so the row is unmistakably a seed
-- artifact rather than a working credential.

INSERT INTO users (full_name, mobile_number, email, password_hash, role, ward_id, status, mobile_verified_at)
VALUES
    ('Test Citizen', '9990000001', 'citizen.test@example.invalid',
     'SEED_PLACEHOLDER_NOT_A_REAL_HASH', 'CITIZEN',
     (SELECT ward_id FROM wards WHERE code = 'W1'), 'ACTIVE', CURRENT_TIMESTAMP);

INSERT INTO users (full_name, mobile_number, email, password_hash, role, department_id, status, mobile_verified_at)
VALUES
    ('Test Officer', '9990000002', 'officer.test@example.invalid',
     'SEED_PLACEHOLDER_NOT_A_REAL_HASH', 'GOVERNMENT_OFFICER',
     (SELECT department_id FROM departments WHERE name = 'Public Works'),
     'ACTIVE', CURRENT_TIMESTAMP);

INSERT INTO locations (latitude, longitude, ward_id, formatted_address, source)
VALUES (28.669512, 77.453362,
        (SELECT ward_id FROM wards WHERE code = 'W1'),
        'Sample address, Ward 1, Ghaziabad', 'DEVICE_GPS');

INSERT INTO complaints (reference_number, citizen_id, category, description, location_id,
                          department_id, assigned_officer_id, status, severity)
VALUES ('JN-2026-DEV0001',
        (SELECT user_id FROM users WHERE mobile_number = '9990000001'),
        'POTHOLE', 'Sample pothole for local testing.',
        (SELECT location_id FROM locations ORDER BY location_id DESC LIMIT 1),
        (SELECT department_id FROM departments WHERE name = 'Public Works'),
        (SELECT user_id FROM users WHERE mobile_number = '9990000002'),
        'ASSIGNED', 'MEDIUM');

INSERT INTO status_history (complaint_id, previous_status, new_status, actor_type, reason)
VALUES (
    (SELECT complaint_id FROM complaints WHERE reference_number = 'JN-2026-DEV0001'),
    NULL, 'SUBMITTED', 'CITIZEN', NULL
), (
    (SELECT complaint_id FROM complaints WHERE reference_number = 'JN-2026-DEV0001'),
    'SUBMITTED', 'ASSIGNED', 'SYSTEM', 'Dev seed: auto-assigned to Public Works'
);
