# MongoDB Support for Spring Cloud Task

## 개요

Spring Cloud Task에 MongoDB 저장소 지원을 추가했습니다. 기존 JDBC 구현과 동일한 패턴을 따르며, 사용자가 관계형 데이터베이스 대신 MongoDB를 선택할 수 있습니다.

---

## 주요 구현 내용

### 1. Auto-Configuration
**파일**: `MongoTaskAutoConfiguration.java`

MongoDB 사용 시 자동으로 활성화됩니다:
- **조건**: `spring.cloud.task.repository-type=mongodb` 설정 필요
- **Optional Dependency**: MongoDB가 없어도 라이브러리 컴파일 가능

```yaml
spring:
  cloud:
    task:
      repository-type: mongodb
```

### 2. 핵심 컴포넌트

| Component | 설명 | 파일 |
|-----------|------|------|
| `MongoTaskExecutionDao` | Task CRUD 및 시퀀스 관리 | `MongoTaskExecutionDao.java` |
| `MongoTaskConfigurer` | TaskConfigurer 구현체 | `MongoTaskConfigurer.java` |
| `MongoTaskRepositoryInitializer` | 컬렉션 및 인덱스 초기화 | `MongoTaskRepositoryInitializer.java` |
| `MongoLockRepository` | 분산 락 (단일 인스턴스 실행) | `MongoLockRepository.java` |

### 3. MongoDB Collections

Task 실행 정보를 다음 5개 컬렉션에 저장합니다:

```
{tablePrefix}task_executions           - 메인 실행 정보 (ID, 이름, 시간, 상태 등)
{tablePrefix}task_execution_parameters - 실행 파라미터
{tablePrefix}task_batch_associations   - Spring Batch Job 연관 정보
{tablePrefix}task_sequence             - ID 자동 생성용 시퀀스
{tablePrefix}task_locks                - 분산 락 (단일 인스턴스 실행 보장)
```

### 4. 주요 기능

- ✅ **Atomic ID Generation**: `findAndModify`로 thread-safe한 시퀀스 생성
- ✅ **인덱스 최적화**: 조회 성능 향상을 위한 복합 인덱스 (taskName, startTime, endTime 등)
- ✅ **분산 락**: MongoDB 기반 분산 락 with TTL (만료 자동 정리)
- ✅ **트랜잭션 지원**: PlatformTransactionManager 자동 감지 및 적용
- ✅ **Immutability**: TaskExecution의 executionId는 생성 후 불변

---

## 기술적 구현 세부사항

### 1. Optional Dependency Pattern

MongoDB는 optional 의존성으로 선언되어, MongoDB가 없는 환경에서도 라이브러리가 동작합니다:

```xml
<dependency>
    <groupId>org.springframework.data</groupId>
    <artifactId>spring-data-mongodb</artifactId>
    <optional>true</optional>
</dependency>
```

이로 인해 `TaskExecutionDaoFactoryBean`에서 리플렉션을 사용하여 MongoDB DAO를 생성합니다:

```java
// Object 타입 생성자 - 컴파일 타임 의존성 회피
public MongoTaskExecutionDao(Object mongoOperations, TaskProperties taskProperties) {
    if (!(mongoOperations instanceof MongoOperations)) {
        throw new IllegalArgumentException("...");
    }
    // ...
}
```

**장점**:
- JDBC만 사용하는 사용자는 MongoDB 의존성 불필요
- 라이브러리 의존성 트리 최소화

**단점**:
- 타입 안전성 낮음 (런타임에 체크)

### 2. Sequence 관리

MongoDB에는 auto-increment가 없으므로, `findAndModify`를 사용한 원자적 증가:

```java
@Override
public long getNextExecutionId() {
    Query query = new Query(Criteria.where("_id").is("task_seq"));
    Update update = new Update().inc("sequence", 1);
    FindAndModifyOptions options = new FindAndModifyOptions()
        .upsert(true)
        .returnNew(true);

    TaskSequence sequence = mongoOperations.findAndModify(
        query, update, options, TaskSequence.class,
        sequenceCollection
    );
    return sequence.getSequence();
}
```

- **Thread-safe**: MongoDB 서버에서 원자적으로 처리
- **시작값**: 0에서 시작, 첫 호출 시 1 반환 (JDBC와 동일)

### 3. 분산 락 메커니즘

`MongoLockRepository`는 Spring Integration의 `LockRegistry` 인터페이스를 구현:

**주요 개선사항**:
- ✅ `tryLock(long time, TimeUnit unit)` 타임아웃 구현 (이전 구현에서 누락됨)
- ✅ 만료된 락 자동 정리
- ✅ 재진입 가능 (같은 클라이언트의 TTL 연장)

```java
// 100ms 간격으로 재시도, 최대 5초 대기
if (lock.tryLock(5, TimeUnit.SECONDS)) {
    try {
        // Task 실행
    } finally {
        lock.unlock();
    }
}
```

---

## 검토 필요 사항

