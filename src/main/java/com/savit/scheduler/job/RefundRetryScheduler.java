package com.savit.scheduler.job;

import com.savit.challenge.domain.PaymentVO;
import com.savit.challenge.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 자동 환불 실패(REFUND_FAILED) 결제 재시도 배치.
 *
 * <p>참여 확정 실패 후 자동 환불이 실패하면 결제는 REFUND_FAILED로 남는다(PG에는 paid 상태).
 * 이 배치가 주기적으로 해당 건들을 다시 환불 시도한다. 아임포트 cancel은 멱등이라
 * (이미 취소된 건은 성공으로 간주) 안전하게 반복 호출할 수 있다.
 *
 * <p>건별로 독립 트랜잭션({@link PaymentService#retrySingleRefund})을 사용하므로
 * 한 건의 실패가 다른 건 처리에 영향을 주지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundRetryScheduler {

    private final PaymentService paymentService;

    // 10분마다 자동 환불 실패분 재시도
    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Seoul")
    public void retryFailedRefunds() {
        List<PaymentVO> targets = paymentService.findRefundFailedTargets();
        if (targets == null || targets.isEmpty()) {
            return;
        }

        log.info("===== [환불 재시도] 시작 — 대상 {}건", targets.size());
        int refunded = 0;
        for (PaymentVO p : targets) {
            try {
                if (paymentService.retrySingleRefund(p.getMerchantUid())) {
                    refunded++;
                }
            } catch (Exception e) {
                // 다음 건 계속 진행 (해당 건은 REFUND_FAILED로 유지되어 다음 주기에 재시도)
                log.error("===== [환불 재시도] 건 처리 실패 — merchantUid={}", p.getMerchantUid(), e);
            }
        }
        log.info("===== [환불 재시도] 완료 — 환불 확정 {}건 / 대상 {}건", refunded, targets.size());
    }
}
