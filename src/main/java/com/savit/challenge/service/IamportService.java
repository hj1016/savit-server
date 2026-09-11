package com.savit.challenge.service;

import com.savit.challenge.dto.IamportPaymentDTO;
import java.math.BigDecimal;

public interface IamportService {
    void prepare(String merchantUid, BigDecimal amount);  // 금액 위변조 방지(prepare)

    /**
     * 결제 취소(환불). 참여 확정 실패 등 보상 트랜잭션에서 사용한다.
     * @param impUid       아임포트 결제 고유번호
     * @param amount       부분/전액 환불 금액(null이면 전액)
     * @param reason       환불 사유
     */
    void cancel(String impUid, BigDecimal amount, String reason);

    // 단건 조회: 각각 별도 메서드 제공 (원하는 경우 직접 호출)
    IamportPaymentDTO fetchPaymentByImpUid(String impUid);
    IamportPaymentDTO fetchPaymentByMerchantUid(String merchantUid);

    // 통합 조회: impUid 우선, 없으면 merchantUid로 조회
    default IamportPaymentDTO fetchPayment(String impUid, String merchantUid) {
        if (impUid != null && !impUid.isBlank()) return fetchPaymentByImpUid(impUid);
        if (merchantUid != null && !merchantUid.isBlank()) return fetchPaymentByMerchantUid(merchantUid);
        throw new IllegalArgumentException("impUid 또는 merchantUid 중 하나는 필수입니다.");
    }

    @Deprecated
    default IamportPaymentDTO fetchPayment(String impUid) {
        return fetchPaymentByImpUid(impUid);
    }
}
