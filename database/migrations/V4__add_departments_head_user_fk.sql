-- V4__add_departments_head_user_fk.sql
-- Adds the foreign key from departments.head_user_id to users.user_id, now
-- that the users table exists. See V2's header comment for why this is
-- deferred rather than inline.

ALTER TABLE departments
    ADD CONSTRAINT fk_departments_head_user
        FOREIGN KEY (head_user_id) REFERENCES users (user_id)
        ON DELETE SET NULL;

CREATE INDEX idx_departments_head_user_id ON departments (head_user_id);
