package app.pagefault.hyperllm.domain.repository.jooq;

import app.pagefault.hyperllm.domain.jooq.tables.records.ProvidersRecord;
import app.pagefault.hyperllm.domain.model.enums.AuthScheme;
import app.pagefault.hyperllm.domain.model.enums.ProtocolFamily;
import app.pagefault.hyperllm.domain.model.routing.Provider;
import app.pagefault.hyperllm.domain.repository.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static app.pagefault.hyperllm.domain.jooq.Tables.PROVIDERS;

@Repository
@RequiredArgsConstructor
public class JooqProviderRepository implements ProviderRepository {

    private final DSLContext dsl;

    @Override
    public Optional<Provider> findById(UUID id) {
        return dsl.selectFrom(PROVIDERS)
                .where(PROVIDERS.ID.eq(id))
                .fetchOptional(this::toDomain);
    }

    @Override
    public Optional<Provider> findByCode(String code) {
        return dsl.selectFrom(PROVIDERS)
                .where(PROVIDERS.CODE.eq(code))
                .fetchOptional(this::toDomain);
    }

    @Override
    public List<Provider> listAllActive() {
        return dsl.selectFrom(PROVIDERS)
                .where(PROVIDERS.STATUS.eq("ACTIVE"))
                .orderBy(PROVIDERS.CODE.asc())
                .fetch(this::toDomain);
    }

    private Provider toDomain(ProvidersRecord r) {
        return new Provider(
                r.getId(),
                r.getCode(),
                r.getDisplayName(),
                PgJooqSupport.toEnum(ProtocolFamily.class, r.getProtocolFamily()),
                PgJooqSupport.fromTextArray(r.getSupportedProtocols()),
                r.getDefaultWireProtocol(),
                r.getDefaultBaseUrl(),
                PgJooqSupport.toEnum(AuthScheme.class, r.getAuthScheme()),
                r.getStatus(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
