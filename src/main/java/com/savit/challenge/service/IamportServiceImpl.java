package com.savit.challenge.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.savit.challenge.config.IamportProperties;
import com.savit.challenge.dto.IamportPaymentDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class IamportServiceImpl implements IamportService {

    private final IamportProperties props;
    private final ObjectMapper om = new ObjectMapper();
    private final RestTemplate rest = new RestTemplate();

    /** 액세스 토큰 발급 */
    private String getAccessToken() {
        final String url = props.getBaseUrl() + "/users/getToken";

        Map<String, String> body = new HashMap<>();
        body.put("imp_key", props.getApiKey());
        body.put("imp_secret", props.getApiSecret());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> req = new HttpEntity<>(body, headers);

        ResponseEntity<String> resp = rest.postForEntity(url, req, String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("아임포트 토큰 요청 실패: HTTP " + resp.getStatusCode());
        }
        try {
            JsonNode root = om.readTree(resp.getBody());
            String token = root.path("response").path("access_token").asText(null);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("응답에 access_token 없음");
            }
            log.info("[Iamport] 액세스 토큰 발급 성공");
            return token;
        } catch (Exception e) {
            throw new IllegalStateException("토큰 응답 파싱 실패", e);
        }
    }

    /** 금액 위변조 방지용 사전등록 */
    @Override
    public void prepare(String merchantUid, BigDecimal amount) {
        final String url = props.getBaseUrl() + "/payments/prepare";
        final String token = getAccessToken();

        Map<String, Object> body = new HashMap<>();
        body.put("merchant_uid", merchantUid);
        body.put("amount", amount.longValue()); // 원화 정수 가정

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        ResponseEntity<String> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("아임포트 결제 사전 등록 실패: HTTP " + resp.getStatusCode());
        }
        log.info("[Iamport] 결제 사전 등록 성공: merchantUid={}, amount={}", merchantUid, amount);
    }

    /** 결제 취소(환불) — 참여 확정 실패 시 보상 트랜잭션용 */
    @Override
    public void cancel(String impUid, BigDecimal amount, String reason) {
        if (impUid == null || impUid.isBlank()) {
            throw new IllegalArgumentException("환불하려면 impUid가 필요합니다.");
        }
        final String url = props.getBaseUrl() + "/payments/cancel";
        final String token = getAccessToken();

        Map<String, Object> body = new HashMap<>();
        body.put("imp_uid", impUid);
        if (amount != null) body.put("amount", amount.longValue()); // 부분 환불(원화 정수), null이면 전액
        if (reason != null) body.put("reason", reason);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        ResponseEntity<String> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("아임포트 결제 취소 실패: HTTP " + resp.getStatusCode());
        }
        try {
            JsonNode root = om.readTree(resp.getBody());
            // 아임포트는 실패 시에도 200을 주고 code != 0 으로 알림 → code 확인
            int code = root.path("code").asInt(0);
            if (code != 0) {
                String message = root.path("message").asText("");
                // 멱등 처리: 타임아웃 재시도 등으로 이미 취소된 결제를 다시 취소하려는 경우,
                // 실제 상태를 재조회해 이미 'cancelled'면 성공으로 간주한다.
                // (부분취소/타임아웃 후 재시도가 REFUND_FAILED에 영구히 갇히는 것을 방지)
                if (isAlreadyCancelled(impUid)) {
                    log.info("[Iamport] 이미 취소된 결제 — 멱등 성공 처리. impUid={}, code={}, message={}",
                            impUid, code, message);
                    return;
                }
                throw new IllegalStateException("아임포트 결제 취소 거부: code=" + code + ", message=" + message);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("결제 취소 응답 파싱 실패", e);
        }
        log.info("[Iamport] 결제 취소(환불) 성공: impUid={}, amount={}, reason={}", impUid, amount, reason);
    }

    /** 결제 단건을 재조회해 이미 취소('cancelled') 상태인지 확인 (cancel 멱등 판별용) */
    private boolean isAlreadyCancelled(String impUid) {
        try {
            IamportPaymentDTO current = fetchPaymentByImpUid(impUid);
            return current != null && "cancelled".equalsIgnoreCase(current.getStatus());
        } catch (Exception e) {
            // 상태 확인 자체가 실패하면 멱등으로 단정할 수 없으므로 false → 상위에서 취소 실패로 처리
            log.warn("[Iamport] 취소 멱등 판별용 상태 재조회 실패. impUid={}", impUid, e);
            return false;
        }
    }

    /** 단건 조회(impUid) */
    @Override
    public IamportPaymentDTO fetchPaymentByImpUid(String impUid) {
        final String token = getAccessToken();
        final String url = props.getBaseUrl() + "/payments/" + impUid;
        return fetchAndMap(token, url);
    }

    /** 단건 조회(merchantUid) */
    @Override
    public IamportPaymentDTO fetchPaymentByMerchantUid(String merchantUid) {
        final String token = getAccessToken();
        final String url = props.getBaseUrl() + "/payments/find/" + merchantUid;
        return fetchAndMap(token, url);
    }

    /** 공통: 호출 + 매핑 */
    private IamportPaymentDTO fetchAndMap(String accessToken, String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> req = new HttpEntity<>(headers);

        ResponseEntity<String> resp = rest.exchange(url, HttpMethod.GET, req, String.class);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("아임포트 결제 조회 실패: HTTP " + resp.getStatusCode());
        }

        try {
            JsonNode root = om.readTree(resp.getBody());
            JsonNode r = root.path("response");
            if (r.isMissingNode() || r.isNull()) {
                throw new IllegalStateException("아임포트 응답이 비어 있습니다.");
            }

            String status       = r.path("status").asText(null);
            String merchantUid  = r.path("merchant_uid").asText(null);
            String impUid       = r.path("imp_uid").asText(null);
            long amountWon      = r.path("amount").asLong();
            long paidAtEpochSec = r.path("paid_at").asLong(0);
            String name         = r.path("name").asText(null);
            String pgProvider   = r.path("pg_provider").asText(null);

            // custom_data: 문자열 또는 JSON 객체 양쪽 대응
            Long userId = null, challengeId = null;
            JsonNode customNode = r.path("custom_data");
            if (!customNode.isMissingNode() && !customNode.isNull()) {
                try {
                    JsonNode c = customNode.isTextual() ? om.readTree(customNode.asText()) : customNode;
                    if (c.has("userId"))      userId = c.get("userId").asLong();
                    if (c.has("challengeId")) challengeId = c.get("challengeId").asLong();
                } catch (Exception ignore) {
                    log.warn("[Iamport] custom_data 파싱 실패: {}", customNode.toString());
                }
            }

            log.info("[Iamport] 결제 조회 성공: impUid={}, merchantUid={}, amount={}, status={}",
                    impUid, merchantUid, amountWon, status);

            return IamportPaymentDTO.builder()
                    .status(status)
                    .merchantUid(merchantUid)
                    .impUid(impUid)
                    .amount(BigDecimal.valueOf(amountWon))
                    .paidAt(paidAtEpochSec)
                    .userId(userId)
                    .challengeId(challengeId)
                    .name(name)
                    .pgProvider(pgProvider)
                    .build();

        } catch (Exception e) {
            throw new IllegalStateException("결제 조회 응답 파싱 실패", e);
        }
    }

    @Override
    @Deprecated
    public IamportPaymentDTO fetchPayment(String impUid) {
        return fetchPaymentByImpUid(impUid);
    }
}