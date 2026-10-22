package app.pagefault.hyperllm.admin.maintenance;

import app.pagefault.hyperllm.domain.model.billing.BonusGrant;
import app.pagefault.hyperllm.domain.model.billing.ResourcePack;
import app.pagefault.hyperllm.domain.repository.BonusGrantRepository;
import app.pagefault.hyperllm.domain.repository.ResourcePackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Closes batches whose date has passed.
 *
 * <p>A bonus batch also reduces the wallet snapshot because that balance is money; a resource pack
 * only changes status, because its units were never part of the wallet. Both are bounded per run so
 * a large backlog is worked off over several sweeps instead of one long transaction.
 */
@Slf4j
@Component
public class BatchExpiryJob {

    private final BonusGrantRepository grantRepository;
    private final ResourcePackRepository packRepository;
    private final BatchExpiryService expiryService;
    private final int batchSize;

    public BatchExpiryJob(
            BonusGrantRepository grantRepository,
            ResourcePackRepository packRepository,
            BatchExpiryService expiryService,
            @Value("${hyperllm.maintenance.expiry-batch:200}") int batchSize
    ) {
        this.grantRepository = grantRepository;
        this.packRepository = packRepository;
        this.expiryService = expiryService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${hyperllm.maintenance.expiry-delay:PT5M}")
    public void sweep() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<BonusGrant> grants = grantRepository.listExpiredActive(now, batchSize);
        int booked = 0;
        for (BonusGrant grant : grants) {
            if (expiryService.expireGrant(grant, now)) {
                booked++;
            }
        }
        List<ResourcePack> packs = packRepository.listExpiredActive(now, batchSize);
        int closed = 0;
        for (ResourcePack pack : packs) {
            if (packRepository.expire(pack.id(), now)) {
                closed++;
            }
        }
        if (booked > 0 || closed > 0) {
            log.info("expired {} bonus grants and {} resource packs", booked, closed);
        }
    }
}
