package com.example.transit.service.ride;

import com.example.transit.domain.Payment;
import com.example.transit.domain.TaxiRide;
import com.example.transit.domain.ledger.AccountKind;
import com.example.transit.domain.ledger.AccountOwnerType;
import com.example.transit.repository.PaymentRepository;
import com.example.transit.service.ledger.LedgerAccountService;
import com.example.transit.service.point.PointService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이용 완료 시점의 결제 처리. 호출부(RideService)의 트랜잭션 안에서 돌며, 여기서 실패하면
 * 이용 완료까지 통째로 롤백된다(포인트 부족으로 결제를 못 하면 이용은 완료로 넘어가지 않는다).
 * <p>
 * 페이백은 요금의 5%(정수 내림). 포인트 차감·적립은 안정적인 idempotency_key
 * ("SPEND:ride:{id}", "PAYBACK:ride:{id}")로 걸어, 재시도가 이중 반영되지 않게 한다.
 */
@Service
public class PaymentService {

    private static final int PAYBACK_PERCENT = 5;

    private final PaymentRepository payments;
    private final PointService pointService;
    private final LedgerAccountService ledgerAccounts;

    public PaymentService(PaymentRepository payments, PointService pointService,
                          LedgerAccountService ledgerAccounts) {
        this.payments = payments;
        this.pointService = pointService;
        this.ledgerAccounts = ledgerAccounts;
    }

    @Transactional
    public Payment settleCompletion(TaxiRide ride, long pointToUse) {
        if (pointToUse < 0 || pointToUse > ride.getFareAmount()) {
            throw new RideException(RideException.Reason.INVALID_POINT_AMOUNT,
                    "사용 포인트는 0 이상 요금 이하여야 합니다");
        }
        // 이용 상태 전이 가드가 이미 이중 완료를 막지만, 결제 유니크(ride_id)로 한 번 더 방어한다.
        Payment existing = payments.findByRideId(ride.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }

        long pointEarned = ride.getFareAmount() * PAYBACK_PERCENT / 100;
        Payment payment = payments.save(new Payment(
                ride.getId(), ride.getUserId(), ride.getPartnerId(),
                ride.getFareAmount(), pointToUse, pointEarned));

        // 제휴사 CASH 계정을 이 시점에 provisioning한다(첫 매출 발생 시). 정산 배치는
        // "생성 후 같은 트랜잭션에서 재조회"가 곤란하므로, 배치가 아니라 여기서 미리 만든다.
        ledgerAccounts.getOrCreate(AccountOwnerType.PARTNER, ride.getPartnerId(), AccountKind.CASH);

        if (pointToUse > 0) {
            pointService.spend(ride.getUserId(), pointToUse,
                    "SPEND:ride:" + ride.getId(), "ride", ride.getId());
        }
        pointService.earn(ride.getUserId(), pointEarned,
                "PAYBACK:ride:" + ride.getId(), "ride", ride.getId());

        return payment;
    }
}