### 1. Bean Configuration 우선순위

`MongoTaskAutoConfiguration`에서 TransactionManager가 있을 때와 없을 때 두 개의 TaskConfigurer bean을 생성합니다:

```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnBean(PlatformTransactionManager.class)
public TaskConfigurer taskConfigurer(..., PlatformTransactionManager transactionManager) {
    return new MongoTaskConfigurer(..., transactionManager);
}

@Bean
@ConditionalOnMissingBean
public TaskConfigurer taskConfigurerWithoutTransactionManager(...) {
    return new MongoTaskConfigurer(...);
}
```

- TransactionManager가 있으면 첫 번째 bean 생성
- 없으면 두 번째 bean 생성 (fallback)
- Bean 이름이 다르므로 충돌 없음

**검토 포인트**: 이 패턴이 Spring Boot의 auto-configuration 베스트 프랙티스와 일치하는지 확인 필요

### 2. Sequence 초기값 일관성

현재 구현:
- 시퀀스 초기값: 0
- 첫 `getNextExecutionId()` 호출: 1 반환

JDBC 구현과 동일하지만, 다른 데이터베이스들의 시퀀스 동작과 일관성을 확인해야 합니다.

### 3. 성능 고려사항

**Sequence 생성**:
- `findAndModify`는 원자적이지만 락을 사용하므로 고처리량 환경에서 병목 가능
- 대안: Batch ID 예약 (향후 최적화 가능)

**인덱스 전략**:
- 현재 6개 인덱스 생성 (task_executions에 5개, parameters에 1개 등)
- 대용량 환경에서 인덱스 오버헤드 모니터링 필요

---

## 테스트

### Test Coverage

| 테스트 클래스 | 설명 | 테스트 수 |
|--------------|------|----------|
| `MongoTaskExecutionDaoTests` | DAO CRUD 및 조회 테스트 | 49개 |
| `MongoLockRepositoryTests` | 분산 락 및 동시성 테스트 | 15개 |
| `MongoTaskAutoConfigurationTests` | Auto-configuration 조건 테스트 | 7개 |
| `MongoTaskConfigurerTests` | TaskConfigurer 생성 테스트 | 5개 |
| `MongoTaskRepositoryInitializerTests` | 초기화 및 인덱스 테스트 | 8개 |

**총 84개 테스트 통과**

### 특징
- ✅ `BaseTaskExecutionDaoTestCases` 상속으로 JDBC와 동일한 테스트 케이스 통과
- ✅ Testcontainers 사용 (실제 MongoDB 6.0)
- ✅ 동시성 테스트 포함

### 테스트 실행

```bash
# 모든 MongoDB 테스트
./mvnw test -pl spring-cloud-task-core -Dtest="Mongo*"

# 특정 테스트 클래스
./mvnw test -pl spring-cloud-task-core -Dtest=MongoTaskExecutionDaoTests
```

---

## 사용 예시

### 기본 설정

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/taskdb
  cloud:
    task:
      repository-type: mongodb
      table-prefix: "TASK_"
```

### 단일 인스턴스 실행 (분산 락)

```yaml
spring:
  cloud:
    task:
      repository-type: mongodb
      single-instance-enabled: true
      single-instance-lock-ttl: 30000  # 30초
```

### Application Code

```java
@SpringBootApplication
@EnableTask
public class MyTaskApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyTaskApplication.class, args);
    }

    @Bean
    public CommandLineRunner taskRunner() {
        return args -> {
            System.out.println("MongoDB에 Task 실행 정보 저장됨!");
            // Task 로직
        };
    }
}
```

### 트랜잭션 사용

```java
@Configuration
public class TaskConfiguration {

    @Bean
    public MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
}
```

---

## Breaking Changes

**없음**

- 기존 JDBC 사용자에게 영향 없음
- MongoDB는 opt-in 방식으로 활성화
- 기존 API 변경 없음

---

## Checklist

- [x] JDBC 구현 패턴 준수
- [x] `BaseTaskExecutionDaoTestCases` 상속 및 통과
- [x] Testcontainers 기반 통합 테스트
- [x] Auto-configuration 구현
- [x] 분산 락 구현 및 타임아웃 수정
- [x] 인덱스 최적화
- [x] JavaDoc 작성
- [x] Immutability 개선 (`setExecutionId` 제거)
- [x] 코드 리뷰 반영

---

## 향후 개선 가능 사항

1. **Reactive MongoDB 지원**: WebFlux 환경 지원
2. **MongoDB Transactions 활용**: 현재는 single document operations
3. **Batch ID 예약**: Sequence 생성 성능 최적화
4. **TTL Index**: 만료된 락 자동 정리
5. **Aggregation Pipeline**: 복잡한 조회 쿼리 최적화

---

## Related Issues

Closes #953

---

**Note**: 이 PR은 MongoDB 기본 지원 추가가 목표입니다. 추가 최적화 및 고급 기능은 후속 PR에서 진행 가능합니다.