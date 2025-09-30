# Spring Cloud Task MongoDB Implementation Guide

## Table of Contents
- [1. Architecture Overview](#1-architecture-overview)
- [2. Auto-Configuration](#2-auto-configuration)
- [3. DAO Implementation](#3-dao-implementation)
- [4. Distributed Lock Mechanism](#4-distributed-lock-mechanism)
- [5. Test Strategy](#5-test-strategy)
- [6. Usage Guide](#6-usage-guide)
- [7. JDBC vs MongoDB Comparison](#7-jdbc-vs-mongodb-comparison)
- [8. Migration Guide](#8-migration-guide)
- [9. Improvements Made](#9-improvements-made)

---

## 1. Architecture Overview

MongoDB implementation follows the same pattern as the existing JDBC implementation, allowing Spring Cloud Task to use MongoDB instead of relational databases.

### Component Structure

```
┌─────────────────────────────────────────┐
│   MongoTaskAutoConfiguration           │  ← Auto-configuration
│   - MongoTaskRepositoryInitializer      │
│   - MongoTaskConfigurer                 │
│   - MongoLockRepository (optional)      │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│   MongoTaskConfigurer                   │  ← TaskConfigurer implementation
│   - TaskRepository                      │
│   - TaskExplorer                        │
│   - TransactionManager (optional)       │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│   MongoTaskExecutionDao                 │  ← Core DAO
│   - Task CRUD operations                │
│   - Sequence management                 │
│   - Batch associations                  │
└─────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────┐
│   MongoDB Collections                   │
│   - task_executions                     │
│   - task_execution_parameters           │
│   - task_batch_associations             │
│   - task_sequence                       │
│   - task_locks                          │
└─────────────────────────────────────────┘
```

### Key Components

| Component | Purpose | File |
|-----------|---------|------|
| **MongoTaskAutoConfiguration** | Auto-configuration for MongoDB support | `MongoTaskAutoConfiguration.java` |
| **MongoTaskConfigurer** | TaskConfigurer implementation | `MongoTaskConfigurer.java` |
| **MongoTaskExecutionDao** | DAO for task execution CRUD | `MongoTaskExecutionDao.java` |
| **MongoLockRepository** | Distributed lock for single-instance execution | `MongoLockRepository.java` |
| **MongoTaskRepositoryInitializer** | Collection and index initialization | `MongoTaskRepositoryInitializer.java` |
| **MongoTaskMigration** | Migration utilities between versions | `MongoTaskMigration.java` |

---

## 2. Auto-Configuration

### 2.1 Activation Conditions

```java
@AutoConfiguration
@ConditionalOnClass({ MongoOperations.class })          // MongoDB dependency required
@ConditionalOnBean(MongoOperations.class)               // MongoOperations bean must exist
@ConditionalOnProperty(                                  // Property must be set
    prefix = "spring.cloud.task",
    name = "repository-type",
    havingValue = "mongodb",
    matchIfMissing = false                               // Explicit configuration required
)
@EnableConfigurationProperties(TaskProperties.class)
public class MongoTaskAutoConfiguration {
    // ...
}
```

### 2.2 Configuration Properties

**application.yml**:
```yaml
spring:
  cloud:
    task:
      repository-type: mongodb        # Enable MongoDB support
      table-prefix: "TASK_"           # Collection prefix
      initialize-enabled: true        # Enable initialization
      single-instance-enabled: false  # Enable distributed lock (optional)
      single-instance-lock-ttl: 30000 # Lock TTL in milliseconds
```

### 2.3 Created Beans

#### MongoTaskRepositoryInitializer
```java
@Bean
@ConditionalOnMissingBean(MongoTaskRepositoryInitializer.class)
public MongoTaskRepositoryInitializer mongoTaskRepositoryInitializer(
        MongoOperations mongoOperations,
        TaskProperties taskProperties) {
    return new MongoTaskRepositoryInitializer(mongoOperations, taskProperties);
}
```

**Purpose**:
- Creates collections and indexes
- Initializes sequence counter
- Runs automatically on application startup (`InitializingBean`)

#### MongoLockRepository (Optional)
```java
@Bean
@ConditionalOnMissingBean(LockRegistry.class)
@ConditionalOnProperty(prefix = "spring.cloud.task",
                      name = "single-instance-enabled",
                      havingValue = "true")
public LockRegistry mongoLockRegistry(MongoOperations mongoOperations,
                                     TaskProperties taskProperties) {
    return new MongoLockRepository(mongoOperations, taskProperties);
}
```

**Purpose**:
- Distributed lock for single-instance execution
- Only created when `single-instance-enabled=true`
- Implements Spring Integration's `LockRegistry` interface

#### TaskConfigurer
```java
// With TransactionManager (higher priority)
@Bean
@ConditionalOnMissingBean
@ConditionalOnBean(PlatformTransactionManager.class)
public TaskConfigurer taskConfigurer(MongoOperations mongoOperations,
        TaskProperties taskProperties,
        PlatformTransactionManager transactionManager) {
    return new MongoTaskConfigurer(mongoOperations, taskProperties, transactionManager);
}

// Without TransactionManager (fallback)
@Bean
@ConditionalOnMissingBean
public TaskConfigurer taskConfigurerWithoutTransactionManager(
        MongoOperations mongoOperations,
        TaskProperties taskProperties) {
    return new MongoTaskConfigurer(mongoOperations, taskProperties);
}
```

**Improvement**:
- ✅ Fixed bean conflict issue
- ✅ TransactionManager-enabled bean has priority
- ✅ Different bean names prevent conflicts

---

## 3. DAO Implementation

### 3.1 MongoDB Collections Structure

#### 1. task_executions - Main execution information
```javascript
{
  "taskExecutionId": 1,
  "taskName": "myTask",
  "startTime": ISODate("2025-09-30T10:30:00Z"),
  "endTime": ISODate("2025-09-30T10:35:00Z"),
  "exitCode": 0,
  "exitMessage": "Success",
  "errorMessage": null,
  "lastUpdated": ISODate("2025-09-30T10:35:00Z"),
  "externalExecutionId": "ext-123",
  "parentExecutionId": null
}
```

#### 2. task_execution_parameters - Execution parameters
```javascript
{
  "taskExecutionId": 1,
  "taskParam": "--spring.profiles.active=prod"
}
```

#### 3. task_batch_associations - Batch Job associations
```javascript
{
  "taskExecutionId": 1,
  "jobExecutionId": 100
}
```

#### 4. task_sequence - ID generation sequence
```javascript
{
  "_id": "task_seq",
  "sequence": 1
}
```

#### 5. task_locks - Distributed locks
```javascript
{
  "_id": "myTask:default",
  "lockKey": "myTask",
  "region": "default",
  "clientId": "uuid-123",
  "createdDate": ISODate("2025-09-30T10:30:00Z"),
  "expirationDate": ISODate("2025-09-30T10:35:00Z")
}
```

### 3.2 Core Operations

#### A. Sequence Generation

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
        getCollectionName(TASK_SEQUENCE_COLLECTION)
    );
    return sequence != null ? sequence.getSequence() : 1L;
}
```

**How it works**:
- Uses MongoDB's `findAndModify` atomic operation
- `upsert(true)`: Creates document if it doesn't exist
- `returnNew(true)`: Returns incremented value
- **Thread-safe**: Atomically processed by MongoDB server

#### B. Task Creation

```java
@Override
public TaskExecution createTaskExecution(String taskName, LocalDateTime startTime,
        List<String> arguments, String externalExecutionId, Long parentExecutionId) {

    // 1. Generate next ID
    long taskExecutionId = getNextExecutionId();

    // 2. Save main document
    TaskExecutionDocument document = new TaskExecutionDocument();
    document.setTaskExecutionId(taskExecutionId);
    document.setTaskName(taskName);
    document.setStartTime(startTime);
    // ... set other fields
    mongoOperations.save(document, getCollectionName(TASK_EXECUTION_COLLECTION));

    // 3. Save parameters
    if (arguments != null) {
        for (String argument : arguments) {
            TaskExecutionParameterDocument paramDoc = new TaskExecutionParameterDocument();
            paramDoc.setTaskExecutionId(taskExecutionId);
            paramDoc.setTaskParam(argument);
            mongoOperations.save(paramDoc, getCollectionName(TASK_EXECUTION_PARAMS_COLLECTION));
        }
    }

    return convertToTaskExecution(document, arguments);
}
```

**Comparison with JDBC**:
- JDBC: `INSERT` SQL + JDBC batch insert
- MongoDB: `mongoOperations.save()` + multiple calls
- **Transactions**: MongoDB 4.0+ supports multi-document transactions

#### C. Pagination Query

```java
@Override
public Page<TaskExecution> findTaskExecutionsByName(String taskName, Pageable pageable) {
    // 1. Create query with pagination
    Query query = new Query(Criteria.where(TASK_NAME_KEY).is(taskName))
        .with(pageable);  // Apply Spring Data Pageable

    // 2. Find documents
    List<TaskExecutionDocument> documents = mongoOperations.find(
        query, TaskExecutionDocument.class,
        getCollectionName(TASK_EXECUTION_COLLECTION)
    );

    // 3. Get total count
    long total = getTaskExecutionCountByTaskName(taskName);

    // 4. Convert and create page
    List<TaskExecution> taskExecutions = convertToTaskExecutions(documents);
    return new PageImpl<>(taskExecutions, pageable, total);
}
```

**Spring Data MongoDB Integration**:
- `Pageable` object applied directly with `.with(pageable)`
- Implements Spring Data's `Page` interface with `PageImpl`
- Same functionality as JDBC's `LIMIT/OFFSET`

### 3.3 Index Strategy

| Collection | Index | Purpose |
|-----------|-------|---------|
| **task_executions** | `taskName` | Fast lookup by task name |
| | `startTime` (DESC) | Query latest executions |
| | `endTime` | Find running tasks |
| | `externalExecutionId` | Query by external ID |
| | `taskName + endTime` (compound) | Find running tasks by name |
| | `parentExecutionId` | Parent-child relationship |
| **task_execution_parameters** | `taskExecutionId` | Fast parameter lookup |
| **task_batch_associations** | `taskExecutionId`, `jobExecutionId` | Bidirectional lookup |
| **task_locks** | `lockKey + region` (UNIQUE) | Prevent lock conflicts |
| | `expirationDate` | Cleanup expired locks |
| | `clientId` | Owner identification |

#### Index Creation Code

```java
private void createTaskExecutionIndexes(String collectionName) {
    IndexOperations indexOps = mongoOperations.indexOps(collectionName);

    // Single field indexes
    indexOps.createIndex(new Index().on("taskName", Sort.Direction.ASC)
        .named("idx_task_name"));

    indexOps.createIndex(new Index().on("startTime", Sort.Direction.DESC)
        .named("idx_start_time"));

    indexOps.createIndex(new Index().on("endTime", Sort.Direction.ASC)
        .named("idx_end_time"));

    // Compound index for running tasks by name
    indexOps.createIndex(new Index()
        .on("taskName", Sort.Direction.ASC)
        .on("endTime", Sort.Direction.ASC)
        .named("idx_task_name_end_time"));
}
```

---

## 4. Distributed Lock Mechanism

### 4.1 Overview

Distributed lock system ensures single-instance execution. When multiple application instances run simultaneously, only one task executes at a time.

### 4.2 Lock Acquisition Flow

```
┌─────────────────────────────────────────────────────────────┐
│ 1. tryLock() called                                          │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. cleanupExpiredLocks() - Remove expired locks              │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. Try to INSERT lock document                               │
│    - id: "lockKey:region"                                    │
│    - clientId: UUID                                          │
│    - expirationDate: now + TTL                               │
└──────────────────────┬──────────────────────────────────────┘
                       ↓
              ┌────────┴────────┐
              │                 │
        ✅ SUCCESS          ❌ DuplicateKeyException
              │                 │
              ↓                 ↓
      Lock acquired     ┌─────────────────────────┐
                       │ Check if expired         │
                       │ (expirationDate < now)   │
                       └──────────┬───────────────┘
                                  ↓
                         ┌────────┴────────┐
                         │                 │
                    ✅ Expired        ❌ Still valid
                         │                 │
                         ↓                 ↓
               Take over expired     Check current owner
               lock (change          (clientId == me?)
               clientId)                   │
                         │                 ↓
                         ↓           ┌─────┴─────┐
                   Lock acquired    │            │
                                ✅ Owner     ❌ Different client
                                    │            │
                                    ↓            ↓
                           Extend TTL      Lock failed
                           succeeded
```

### 4.3 Improved tryLock Implementation

**Previous Issue**:
- Ignored `time` and `unit` parameters
- Violated Lock interface contract
- Only tried once and gave up

**Improved Implementation**:
```java
@Override
public boolean tryLock(long time, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(time);
    long startTime = System.currentTimeMillis();
    long retryInterval = 100; // 100ms between retries

    while (true) {
        if (tryLockOnce()) {
            return true;  // Lock acquired
        }

        // Check timeout
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed >= timeoutMillis) {
            return false;  // Timeout
        }

        // Sleep considering remaining time
        long remainingTime = timeoutMillis - elapsed;
        long sleepTime = Math.min(retryInterval, remainingTime);

        if (sleepTime > 0) {
            try {
                Thread.sleep(sleepTime);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
```

**Improvements**:
- ✅ Retries until timeout
- ✅ Complies with Lock interface contract
- ✅ Proper InterruptedException handling

### 4.4 Lock Takeover Mechanism

```java
// 1. INSERT failed (lock already exists)
catch (DuplicateKeyException e) {

    // 2. Try to take over expired lock
    Query query = Query.query(
        Criteria.where("lockKey").is(this.lockKey)
            .and("region").is(this.region)
            .and("expirationDate").lt(now)  // Only expired
    );
    Update update = Update.update("expirationDate", expirationTime)
        .set("clientId", this.clientId);  // Change owner

    var result = mongoOperations.updateFirst(query, update, ...);
    if (result.getModifiedCount() > 0) {
        return true;  // Took over expired lock
    }

    // 3. Check if already owned by me (extend TTL)
    Query ownerQuery = Query.query(
        Criteria.where("lockKey").is(this.lockKey)
            .and("region").is(this.region)
            .and("clientId").is(this.clientId)  // My clientId
    );
    Update ownerUpdate = Update.update("expirationDate", expirationTime);
    var ownerResult = mongoOperations.updateFirst(ownerQuery, ownerUpdate, ...);
    if (ownerResult.getModifiedCount() > 0) {
        return true;  // Extended TTL
    }
}
```

**Scenarios**:

1. **Normal case**: INSERT succeeds → Lock acquired
2. **Expired case**: UPDATE takes over expired lock
3. **Re-entrant case**: Already owned → Extend TTL
4. **Contention case**: Owned by different client → Fail

---

## 5. Test Strategy

### 5.1 Test Structure

```
Test Hierarchy:
┌─────────────────────────────────────────────────────────┐
│ BaseTaskExecutionDaoTestCases (Abstract class)          │
│ - Common test cases                                      │
│ - All DAO implementations must behave identically        │
└──────────────┬──────────────────────────────────────────┘
               │ extends
               ↓
┌─────────────────────────────────────────────────────────┐
│ MongoTaskExecutionDaoTests                               │
│ - MongoDB-specific tests                                 │
│ - Extends BaseTaskExecutionDaoTestCases                  │
│ - Uses real MongoDB via Testcontainers                   │
└─────────────────────────────────────────────────────────┘

Separate Tests:
┌─────────────────────────────────────────────────────────┐
│ MongoTaskAutoConfigurationTests                          │
│ - Auto-configuration behavior                            │
│ - Bean creation conditions                               │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ MongoLockRepositoryTests                                 │
│ - Distributed lock behavior                              │
│ - Concurrency tests                                      │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ MongoTaskConfigurerTests                                 │
│ - TaskConfigurer creation                                │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│ MongoTaskRepositoryInitializerTests                      │
│ - Initialization logic                                   │
│ - Index creation                                         │
└─────────────────────────────────────────────────────────┘
```

### 5.2 Testcontainers Usage

All MongoDB tests use real MongoDB instances:

```java
@Testcontainers
public class MongoTaskExecutionDaoTests extends BaseTaskExecutionDaoTestCases {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:6.0")
        .withExposedPorts(27017);

    @BeforeEach
    public void setup() {
        mongoClient = MongoClients.create(mongoDBContainer.getConnectionString());
        mongoOperations = new MongoTemplate(
            new SimpleMongoClientDatabaseFactory(mongoClient, DATABASE_NAME)
        );
        // ...
    }
}
```

**Benefits**:
- ✅ Tests real MongoDB behavior
- ✅ Validates indexes and transactions
- ✅ No mocking, real environment
- ✅ Works identically in CI/CD pipelines

### 5.3 Test Coverage Summary

| Component | Test File | Key Tests |
|-----------|-----------|-----------|
| **MongoTaskExecutionDao** | MongoTaskExecutionDaoTests | - Full CRUD<br>- Pagination<br>- Sequence<br>- Batch associations<br>- Validation |
| **MongoLockRepository** | MongoLockRepositoryTests | - Lock acquire/release<br>- Expiration/takeover<br>- Re-entrant<br>- Concurrency |
| **MongoTaskAutoConfiguration** | MongoTaskAutoConfigurationTests | - Bean creation conditions<br>- Property binding<br>- Priority |
| **MongoTaskConfigurer** | MongoTaskConfigurerTests | - Repository/Explorer creation<br>- TransactionManager integration |
| **MongoTaskRepositoryInitializer** | MongoTaskRepositoryInitializerTests | - Collection creation<br>- Index creation<br>- Sequence initialization<br>- Idempotency |

### 5.4 Key Test Cases

#### A. CRUD Tests
```java
@Test
public void testCreateTaskExecution() {
    TaskExecution taskExecution = mongoDao.createTaskExecution(
        "testTask", LocalDateTime.now(),
        Arrays.asList("arg1", "arg2"), "ext123"
    );

    assertThat(taskExecution.getExecutionId()).isGreaterThan(0);
    assertThat(taskExecution.getArguments()).hasSize(2);
}

@Test
public void testCompleteTaskExecution() {
    TaskExecution task = mongoDao.createTaskExecution(...);
    mongoDao.completeTaskExecution(task.getExecutionId(), 0,
                                   LocalDateTime.now(), "Success");

    TaskExecution completed = mongoDao.getTaskExecution(task.getExecutionId());
    assertThat(completed.getExitCode()).isEqualTo(0);
    assertThat(completed.getEndTime()).isNotNull();
}
```

#### B. Lock Tests
```java
@Test
public void testTryLockSuccess() {
    Lock lock = lockRepository.obtain("testKey");
    assertThat(lock.tryLock()).isTrue();

    // Verify lock exists in MongoDB
    Query query = Query.query(
        Criteria.where("lockKey").is("testKey")
            .and("region").is("default")
    );
    assertThat(mongoOperations.exists(query, "TASK_task_locks")).isTrue();

    lock.unlock();
}

@Test
public void testLockExpirationAndTakeover() {
    // Create expired lock
    TaskLockDocument expiredLock = new TaskLockDocument();
    expiredLock.expirationDate = LocalDateTime.now().minusMinutes(1);
    mongoOperations.save(expiredLock, "TASK_task_locks");

    // New client should acquire expired lock
    Lock lock = lockRepository.obtain("expiredKey");
    assertThat(lock.tryLock()).isTrue();
}

@Test
public void testConcurrentLockAttempts() throws InterruptedException {
    final int threadCount = 3;
    final CountDownLatch startLatch = new CountDownLatch(1);

    // Start 3 threads simultaneously
    for (int i = 0; i < threadCount; i++) {
        new Thread(() -> {
            Lock lock = lockRepository.obtain(lockKey);
            startLatch.await();
            boolean acquired = lock.tryLock();
            // Record result
        }).start();
    }

    startLatch.countDown();
    // At least one should succeed
    assertThat(successCount).isGreaterThanOrEqualTo(1);
}
```

#### C. Auto-configuration Tests
```java
@Test
public void testMongoTaskAutoConfigurationDisabledByDefault() {
    contextRunner.run(context -> {
        assertThat(context).doesNotHaveBean(MongoTaskRepositoryInitializer.class);
        assertThat(context).doesNotHaveBean(TaskConfigurer.class);
    });
}

@Test
public void testMongoTaskAutoConfigurationEnabled() {
    contextRunner
        .withPropertyValues("spring.cloud.task.repository-type=mongodb")
        .run(context -> {
            assertThat(context).hasSingleBean(MongoTaskRepositoryInitializer.class);
            assertThat(context).hasSingleBean(TaskConfigurer.class);
            assertThat(context).getBean(TaskConfigurer.class)
                .isInstanceOf(MongoTaskConfigurer.class);
        });
}

@Test
public void testMongoLockRegistryEnabled() {
    contextRunner
        .withPropertyValues(
            "spring.cloud.task.repository-type=mongodb",
            "spring.cloud.task.single-instance-enabled=true"
        )
        .run(context -> {
            assertThat(context).hasSingleBean(LockRegistry.class);
        });
}
```

---

## 6. Usage Guide

### 6.1 Basic Configuration

**application.yml**:
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/taskdb
      # Or detailed configuration
      host: localhost
      port: 27017
      database: taskdb
      username: admin
      password: secret

  cloud:
    task:
      repository-type: mongodb       # Enable MongoDB
      table-prefix: "TASK_"          # Collection prefix (optional)
      initialize-enabled: true       # Enable initialization
      single-instance-enabled: false # Distributed lock (optional)
```

### 6.2 Dependencies

**pom.xml**:
```xml
<dependencies>
    <!-- Spring Cloud Task -->
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-task</artifactId>
    </dependency>

    <!-- MongoDB -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-mongodb</artifactId>
    </dependency>
</dependencies>
```

**build.gradle**:
```gradle
dependencies {
    implementation 'org.springframework.cloud:spring-cloud-starter-task'
    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
}
```

### 6.3 Task Application Example

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
            System.out.println("Task running...");
            // Task logic here
            System.out.println("Task completed!");
        };
    }
}
```

### 6.4 Single Instance Configuration

```yaml
spring:
  cloud:
    task:
      repository-type: mongodb
      single-instance-enabled: true         # Enable single instance
      single-instance-lock-ttl: 60000       # Lock TTL (ms)
      single-instance-lock-check-interval: 500  # Check interval (ms)
```

**Behavior**:
- When same task tries to run on multiple instances
- Only the instance that acquires the lock first will execute
- Others will wait or fail

### 6.5 Custom TaskConfigurer

```java
@Configuration
public class CustomTaskConfiguration {

    @Bean
    public TaskConfigurer customTaskConfigurer(
            MongoOperations mongoOperations,
            TaskProperties taskProperties) {
        return new MongoTaskConfigurer(mongoOperations, taskProperties) {
            @Override
            public TaskRepository getTaskRepository() {
                // Custom repository logic
                return super.getTaskRepository();
            }
        };
    }
}
```

### 6.6 Programmatic Configuration

```java
@Configuration
public class TaskConfiguration {

    @Bean
    public MongoClient mongoClient() {
        return MongoClients.create("mongodb://localhost:27017");
    }

    @Bean
    public MongoTemplate mongoTemplate(MongoClient mongoClient) {
        return new MongoTemplate(mongoClient, "taskdb");
    }

    @Bean
    public TaskProperties taskProperties() {
        TaskProperties properties = new TaskProperties();
        properties.setTablePrefix("CUSTOM_");
        properties.setInitializeEnabled(true);
        return properties;
    }
}
```

---

## 7. JDBC vs MongoDB Comparison

| Aspect | JDBC | MongoDB |
|--------|------|---------|
| **Schema** | SQL Tables | Document Collections |
| **Initialization** | Execute SQL scripts | Create collections/indexes |
| **ID Generation** | DB Sequence | Atomic findAndModify |
| **Transactions** | JDBC Transaction | MongoDB Transaction (4.0+) |
| **Pagination** | LIMIT/OFFSET | skip()/limit() |
| **Indexes** | CREATE INDEX | createIndex() |
| **Locking** | SELECT FOR UPDATE | Unique Index + TTL |
| **Pros** | - Mature ecosystem<br>- Strong transactions<br>- SQL standardization | - Schema flexibility<br>- Horizontal scaling<br>- Document structure<br>- Native JSON support |
| **Cons** | - Vertical scaling limits<br>- Schema changes difficult<br>- Complex joins | - Limited transactions<br>- JOIN performance<br>- No foreign keys |
| **Best For** | - Strong consistency needs<br>- Complex relationships<br>- ACID requirements | - Flexible schema<br>- High write throughput<br>- Document-based data<br>- Horizontal scaling |

### Performance Considerations

| Operation | JDBC | MongoDB |
|-----------|------|---------|
| **Simple Insert** | Fast | Very Fast |
| **Batch Insert** | Very Fast (batch) | Fast (no native batch) |
| **Simple Select** | Fast | Fast |
| **Complex Join** | Fast | Slow (multiple queries) |
| **Pagination** | Fast (with indexes) | Fast (with indexes) |
| **Aggregation** | Fast | Very Fast (aggregation pipeline) |
| **Full Text Search** | Limited | Native support |

---

## 8. Migration Guide

### 8.1 From JDBC to MongoDB

#### Step 1: Update Dependencies

**Remove**:
```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
</dependency>
<!-- Or other JDBC drivers -->
```

**Add**:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

#### Step 2: Update Configuration

**Before (JDBC)**:
```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
  cloud:
    task:
      initialize-enabled: true
```

**After (MongoDB)**:
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/taskdb
  cloud:
    task:
      repository-type: mongodb
      initialize-enabled: true
```

#### Step 3: Data Migration (if needed)

**Export from JDBC**:
```sql
-- Export task executions
SELECT * FROM TASK_EXECUTION
INTO OUTFILE '/tmp/task_executions.csv';
```

**Import to MongoDB**:
```javascript
// Import script
db.task_executions.insertMany([
  {
    taskExecutionId: 1,
    taskName: "myTask",
    startTime: new Date("2025-01-01T10:00:00Z"),
    // ... other fields
  }
]);
```

**Or use MongoTaskMigration** (TODO: needs implementation):
```java
MongoTaskMigration migration = new MongoTaskMigration(mongoOperations, taskProperties);
migration.importFromJdbc(dataSource);
```

### 8.2 Compatibility Checklist

- [ ] Update Maven/Gradle dependencies
- [ ] Update application.yml configuration
- [ ] Remove JDBC-specific beans
- [ ] Test with embedded MongoDB (Testcontainers)
- [ ] Migrate existing data (if needed)
- [ ] Update CI/CD pipelines
- [ ] Update monitoring/alerting
- [ ] Document new connection strings

### 8.3 Rollback Plan

Keep JDBC dependencies for transition period:

```yaml
spring:
  profiles:
    active: ${REPOSITORY_TYPE:jdbc}  # Default to JDBC

---
spring:
  config:
    activate:
      on-profile: jdbc
  cloud:
    task:
      repository-type: jdbc

---
spring:
  config:
    activate:
      on-profile: mongodb
  cloud:
    task:
      repository-type: mongodb
```

---

## 9. Improvements Made

### 9.1 Fixed Bean Conflict Issue

**Problem**: Two `TaskConfigurer` beans could be created when `PlatformTransactionManager` exists.

**Solution**:
```java
// Higher priority - with TransactionManager
@Bean
@ConditionalOnMissingBean
@ConditionalOnBean(PlatformTransactionManager.class)
public TaskConfigurer taskConfigurer(MongoOperations mongoOperations,
        TaskProperties taskProperties,
        PlatformTransactionManager transactionManager) {
    return new MongoTaskConfigurer(mongoOperations, taskProperties, transactionManager);
}

// Fallback - without TransactionManager
@Bean
@ConditionalOnMissingBean
public TaskConfigurer taskConfigurerWithoutTransactionManager(
        MongoOperations mongoOperations,
        TaskProperties taskProperties) {
    return new MongoTaskConfigurer(mongoOperations, taskProperties);
}
```

**Benefits**:
- ✅ Clear priority order
- ✅ Different bean names prevent conflicts
- ✅ TransactionManager always used when available

### 9.2 Implemented tryLock Timeout

**Problem**: `tryLock(long time, TimeUnit unit)` ignored parameters, violated Lock interface contract.

**Solution**:
```java
@Override
public boolean tryLock(long time, TimeUnit unit) {
    long timeoutMillis = unit.toMillis(time);
    long startTime = System.currentTimeMillis();
    long retryInterval = 100;

    while (true) {
        if (tryLockOnce()) {
            return true;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed >= timeoutMillis) {
            return false;
        }

        long remainingTime = timeoutMillis - elapsed;
        long sleepTime = Math.min(retryInterval, remainingTime);

        if (sleepTime > 0) {
            Thread.sleep(sleepTime);
        }
    }
}
```

**Benefits**:
- ✅ Complies with Lock interface contract
- ✅ Retries until timeout
- ✅ Proper InterruptedException handling

### 9.3 Improved Initialization Logic

**Problem**: Lock initialization duplicated with `MongoTaskRepositoryInitializer`.

**Solution**:
```java
private void initializeCollection() {
    String collectionName = getCollectionName(TASK_LOCK_COLLECTION);
    if (!mongoOperations.collectionExists(collectionName)) {
        mongoOperations.createCollection(collectionName);
    }

    // Explicit index name for idempotency
    Index compoundIndex = new Index()
        .on("lockKey", Sort.Direction.ASC)
        .on("region", Sort.Direction.ASC)
        .unique()
        .named("idx_lock_key_region");  // Named index

    var indexOps = mongoOperations.indexOps(collectionName);
    try {
        indexOps.createIndex(compoundIndex);
    }
    catch (Exception e) {
        // Index may already exist - acceptable
    }
}
```

**Benefits**:
- ✅ Idempotent index creation
- ✅ Clear comment explaining duplication
- ✅ Works even if initializer disabled

### 9.4 Enhanced Documentation

**Improvements**:
- ✅ Added JavaDoc for all public methods
- ✅ Explained Object-type constructor purpose
- ✅ Clarified sequence initialization behavior
- ✅ Documented why Document classes lack annotations

---

## 10. Remaining TODOs

### MongoTaskMigration

**Location**: `MongoTaskMigration.java`

**Issue**: Migration class exists but not integrated into auto-configuration.

**Questions to address**:
1. Should migrations run automatically on startup?
2. Should there be a property to enable/disable migrations?
3. How does this compare to JDBC's Flyway/Liquibase approach?
4. Need documentation on when and how to use migration methods

**Suggested Implementation**:
```yaml
spring:
  cloud:
    task:
      migration:
        enabled: false              # Disabled by default
        from-version: "3.x"        # Source version
        to-version: "4.x"          # Target version
        auto-run: false            # Manual trigger recommended
```

---

## 11. Troubleshooting

### Common Issues

#### Issue 1: Auto-configuration not activating

**Symptoms**: TaskConfigurer bean not created

**Solutions**:
```yaml
# Ensure property is set
spring:
  cloud:
    task:
      repository-type: mongodb  # Must be explicit

# Check MongoDB connection
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/taskdb
```

**Debug**:
```bash
# Enable debug logging
logging:
  level:
    org.springframework.boot.autoconfigure: DEBUG
    org.springframework.cloud.task: DEBUG
```

#### Issue 2: Lock not releasing

**Symptoms**: Tasks hang, locks persist

**Solutions**:
1. Check TTL configuration:
```yaml
spring:
  cloud:
    task:
      single-instance-lock-ttl: 30000  # Increase if needed
```

2. Manual cleanup:
```javascript
// MongoDB shell
db.TASK_task_locks.deleteMany({
  expirationDate: { $lt: new Date() }
});
```

#### Issue 3: Sequence not incrementing

**Symptoms**: ID collision errors

**Solutions**:
```javascript
// Check sequence document
db.TASK_task_sequence.find({ _id: "task_seq" });

// Reset if needed
db.TASK_task_sequence.updateOne(
  { _id: "task_seq" },
  { $set: { sequence: 1000 } },
  { upsert: true }
);
```

#### Issue 4: Index creation failures

**Symptoms**: Startup errors about indexes

**Solutions**:
1. Drop existing indexes:
```javascript
db.TASK_task_executions.dropIndexes();
```

2. Disable auto-initialization:
```yaml
spring:
  cloud:
    task:
      initialize-enabled: false
```

---

## 12. Best Practices

### 12.1 Production Deployment

1. **Use Replica Sets**:
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://node1:27017,node2:27017,node3:27017/taskdb?replicaSet=rs0
```

2. **Enable Authentication**:
```yaml
spring:
  data:
    mongodb:
      username: taskuser
      password: ${MONGO_PASSWORD}
      authentication-database: admin
```

3. **Configure Connection Pool**:
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/taskdb?maxPoolSize=100&minPoolSize=10
```

### 12.2 Monitoring

**Metrics to track**:
- Task execution count
- Average execution time
- Failed task count
- Lock acquisition time
- Lock wait time

**Example with Micrometer**:
```java
@Component
public class TaskMetrics {
    private final MeterRegistry registry;

    public TaskMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordTaskExecution(String taskName, long duration) {
        Timer.builder("task.execution")
            .tag("name", taskName)
            .register(registry)
            .record(duration, TimeUnit.MILLISECONDS);
    }
}
```

### 12.3 Performance Tuning

1. **Index Optimization**:
   - Review query patterns
   - Add compound indexes for frequent queries
   - Monitor index usage with `explain()`

2. **Write Concern**:
```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/taskdb?w=majority&journal=true
```

3. **Read Preference**:
```java
@Bean
public MongoTemplate mongoTemplate(MongoClient mongoClient) {
    MongoTemplate template = new MongoTemplate(mongoClient, "taskdb");
    template.setReadPreference(ReadPreference.secondaryPreferred());
    return template;
}
```

---

## 13. References

### Official Documentation
- [Spring Cloud Task Reference](https://spring.io/projects/spring-cloud-task)
- [Spring Data MongoDB](https://spring.io/projects/spring-data-mongodb)
- [MongoDB Manual](https://docs.mongodb.com/manual/)

### Related Issues
- [GitHub Issue #953](https://github.com/spring-cloud/spring-cloud-task/issues/953) - MongoDB Support Request

### Code Locations
- **Auto-configuration**: `org.springframework.cloud.task.configuration.MongoTaskAutoConfiguration`
- **DAO**: `org.springframework.cloud.task.repository.dao.MongoTaskExecutionDao`
- **Lock**: `org.springframework.cloud.task.repository.dao.MongoLockRepository`
- **Initializer**: `org.springframework.cloud.task.repository.support.MongoTaskRepositoryInitializer`
- **Tests**: `org.springframework.cloud.task.repository.dao.MongoTaskExecutionDaoTests`

---

## Appendix A: Collection Schema Reference

### task_executions
| Field | Type | Description | Index |
|-------|------|-------------|-------|
| taskExecutionId | Long | Primary key | - |
| taskName | String | Task name | ✅ |
| startTime | Date | Start timestamp | ✅ (DESC) |
| endTime | Date | End timestamp | ✅ |
| exitCode | Integer | Exit code | - |
| exitMessage | String | Exit message | - |
| errorMessage | String | Error message | - |
| lastUpdated | Date | Last update | - |
| externalExecutionId | String | External ID | ✅ |
| parentExecutionId | Long | Parent task ID | ✅ |

### task_locks
| Field | Type | Description | Index |
|-------|------|-------------|-------|
| _id | String | Composite key (lockKey:region) | Primary |
| lockKey | String | Lock identifier | ✅ (compound) |
| region | String | Lock region | ✅ (compound) |
| clientId | String | Owner client ID | ✅ |
| createdDate | Date | Creation timestamp | - |
| expirationDate | Date | Expiration timestamp | ✅ |

---

**Document Version**: 1.0
**Last Updated**: 2025-09-30
**Author**: Spring Cloud Task Development Team