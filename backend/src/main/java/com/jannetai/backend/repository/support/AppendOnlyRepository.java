package com.jannetai.backend.repository.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * A CRUD-repository-shaped interface with every delete method removed.
 *
 * Added in Phase 4 to satisfy the requirement flagged (but explicitly
 * deferred to this phase) in
 * database/migrations/V12__create_audit_logs.sql / Security Section 27.4:
 * "audit logs are append-only and are not exposed for deletion through any
 * application interface". Extending JpaRepository (which brings
 * deleteById/delete/deleteAll) would silently reopen that door for any
 * future service class; extending this instead makes it a compile error.
 *
 * This only enforces the rule at the application code layer. Restricting
 * DELETE at the database-user-grant level (defense in depth) is an
 * infrastructure/deployment concern, not a Spring Data concern - see
 * deployment/ (Phase 22) and docker/ (Phase 18).
 */
@NoRepositoryBean
public interface AppendOnlyRepository<T, ID> extends Repository<T, ID> {
    T save(T entity);
    Optional<T> findById(ID id);
    List<T> findAll();
    Page<T> findAll(Pageable pageable);
    long count();
    boolean existsById(ID id);
}
