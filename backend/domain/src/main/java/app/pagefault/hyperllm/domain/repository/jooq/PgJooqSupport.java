package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.common.page.PageQuery;
import app.pagefault.hyperllm.common.page.PageResponse;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.SortField;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared jOOQ mapping, PostgreSQL type-casting, and pagination helpers for repositories.
 */
final class PgJooqSupport {

    private PgJooqSupport() {
    }

    static Field<Object> toInet(String ip, Field<Object> targetField) {
        if (ip == null || ip.isBlank()) {
            return DSL.val((Object) null, targetField.getDataType());
        }
        return DSL.val(ip).cast(targetField.getDataType());
    }

    static Map<Field<Object>, Field<Object>> inetMap(Field<Object> targetField, String ip) {
        return Collections.singletonMap(targetField, toInet(ip, targetField));
    }

    static String fromInet(Object value) {
        return value == null ? null : value.toString();
    }

    static Field<Object[]> toCidrArray(List<String> cidrs, Field<Object[]> targetField) {
        if (cidrs == null) {
            return DSL.val((Object[]) null, targetField.getDataType());
        }
        return DSL.val(cidrs.toArray(String[]::new), SQLDataType.CLOB.array()).cast(targetField.getDataType());
    }

    static List<String> fromCidrArray(Object[] values) {
        if (values == null) {
            return null;
        }
        return Arrays.stream(values).map(Object::toString).toList();
    }

    static String[] toTextArray(List<String> values) {
        return values == null ? null : values.toArray(String[]::new);
    }

    static List<String> fromTextArray(String[] values) {
        return values == null ? null : List.of(values);
    }

    static UUID[] toUuidArray(List<UUID> values) {
        return values == null ? new UUID[0] : values.toArray(UUID[]::new);
    }

    static List<UUID> fromUuidArray(UUID[] values) {
        return values == null ? List.of() : List.of(values);
    }

    static JSONB toJsonb(String rawJson) {
        return rawJson == null ? null : JSONB.valueOf(rawJson);
    }

    static String fromJsonb(JSONB jsonb) {
        return jsonb == null ? null : jsonb.data();
    }

    static <E extends Enum<E>> E toEnum(Class<E> enumType, String value) {
        return value == null ? null : Enum.valueOf(enumType, value);
    }

    static <R extends Record, T> PageResponse<T> fetchPage(
            DSLContext dsl,
            Table<R> table,
            Condition condition,
            SortField<?> orderBy,
            PageQuery pageQuery,
            java.util.function.Function<R, T> mapper
    ) {
        long total = dsl.fetchCount(dsl.selectFrom(table).where(condition));
        if (total == 0L) {
            return new PageResponse<>(List.of(), pageQuery.page(), pageQuery.pageSize(), 0L);
        }
        SelectConditionStep<R> query = dsl.selectFrom(table).where(condition);
        List<T> items = query.orderBy(orderBy)
                .limit(pageQuery.pageSize())
                .offset(pageQuery.offset())
                .fetch(mapper::apply);
        return new PageResponse<>(items, pageQuery.page(), pageQuery.pageSize(), total);
    }
}
