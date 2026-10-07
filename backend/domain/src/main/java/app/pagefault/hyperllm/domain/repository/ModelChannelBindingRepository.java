package app.pagefault.hyperllm.domain.repository;

import app.pagefault.hyperllm.domain.model.routing.ModelChannelBinding;

import java.util.List;
import java.util.UUID;

public interface ModelChannelBindingRepository {

    void insert(ModelChannelBinding binding);

    void replaceForModel(UUID virtualModelId, List<ModelChannelBinding> bindings);

    List<ModelChannelBinding> listByModelId(UUID virtualModelId);
}
