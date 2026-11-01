package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;
import app.pagefault.hyperllm.domain.model.enums.PaymentChannel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentGatewayRegistry {

    private final Map<PaymentChannel, PaymentGateway> gateways = new EnumMap<>(PaymentChannel.class);

    public PaymentGatewayRegistry(List<PaymentGateway> configured) {
        for (PaymentGateway gateway : configured) {
            gateways.put(gateway.channel(), gateway);
        }
    }

    public PaymentGateway require(PaymentChannel channel) {
        PaymentGateway gateway = gateways.get(channel);
        if (gateway == null || !gateway.enabled()) {
            throw new HyperLlmException(ErrorCode.PAYMENT_CHANNEL_DISABLED,
                    "Payment channel " + channel + " is not enabled");
        }
        return gateway;
    }

    public PaymentGateway find(PaymentChannel channel) {
        return gateways.get(channel);
    }
}
