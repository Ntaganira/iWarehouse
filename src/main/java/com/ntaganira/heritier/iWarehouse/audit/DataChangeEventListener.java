package com.ntaganira.heritier.iWarehouse.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ntaganira.heritier.iWarehouse.enums.ChangeOperation;
import com.ntaganira.heritier.iWarehouse.security.AppUserPrincipal;
import org.hibernate.Hibernate;
import org.hibernate.HibernateException;
import org.hibernate.action.spi.BeforeTransactionCompletionProcess;
import org.hibernate.engine.spi.SessionImplementor;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.type.CompositeType;
import org.hibernate.type.Type;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <pre>
 * - Project   : iWarehouse - Glass Warehouse &amp; Mobile POS (ERP-Lite)
 * - Package   : com.ntaganira.heritier.iWarehouse.audit
 * - File      : DataChangeEventListener.java
 * - Date      : 2026. 10. 07.
 * - User      : Hntaganira
 * - Desc      : Captures BEFORE/AFTER snapshots of every @AuditedEntity change (SRS 4.13).
 * </pre>
 *
 * How it works:
 * <ol>
 *   <li>Hibernate calls this listener after each insert, update and delete of an entity.</li>
 *   <li>Old and new property values are turned into snapshots: collections skipped,
 *       associations reduced to their id. Changed fields are computed on the raw values,
 *       then masked fields are replaced by "***".</li>
 *   <li>The row is written with plain JDBC in a before-transaction-completion process,
 *       i.e. inside the same database transaction as the business change (AUD-04).
 *       If the audit insert fails, the commit fails and the business change rolls back.</li>
 * </ol>
 *
 * Not captured (by design, see CLAUDE.md): JPQL/SQL bulk updates and deletes, and
 * changes to collections (e.g. a role's permission set) — audit those explicitly.
 */
@Component
public class DataChangeEventListener
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final String INSERT_SQL = """
            INSERT INTO data_change_logs
              (request_id, entity_type, entity_id, entity_ref, operation, before_data, after_data,
               changed_fields, reason, user_id, username, ip_address, user_agent, device_id, client_time)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final AuditProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Class<?>, ClassInfo> classInfo = new ConcurrentHashMap<>();

    public DataChangeEventListener(AuditProperties properties) {
        this.properties = properties;
    }

    /** Audit metadata per entity class, computed once. */
    private record ClassInfo(boolean audited, String ref, Set<String> ignored, Set<String> masked) {
    }

    /** One data_change_logs row, captured in the request thread and written at commit. */
    private record ChangeRow(String requestId, String entityType, String entityId, String entityRef,
                             ChangeOperation operation, String beforeJson, String afterJson,
                             String[] changedFields, String reason, Long userId, String username,
                             String ipAddress, String userAgent, String deviceId, LocalDateTime clientTime) {
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        capture(event.getSession(), event.getEntity(), event.getId(), event.getPersister(),
                ChangeOperation.CREATE, null, event.getState());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        capture(event.getSession(), event.getEntity(), event.getId(), event.getPersister(),
                ChangeOperation.UPDATE, event.getOldState(), event.getState());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        capture(event.getSession(), event.getEntity(), event.getId(), event.getPersister(),
                ChangeOperation.DELETE, event.getDeletedState(), null);
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void capture(EventSource session, Object entity, Object id, EntityPersister persister,
                         ChangeOperation operation, Object[] oldState, Object[] newState) {
        Class<?> type = Hibernate.getClass(entity);
        ClassInfo info = classInfo.computeIfAbsent(type, this::inspect);
        if (!info.audited()) {
            return;
        }

        // Compare RAW values first, so a changed password is still reported as changed (AUD-06),
        // then mask secrets before anything is stored.
        Map<String, Object> before = oldState == null ? null : snapshot(session, persister, oldState, info);
        Map<String, Object> after = newState == null ? null : snapshot(session, persister, newState, info);
        List<String> changed = AuditSnapshots.changedFields(before, after);
        if (operation == ChangeOperation.UPDATE && before != null && AuditSnapshots.nothingChanged(changed)) {
            return; // only ignored, collection or version fields changed, or just the update stamps
        }
        before = mask(before, info);
        after = mask(after, info);

        AuditContext.Data ctx = AuditContext.current();
        Long userId = AppUserPrincipal.current().map(AppUserPrincipal::getId).orElse(null);
        String username = AppUserPrincipal.currentUsername();
        Map<String, Object> refSource = after != null ? after : before;
        String ref = StringUtils.hasText(info.ref()) && refSource != null && refSource.get(info.ref()) != null
                ? String.valueOf(refSource.get(info.ref())) : null;

        ChangeRow row = new ChangeRow(ctx.getRequestId(), type.getSimpleName(), String.valueOf(id), ref,
                operation, toJson(before), toJson(after), changed.toArray(String[]::new), ctx.getReason(),
                userId, username, ctx.getIpAddress(), ctx.getUserAgent(), ctx.getDeviceId(), ctx.getClientTime());

        session.getActionQueue().registerProcess(
                (BeforeTransactionCompletionProcess) s -> write(s, row));
    }

    private Map<String, Object> snapshot(EventSource session, EntityPersister persister, Object[] state,
                                         ClassInfo info) {
        String[] names = persister.getPropertyNames();
        Type[] types = persister.getPropertyTypes();
        int versionIndex = persister.isVersioned() ? persister.getVersionProperty() : -1;
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            Type t = types[i];
            if (i == versionIndex || t.isCollectionType() || info.ignored().contains(name)) {
                continue;
            }
            Object value = state[i];
            if (t.isComponentType() && t instanceof CompositeType composite) {
                String[] subNames = composite.getPropertyNames();
                Object[] subValues = value == null ? new Object[subNames.length] : composite.getPropertyValues(value);
                for (int j = 0; j < subNames.length; j++) {
                    put(result, name + "." + subNames[j], subValues[j], info);
                }
                continue;
            }
            if (t.isEntityType() && value != null) {
                value = entityId(session, value);
            }
            put(result, name, value, info);
        }
        return result;
    }

    private void put(Map<String, Object> result, String name, Object value, ClassInfo info) {
        result.put(name, AuditSnapshots.toAuditValue(value));
    }

    /** Replaces the value of every masked field (if set) with "***". Never store secrets (AUD-06). */
    private Map<String, Object> mask(Map<String, Object> snapshot, ClassInfo info) {
        if (snapshot == null) {
            return null;
        }
        snapshot.replaceAll((name, value) -> value != null && isMasked(name, info) ? AuditSnapshots.MASK : value);
        return snapshot;
    }

    private boolean isMasked(String name, ClassInfo info) {
        String leaf = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
        return info.masked().contains(name)
                || AuditSnapshots.isMaskedName(name, properties.maskedFields())
                || AuditSnapshots.isMaskedName(leaf, properties.maskedFields());
    }

    private Object entityId(EventSource session, Object value) {
        if (value instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getIdentifier();
        }
        Object id = session.getContextEntityIdentifier(value);
        return id != null ? id : session.getEntityPersister(null, value).getIdentifier(value, session);
    }

    private ClassInfo inspect(Class<?> type) {
        AuditedEntity annotation = type.getAnnotation(AuditedEntity.class);
        if (annotation == null) {
            return new ClassInfo(false, null, Set.of(), Set.of());
        }
        Set<String> ignored = new HashSet<>();
        Set<String> masked = new HashSet<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(AuditIgnore.class)) {
                    ignored.add(field.getName());
                }
                if (field.isAnnotationPresent(AuditMask.class)) {
                    masked.add(field.getName());
                }
            }
        }
        return new ClassInfo(true, annotation.ref(), Set.copyOf(ignored), Set.copyOf(masked));
    }

    private String toJson(Map<String, Object> snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new HibernateException("Could not serialise audit snapshot", e);
        }
    }

    private void write(SessionImplementor session, ChangeRow row) {
        session.doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                Array changed = connection.createArrayOf("text", row.changedFields());
                ps.setString(1, row.requestId());
                ps.setString(2, row.entityType());
                ps.setString(3, row.entityId());
                ps.setString(4, row.entityRef());
                ps.setString(5, row.operation().name());
                ps.setString(6, row.beforeJson());
                ps.setString(7, row.afterJson());
                ps.setArray(8, changed);
                ps.setString(9, row.reason());
                if (row.userId() == null) {
                    ps.setNull(10, Types.BIGINT);
                } else {
                    ps.setLong(10, row.userId());
                }
                ps.setString(11, row.username());
                ps.setString(12, row.ipAddress());
                ps.setString(13, row.userAgent());
                ps.setString(14, row.deviceId());
                ps.setObject(15, row.clientTime());
                ps.executeUpdate();
            }
        });
    }
}
