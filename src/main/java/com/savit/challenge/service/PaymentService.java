package com.savit.challenge.service;

import com.savit.card.domain.CardTransactionVO;
import com.savit.card.mapper.CardMapper;
import com.savit.card.mapper.CardTransactionMapper;
import com.savit.challenge.domain.ChallengeVO;
import com.savit.challenge.domain.PaymentVO;
import com.savit.challenge.dto.IamportPaymentDTO;
import com.savit.challenge.dto.InitPaymentRequestDTO;
import com.savit.challenge.dto.InitPaymentResponseDTO;
import com.savit.challenge.dto.PaymentStatusResponseDTO;
import com.savit.challenge.mapper.ChallengeMapper;
import com.savit.challenge.mapper.ChallengeParticipationMapper;
import com.savit.challenge.mapper.PaymentMapper;
import com.savit.challenge.mapper.PointMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentMapper paymentMapper;
    private final PointMapper pointMapper;
    private final ChallengeMapper challengeMapper;
    private final ChallengeParticipationMapper challengeParticipationMapper;
    private final CardMapper cardMapper;
    private final CardTransactionMapper cardTransactionMapper;
    private final IamportService iamportService;

    /** 결제 가능한 최소 예치금(원). 0원/음수 결제 방지. */
    private static final long MIN_AMOUNT = 1_000L;
    /** 결제 가능한 최대 예치금(원). 금액 조작으로 인한 비정상 결제 방지. */
    private static final long MAX_AMOUNT = 10_000_000L;

    /**
     * 낙관락(CAS) 최대 재시도 횟수. 이 횟수만큼 CAS가 실패하면
     * 비관락(FOR UPDATE) 폴백으로 전환하는 트리거 값이다.
     */
    private static final int CAS_MAX_RETRIES = 3;

    @Transactional
    public InitPaymentResponseDTO initPayment(Long userId, InitPaymentRequestDTO req) {
        ChallengeVO chal = challengeMapper.findById(req.getChallengeId());
        if (chal == null) throw new IllegalArgumentException("존재하지 않는 챌린지입니다.");

        Date now = new Date();

        if (chal.getStartDate() != null && !now.before(chal.getStartDate())) {
            throw new IllegalStateException("이미 시작된 챌린지는 결제할 수 없습니다.");
        }
        if (chal.getEndDate() != null && now.after(chal.getEndDate())) {
            throw new IllegalStateException("이미 종료된 챌린지입니다.");
        }

        boolean already = challengeParticipationMapper.existsParticipation(req.getChallengeId(), userId);
        if (already) throw new IllegalStateException("이미 참여 중인 챌린지입니다.");

        BigDecimal amount = decideAmount(userId, req.getChallengeId(), req.getDesiredAmount());

        // 주문번호는 추측 불가능하도록 UUID 사용 (userId 접두로 가독성만 유지)
        String merchantUid = "order-" + userId + "-" + UUID.randomUUID().toString().replace("-", "");
        paymentMapper.insertPending(merchantUid, userId, req.getChallengeId(), amount.longValue());
        iamportService.prepare(merchantUid, amount);

        return InitPaymentResponseDTO.builder()
                .merchantUid(merchantUid)
                .amount(amount)
                .build();
    }

    /**
     * 결제 예치금 확정.
     * 챌린지는 사용자가 베팅 금액을 직접 정하는 구조(정산 시 예치금 비율로 분배)지만,
     * 클라이언트 입력값을 그대로 신뢰하지 않고 서버에서 0원·음수·과도한 금액을 차단한다.
     */
    private BigDecimal decideAmount(Long userId, Long challengeId, BigDecimal desired) {
        if (desired == null || desired.signum() <= 0) {
            throw new IllegalArgumentException("결제 금액(예치금)은 0보다 커야 합니다.");
        }
        // 원화 정수만 허용 (소수점 결제 차단)
        long won;
        try {
            won = desired.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("결제 금액은 원 단위 정수여야 합니다.");
        }
        if (won < MIN_AMOUNT || won > MAX_AMOUNT) {
            throw new IllegalArgumentException(
                    "결제 금액은 " + MIN_AMOUNT + "원 이상 " + MAX_AMOUNT + "원 이하여야 합니다. 입력=" + won);
        }
        return BigDecimal.valueOf(won);
    }

    @Transactional(readOnly = true)
    public PaymentStatusResponseDTO getStatus(String merchantUid) {
        PaymentVO p = paymentMapper.findByMerchantUid(merchantUid);
        String status = (p == null) ? "PENDING" : p.getStatus();
        return PaymentStatusResponseDTO.builder()
                .merchantUid(merchantUid)
                .status(status)
                .build();
    }

    /** 웹훅/verify 공용 멱등 처리 (환불 미적용, 멱등/락만) */
    @Transactional
    public void verifyAndConfirm(Long requesterUserIdOrNull, String merchantUidOrNull, String impUidOrNull) {
        // 1) 아임포트 결제 단건 조회 (impUid 우선)
        IamportPaymentDTO pay = iamportService.fetchPayment(impUidOrNull, merchantUidOrNull);
        final String merchantUid = pay.getMerchantUid();
        if (merchantUid == null || merchantUid.isBlank()) {
            throw new IllegalStateException("아임포트 응답에 merchant_uid 없음");
        }

        // 2) 결제 레코드 잠금 (멱등)
        PaymentVO p = paymentMapper.findByMerchantUidForUpdate(merchantUid);
        if (p == null) throw new IllegalStateException("알 수 없는 주문입니다. merchantUid=" + merchantUid);

        // 3) 요청자 소유 검증
        if (requesterUserIdOrNull != null && !Objects.equals(p.getUserId(), requesterUserIdOrNull)) {
            throw new IllegalStateException("주문 소유자가 일치하지 않습니다.");
        }

        // 4) 상태 검증
        if (!"paid".equalsIgnoreCase(pay.getStatus())) {
            throw new IllegalStateException("아임포트 상태가 'paid'가 아닙니다. status=" + pay.getStatus());
        }

        // 5) 금액 검증 (원화 정수)
        if (pay.getAmount() == null) throw new IllegalStateException("아임포트 응답 금액이 없습니다.");
        long payAmount = pay.getAmount().setScale(0, RoundingMode.UNNECESSARY).longValueExact();

        Long dbAmountObj = (p.getAmount() instanceof Long) ? (Long) p.getAmount() : null;
        if (dbAmountObj == null) throw new IllegalStateException("DB 금액이 null 입니다.");
        long dbAmount = dbAmountObj;

        if (payAmount != dbAmount) {
            throw new IllegalStateException("결제 금액 불일치: iamport=" + payAmount + ", db=" + dbAmount);
        }

        // 6) 멱등 가드
        String curr = p.getStatus();
        if ("SUCCESS".equals(curr)) {
            log.info("[결제 검증] 이미 SUCCESS 상태로 처리된 주문입니다. merchantUid={}", merchantUid);
            return;
        }
        if (!"PENDING".equals(curr)) {
            log.info("[결제 검증] PENDING이 아닌 상태라 처리하지 않습니다. merchantUid={}, status={}", merchantUid, curr);
            return;
        }

        // 7) 결제 성공 마킹
        Long paidAtSecLong = pay.getPaidAt();
        final long paidAtSec = (paidAtSecLong != null ? paidAtSecLong : 0L);
        final Date paidAt = paidAtSec > 0 ? new Date(paidAtSec * 1000L) : new Date();
        paymentMapper.markSuccess(merchantUid, pay.getImpUid(), paidAt);
        log.info("[결제 검증] 결제 성공 반영 완료. merchantUid={}, userId={}, challengeId={}",
                merchantUid, p.getUserId(), p.getChallengeId());

        // 8) 챌린지 참여 (멱등 + 락)
        boolean joined = tryJoinWithLocks(p.getUserId(), p.getChallengeId(), BigDecimal.valueOf(dbAmount));
        if (!joined) {
            // 결제는 성공했으나 정원 마감/경쟁 패배로 참여 확정 실패 → 자동 환불(보상 트랜잭션)
            log.warn("[참여 실패] 정원 마감 또는 경쟁 패배로 참여 확정 실패. 자동 환불을 진행합니다. merchantUid={}, userId={}, challengeId={}",
                    merchantUid, p.getUserId(), p.getChallengeId());
            refundForFailedJoin(merchantUid, pay.getImpUid(), BigDecimal.valueOf(dbAmount));
            // 환불 케이스는 카드 트랜잭션/포인트 적립을 하지 않고 종료
            return;
        }
        log.info("[참여 성공] 챌린지 참여 확정. userId={}, challengeId={}", p.getUserId(), p.getChallengeId());

        // 9) 카드 트랜잭션 저장 (참여 확정된 경우에만)
        insertCardTransactionFromIamport(pay, p.getUserId());

        // 10) 포인트 적립 (참여 확정된 경우에만)
        pointMapper.ensureRow(p.getUserId());
        pointMapper.add(p.getUserId(), dbAmount);
        log.info("[포인트] 적립 완료. userId={}, amount={}", p.getUserId(), dbAmount);
    }

    /** 최대 재시도 배치 크기(1회 실행당 처리 상한). */
    private static final int REFUND_RETRY_BATCH_SIZE = 100;

    /**
     * 자동 환불 실패(REFUND_FAILED) 건 목록 조회. 스케줄러가 건별로 재시도하도록 대상만 반환한다.
     * 조회 자체는 읽기 전용 트랜잭션으로 짧게 처리하고, 실제 환불/상태전이는 건별 트랜잭션으로 분리한다.
     */
    @Transactional(readOnly = true)
    public List<PaymentVO> findRefundFailedTargets() {
        return paymentMapper.findRefundFailed(REFUND_RETRY_BATCH_SIZE);
    }

    /**
     * REFUND_FAILED 결제 1건의 환불을 재시도한다(건별 독립 트랜잭션).
     * cancel은 멱등(이미 취소된 건은 성공 간주)이므로 반복 호출해도 안전하며,
     * 성공 시 REFUNDED로 확정한다. impUid가 없으면 자동 재시도가 불가능하므로 상태를 유지한다.
     *
     * @return 이번 호출로 환불이 확정(REFUNDED)되면 true
     */
    @Transactional
    public boolean retrySingleRefund(String merchantUid) {
        PaymentVO p = paymentMapper.findByMerchantUidForUpdate(merchantUid);
        if (p == null) {
            log.warn("[환불 재시도] 결제를 찾을 수 없습니다. merchantUid={}", merchantUid);
            return false;
        }
        // 다른 경로/앞선 재시도로 이미 처리됐으면 멱등 종료
        if (!"REFUND_FAILED".equals(p.getStatus())) {
            log.info("[환불 재시도] 이미 처리된 상태라 건너뜁니다. merchantUid={}, status={}", merchantUid, p.getStatus());
            return false;
        }
        if (p.getImpUid() == null || p.getImpUid().isBlank()) {
            // 자동 환불 불가 → 수동 처리 대상으로 REFUND_FAILED 유지
            log.error("[환불 재시도] impUid가 없어 자동 재시도 불가(수동 처리 필요). merchantUid={}", merchantUid);
            return false;
        }
        iamportService.cancel(p.getImpUid(), BigDecimal.valueOf(p.getAmount()),
                "챌린지 참여 정원 마감으로 인한 자동 환불(재시도)");
        paymentMapper.markRefunded(merchantUid);
        log.info("[환불 재시도] 환불 확정 완료. merchantUid={}, amount={}", merchantUid, p.getAmount());
        return true;
    }

    /**
     * 참여 확정 실패 시 보상 트랜잭션: 아임포트 결제 취소 + 결제 상태를 REFUNDED로 마킹.
     *
     * <p>이 시점의 결제는 이미 PG에서 승인(paid)되어 {@code markSuccess}로 SUCCESS가 된 상태다.
     * 따라서 환불이 실패하더라도 예외를 던져 트랜잭션을 롤백하면 안 된다.
     * 롤백하면 같은 트랜잭션 안의 {@code markSuccess}까지 되돌아가 상태가 PENDING으로 회귀하는데,
     * 실제 PG에는 돈이 잡혀 있어 DB-PG 불일치가 "조용히" 발생한다.
     *
     * <p>그래서 환불 실패 시에는 예외를 삼키고 같은 트랜잭션에서 {@code REFUND_FAILED} 상태로
     * 확정 커밋한다. 이렇게 하면 "결제는 성공했으나 자동 환불이 실패해 수동/재시도가 필요"한 건이
     * DB에 명시적으로 남아 운영 대시보드/재시도 배치가 인지할 수 있다.
     *
     * <p>참고로 별도 커밋 트랜잭션(REQUIRES_NEW)은 여기서 부적합하다. 새 트랜잭션은 별도 커넥션이라
     * 아직 커밋되지 않은 outer 트랜잭션의 SUCCESS를 볼 수 없어, {@code WHERE status='SUCCESS'} 조건이
     * 빗나가기 때문이다. 동일 트랜잭션 내 상태 전이가 정합적이다.
     */
    private void refundForFailedJoin(String merchantUid, String impUid, BigDecimal amount) {
        if (impUid == null || impUid.isBlank()) {
            // impUid 없이는 자동 환불 자체가 불가 → 롤백 대신 REFUND_FAILED로 남겨 수동 처리 유도
            log.error("[환불 불가] impUid가 없어 자동 환불 불가 → REFUND_FAILED 마킹. merchantUid={}", merchantUid);
            paymentMapper.markRefundFailed(merchantUid);
            return;
        }
        try {
            iamportService.cancel(impUid, amount, "챌린지 참여 정원 마감으로 인한 자동 환불");
            paymentMapper.markRefunded(merchantUid);
            log.info("[환불 완료] 참여 실패분 자동 환불 처리. merchantUid={}, amount={}", merchantUid, amount);
        } catch (Exception e) {
            // PG는 이미 paid → SUCCESS를 롤백하지 않고 REFUND_FAILED로 확정 커밋(재시도/수동 처리 대상)
            log.error("[환불 실패] 자동 환불 실패 → REFUND_FAILED 마킹. merchantUid={}, amount={}", merchantUid, amount, e);
            paymentMapper.markRefundFailed(merchantUid);
        }
    }

    /** 낙관락(CAS) + 막판 비관락으로 안전하게 참여 인서트. 실패 시 호출부에서 자동 환불 처리. */
    private boolean tryJoinWithLocks(Long userId, Long challengeId, BigDecimal myFee) {
        // 이미 참여했다면 멱등 종료
        if (challengeParticipationMapper.existsParticipation(challengeId, userId)) {
            log.info("[참여 멱등] 이미 참여 중입니다. userId={}, challengeId={}", userId, challengeId);
            return true;
        }

        // 무제한이면 바로 인서트 시도
        ChallengeVO ch = challengeMapper.findChallengeForJoin(challengeId);
        if (ch == null) {
            log.warn("[참여 실패] 챌린지가 존재하지 않습니다. challengeId={}", challengeId);
            return false;
        }
        if (ch.getTotalParticipants() == null) {
            return insertParticipationIdempotent(challengeId, userId, myFee);
        }

        // 1) 낙관락(CAS): CAS_MAX_RETRIES회 시도, 모두 실패하면 아래 비관락으로 전환
        for (int i = 0; i < CAS_MAX_RETRIES; i++) {
            int ok = challengeMapper.tryReserveSeatCAS(challengeId, ch.getVersion());
            if (ok == 1) {
                return insertParticipationIdempotent(challengeId, userId, myFee);
            }
            // 실패 → 최신 버전 재조회 후 재시도
            ch = challengeMapper.findChallengeForJoin(challengeId);
            if (ch == null) {
                log.warn("[참여 실패] 챌린지를 재조회했으나 존재하지 않습니다. challengeId={}", challengeId);
                return false;
            }
            if (ch.getTotalParticipants() == null) {
                return insertParticipationIdempotent(challengeId, userId, myFee);
            }
        }

        // 2) 막판: 비관락(짧게 1회)
        ChallengeVO locked = challengeMapper.findForUpdate(challengeId); // 같은 @Transactional 안
        Long cap = locked.getTotalParticipants();
        if (cap == null) {
            return insertParticipationIdempotent(challengeId, userId, myFee);
        }
        long current = challengeParticipationMapper.countByChallengeId(challengeId);
        if (current >= cap) {
            log.info("[정원 초과] 현재 인원={} / 정원={}. userId={}, challengeId={}", current, cap, userId, challengeId);
            return false; // 정원 초과
        }
        return insertParticipationIdempotent(challengeId, userId, myFee);
    }

    /** UNIQUE 제약으로 멱등 보장 */
    private boolean insertParticipationIdempotent(Long challengeId, Long userId, BigDecimal myFee) {
        try {
            challengeParticipationMapper.insertParticipation(challengeId, userId, myFee);
            log.info("[참여 등록] 참여 인서트 완료. userId={}, challengeId={}, myFee={}", userId, challengeId, myFee);
            return true;
        } catch (DuplicateKeyException e) {
            // 이미 참여됨 → 멱등 성공으로 간주
            log.info("[참여 멱등] 이미 참여되어 있습니다. userId={}, challengeId={}", userId, challengeId);
            return true;
        }
    }

    private void insertCardTransactionFromIamport(IamportPaymentDTO pay, Long userId) {
        var card = cardMapper.findFirstCardByUserId(userId);
        if (card == null) {
            log.warn("[카드 트랜잭션] 사용자 카드 정보가 없어 저장하지 않습니다. userId={}", userId);
            return;
        }

        Date paidDate = new Date(pay.getPaidAt() * 1000L);
        var dateFormat = new java.text.SimpleDateFormat("yyyyMMdd");
        var timeFormat = new java.text.SimpleDateFormat("HHmmss");

        CardTransactionVO tx = new CardTransactionVO();
        tx.setCardId(card.getId());
        tx.setResCardNo(card.getResCardNo());
        tx.setResUsedDate(dateFormat.format(paidDate));
        tx.setResUsedTime(timeFormat.format(paidDate));
        tx.setResUsedAmount(String.valueOf(pay.getAmount().longValue()));
        tx.setResCancelYn("0");
        tx.setResCancelAmount("");
        tx.setResTotalAmount("");
        tx.setBudgetCategoryId(null);
        tx.setCategoryId(null);
        tx.setResMemberStoreName(pay.getName());
        tx.setResMemberStoreType(pay.getPgProvider());
        tx.setCreatedAt(new Date());
        tx.setUpdatedAt(new Date());

        cardTransactionMapper.insert(tx);
        log.info("[카드 트랜잭션] 저장 완료: {}", tx);
    }
}