package com.savit.challenge.mapper;

import com.savit.challenge.domain.PaymentVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

@Mapper
public interface PaymentMapper {
    void insertPending(@Param("merchantUid") String merchantUid,
                       @Param("userId") Long userId,
                       @Param("challengeId") Long challengeId,
                       @Param("amount") Long amount); // BIGINT

    PaymentVO findByMerchantUid(@Param("merchantUid") String merchantUid);

    PaymentVO findByMerchantUidForUpdate(@Param("merchantUid") String merchantUid);

    void markSuccess(@Param("merchantUid") String merchantUid,
                     @Param("impUid") String impUid,
                     @Param("paidAt") Date paidAt);

    /** 참여 확정 실패로 환불된 결제를 REFUNDED 상태로 마킹 (SUCCESS → REFUNDED) */
    void markRefunded(@Param("merchantUid") String merchantUid);

    /**
     * 참여 확정 실패 후 자동 환불까지 실패한 결제를 REFUND_FAILED로 마킹한다.
     * PG에는 결제가 승인(paid)된 상태이므로 SUCCESS를 롤백하지 않고 이 상태로 확정 커밋해
     * 재시도 배치/운영이 수동 환불을 처리할 수 있게 남긴다. (SUCCESS → REFUND_FAILED)
     */
    void markRefundFailed(@Param("merchantUid") String merchantUid);

    /** 자동 환불 재시도 배치 대상: REFUND_FAILED 상태 결제 목록(오래된 순, 배치 크기 제한) */
    List<PaymentVO> findRefundFailed(@Param("limit") int limit);
}