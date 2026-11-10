import Link from "next/link";
import { ArrowLeft, XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";

export default function PaymentCancelPage() {
  return (
    <div className="mx-auto flex max-w-md flex-col items-center gap-4 py-16 text-center">
      <div className="flex size-12 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
        <XCircle className="size-6" />
      </div>
      <h1 className="text-lg font-semibold">已取消支付</h1>
      <p className="text-xs text-muted-foreground">
        未产生任何扣款，可随时返回账务中心继续支付或重新下单。
      </p>
      <Button variant="outline" size="sm" render={<Link href="/billing" />}>
        <ArrowLeft className="size-3.5" />
        <span>返回账务中心</span>
      </Button>
    </div>
  );
}
