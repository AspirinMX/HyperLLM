package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.routing.GatewayModelRoute;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface GatewayRouteRepository {

    Optional<GatewayModelRoute> findActiveRouteByModelCode(String modelCode, OffsetDateTime at);
}
