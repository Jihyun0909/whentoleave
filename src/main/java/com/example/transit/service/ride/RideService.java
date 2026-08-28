package com.example.transit.service.ride;

import com.example.transit.domain.AuditEvent;
import com.example.transit.domain.Partner;
import com.example.transit.domain.Payment;
import com.example.transit.domain.RideStatus;
import com.example.transit.domain.TaxiRide;
import com.example.transit.repository.PartnerRepository;
import com.example.transit.repository.TaxiRideRepository;
import com.example.transit.service.audit.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 가상 택시 이용의 생명주기(요청 → 시작 → 완료 / 취소). 완료 시 {@link PaymentService}로
 * 결제·포인트 정산이 같은 트랜잭션에서 일어난다 - 포인트 부족 등으로 결제가 실패하면
 * 이용 완료도 롤백된다.
 */
@Service
public class RideService {

    private final TaxiRideRepository rides;
    private final PartnerRepository partners;
    private final PaymentService paymentService;
    private final AuditLogWriter audit;

    public RideService(TaxiRideRepository rides, PartnerRepository partners,
                       PaymentService paymentService, AuditLogWriter audit) {
        this.rides = rides;
        this.partners = partners;
        this.paymentService = paymentService;
        this.audit = audit;
    }

    public record CompletionResult(TaxiRide ride, Payment payment) {
    }

    @Transactional
    public TaxiRide request(long userId, long partnerId, String origin, String destination, long fareAmount) {
        Partner partner = partners.findById(partnerId)
                .filter(Partner::isActive)
                .orElseThrow(() -> new RideException(RideException.Reason.PARTNER_NOT_AVAILABLE,
                        "이용 가능한 제휴사가 아닙니다"));
        if (fareAmount <= 0) {
            throw new RideException(RideException.Reason.INVALID_FARE, "요금은 0보다 커야 합니다");
        }
        return rides.save(new TaxiRide(userId, partner.getId(), origin, destination, fareAmount));
    }

    @Transactional
    public TaxiRide start(long userId, long rideId) {
        TaxiRide ride = load(userId, rideId);
        transition(ride, RideStatus.IN_PROGRESS);
        return ride;
    }

    @Transactional
    public CompletionResult complete(long userId, long rideId, long pointToUse) {
        TaxiRide ride = load(userId, rideId);
        transition(ride, RideStatus.COMPLETED);
        Payment payment = paymentService.settleCompletion(ride, pointToUse);
        return new CompletionResult(ride, payment);
    }

    @Transactional
    public TaxiRide cancel(long userId, long rideId) {
        TaxiRide ride = load(userId, rideId);
        transition(ride, RideStatus.CANCELLED);
        return ride;
    }

    @Transactional(readOnly = true)
    public List<TaxiRide> listMine(long userId) {
        return rides.findByUserIdOrderByIdDesc(userId);
    }

    @Transactional(readOnly = true)
    public TaxiRide getMine(long userId, long rideId) {
        return load(userId, rideId);
    }

    private void transition(TaxiRide ride, RideStatus next) {
        try {
            ride.transitionTo(next);
        } catch (IllegalStateException e) {
            audit.record(AuditEvent.ILLEGAL_STATE_TRANSITION, ride.getUserId(), "ride", ride.getId(),
                    e.getMessage());
            throw new RideException(RideException.Reason.ILLEGAL_STATE, e.getMessage());
        }
    }

    private TaxiRide load(long userId, long rideId) {
        return rides.findByIdAndUserId(rideId, userId)
                .orElseThrow(() -> new RideException(RideException.Reason.RIDE_NOT_FOUND,
                        "이용 내역을 찾을 수 없습니다"));
    }
}
