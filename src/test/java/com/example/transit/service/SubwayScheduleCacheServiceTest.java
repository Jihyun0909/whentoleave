package com.example.transit.service;

import com.example.transit.domain.DayType;
import com.example.transit.domain.SubwaySchedule;
import com.example.transit.repository.SubwayScheduleRepository;
import com.example.transit.service.client.SeoulSubwayLastTrainApiClient;
import com.example.transit.service.client.TagoSubwayApiClient;
import com.example.transit.service.client.dto.SeoulSubwayLastTrainResponse;
import com.example.transit.service.client.dto.TagoBusArrivalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TAGO가 시간표를 아예 안 주는 역(사당·종합운동장·창동 등, 2026-08-25 실사용 중 발견)에 대한
 * 서울교통공사 SearchLastTrainTimeByIDService 폴백을 검증한다.
 */
class SubwayScheduleCacheServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 13); // 목요일(평일)

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void TAGO에_데이터가_있으면_서울교통공사_API는_안_부른다() throws Exception {
        TagoBusArrivalResponse tagoData = tagoResponse("강남", "053630");
        TagoSubwayApiClient tago = new TagoSubwayApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode,
                                                          String dailyTypeCode) {
                return tagoData;
            }
        };
        SeoulSubwayLastTrainApiClient seoul = new SeoulSubwayLastTrainApiClient("http://dummy", "dummy") {
            @Override
            public SeoulSubwayLastTrainResponse findSchedule(String stationCode, String weekTag, String inOutTag,
                                                               int maxRows) {
                throw new AssertionError("TAGO에 데이터가 있으면 서울교통공사 API를 부르면 안 됨");
            }
        };
        SubwayScheduleCacheService service = new SubwayScheduleCacheService(new InMemoryRepository(), tago, seoul);

        List<SubwaySchedule> result = service.getLastTrains("MTRS12222", 1, TODAY);

        assertEquals(1, result.size());
        assertEquals("강남", result.get(0).getEndStationName());
    }

    /** 사당역(MTRS12226) 실사용 케이스 - TAGO가 빈 목록을 주면 역번호(226)를 뽑아 0226으로 재조회한다. */
    @Test
    void TAGO가_비어있으면_역번호를_뽑아_서울교통공사_API로_재시도한다() throws Exception {
        TagoSubwayApiClient tago = new TagoSubwayApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode,
                                                          String dailyTypeCode) {
                return emptyTagoResponse();
            }
        };
        SeoulSubwayLastTrainApiClient seoul = new SeoulSubwayLastTrainApiClient("http://dummy", "dummy") {
            @Override
            public SeoulSubwayLastTrainResponse findSchedule(String stationCode, String weekTag, String inOutTag,
                                                               int maxRows) {
                assertEquals("0226", stationCode);
                assertEquals("1", weekTag); // TODAY는 평일
                assertEquals("1", inOutTag); // wayCode=1(상행) 그대로
                return seoulResponse(row("서울대입구", "24:56:30"), row("신도림", "24:43:30"));
            }
        };
        SubwayScheduleCacheService service = new SubwayScheduleCacheService(new InMemoryRepository(), tago, seoul);

        List<SubwaySchedule> result = service.getLastTrains("MTRS12226", 1, TODAY);

        assertEquals(2, result.size());
        assertEquals("서울대입구", result.get(0).getEndStationName());
        assertEquals(LocalTime.of(0, 56, 30), result.get(0).getDepartureTime());
        assertTrue(result.get(0).isNextDay()); // 24시를 넘으므로 다음날 새벽
    }

    /** 코레일 위탁 구간 등(TAGO id에 3자리 역번호가 안 잡히는 경우)은 서울교통공사 폴백도 시도하지 않는다. */
    @Test
    void 역번호를_못_뽑으면_서울교통공사_API를_부르지_않는다() throws Exception {
        TagoSubwayApiClient tago = new TagoSubwayApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode,
                                                          String dailyTypeCode) {
                return emptyTagoResponse();
            }
        };
        SeoulSubwayLastTrainApiClient seoul = new SeoulSubwayLastTrainApiClient("http://dummy", "dummy") {
            @Override
            public SeoulSubwayLastTrainResponse findSchedule(String stationCode, String weekTag, String inOutTag,
                                                               int maxRows) {
                throw new AssertionError("역번호를 못 뽑으면 서울교통공사 API를 부르면 안 됨");
            }
        };
        SubwayScheduleCacheService service = new SubwayScheduleCacheService(new InMemoryRepository(), tago, seoul);

        assertTrue(service.getLastTrains("COORD:127.0,37.0", 1, TODAY).isEmpty());
    }

    /** 둘 다 못 찾으면(서울교통공사 API 호출 실패 등) 예외 대신 빈 목록을 준다. */
    @Test
    void 서울교통공사_API도_실패하면_빈_목록을_준다() throws Exception {
        TagoSubwayApiClient tago = new TagoSubwayApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode,
                                                          String dailyTypeCode) {
                return emptyTagoResponse();
            }
        };
        SeoulSubwayLastTrainApiClient seoul = new SeoulSubwayLastTrainApiClient("http://dummy", "dummy") {
            @Override
            public SeoulSubwayLastTrainResponse findSchedule(String stationCode, String weekTag, String inOutTag,
                                                               int maxRows) {
                throw new IllegalStateException("API 실패");
            }
        };
        SubwayScheduleCacheService service = new SubwayScheduleCacheService(new InMemoryRepository(), tago, seoul);

        assertTrue(service.getLastTrains("MTRS12226", 1, TODAY).isEmpty());
    }

    /** 키가 없으면(isConfigured=false) 서울교통공사 폴백을 시도하지 않는다. */
    @Test
    void 서울교통공사_키가_없으면_폴백을_시도하지_않는다() throws Exception {
        TagoSubwayApiClient tago = new TagoSubwayApiClient("http://dummy", "dummy") {
            @Override
            public TagoBusArrivalResponse fetchSchedule(String subwayStationId, String upDownTypeCode,
                                                          String dailyTypeCode) {
                return emptyTagoResponse();
            }
        };
        SeoulSubwayLastTrainApiClient seoul = new SeoulSubwayLastTrainApiClient("http://dummy", "") {
            @Override
            public SeoulSubwayLastTrainResponse findSchedule(String stationCode, String weekTag, String inOutTag,
                                                               int maxRows) {
                throw new AssertionError("키 없이 폴백을 시도하면 안 됨");
            }
        };
        SubwayScheduleCacheService service = new SubwayScheduleCacheService(new InMemoryRepository(), tago, seoul);

        assertTrue(service.getLastTrains("MTRS12226", 1, TODAY).isEmpty());
    }

    private TagoBusArrivalResponse tagoResponse(String endStationName, String depTime) {
        String json = """
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                  "body": { "items": { "item":
                    { "endSubwayStationNm": "%s", "depTime": "%s" }
                  } } } }
                """.formatted(endStationName, depTime);
        return mapper.readValue(json, TagoBusArrivalResponse.class);
    }

    private TagoBusArrivalResponse emptyTagoResponse() {
        return mapper.readValue("""
                { "response": { "header": { "resultCode": "00", "resultMsg": "NORMAL SERVICE." },
                  "body": { "items": {} } } }
                """, TagoBusArrivalResponse.class);
    }

    private SeoulSubwayLastTrainResponse seoulResponse(SeoulSubwayLastTrainResponse.Row... rows) {
        return new SeoulSubwayLastTrainResponse(new SeoulSubwayLastTrainResponse.Body(
                rows.length, new SeoulSubwayLastTrainResponse.Result("INFO-000", "정상 처리되었습니다"),
                List.of(rows)));
    }

    private SeoulSubwayLastTrainResponse.Row row(String subwayEnd, String leftTime) {
        return new SeoulSubwayLastTrainResponse.Row("0226", "사당", subwayEnd, leftTime, "1", "1");
    }

    private static class InMemoryRepository implements SubwayScheduleRepository {
        private final List<SubwaySchedule> saved = new ArrayList<>();

        @Override
        public List<SubwaySchedule> findByStationIdAndWayCodeAndDayType(String stationId, Integer wayCode,
                                                                          DayType dayType) {
            return saved.stream()
                    .filter(s -> s.getStationId().equals(stationId) && s.getWayCode().equals(wayCode)
                            && s.getDayType() == dayType)
                    .toList();
        }

        @Override
        public boolean existsByStationIdAndWayCodeAndDayType(String stationId, Integer wayCode, DayType dayType) {
            return !findByStationIdAndWayCodeAndDayType(stationId, wayCode, dayType).isEmpty();
        }

        @Override
        public <S extends SubwaySchedule> S save(S entity) {
            saved.add(entity);
            return entity;
        }

        // --- 아래는 이 테스트에서 쓰지 않는 JpaRepository 메서드들 ---
        @Override public void flush() { }
        @Override public <S extends SubwaySchedule> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends SubwaySchedule> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<SubwaySchedule> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public SubwaySchedule getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public SubwaySchedule getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public SubwaySchedule getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends SubwaySchedule> List<S> findAll(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends SubwaySchedule> List<S> findAll(Example<S> example, Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends SubwaySchedule> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public List<SubwaySchedule> findAll() { return List.copyOf(saved); }
        @Override public List<SubwaySchedule> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public List<SubwaySchedule> findAll(Sort sort) { throw new UnsupportedOperationException(); }
        @Override public Page<SubwaySchedule> findAll(Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public Optional<SubwaySchedule> findById(Long id) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(Long id) { throw new UnsupportedOperationException(); }
        @Override public long count() { return saved.size(); }
        @Override public void deleteById(Long id) { throw new UnsupportedOperationException(); }
        @Override public void delete(SubwaySchedule entity) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends SubwaySchedule> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { saved.clear(); }
        @Override public <S extends SubwaySchedule> Optional<S> findOne(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends SubwaySchedule> Page<S> findAll(Example<S> example, Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends SubwaySchedule> long count(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends SubwaySchedule> boolean exists(Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends SubwaySchedule, R> R findBy(Example<S> example, Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }
}
