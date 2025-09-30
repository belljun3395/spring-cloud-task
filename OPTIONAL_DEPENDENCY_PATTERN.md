# Optional Dependency Pattern in Spring Cloud Task

## Overview

This document explains why `MongoTaskExecutionDao` has a constructor that accepts `Object` type instead of `MongoOperations`, and why this is necessary for Spring's optional dependency pattern.

---

## The Question

**Why does `MongoTaskExecutionDao` need this constructor?**

```java
public MongoTaskExecutionDao(Object mongoOperations, TaskProperties taskProperties) {
    if (!(mongoOperations instanceof MongoOperations)) {
        throw new IllegalArgumentException("Provided object is not a MongoOperations instance");
    }
    this.mongoOperations = (MongoOperations) mongoOperations;
    // ...
}
```

**Why not use the type-safe version directly?**

```java
public MongoTaskExecutionDao(MongoOperations mongoOperations, TaskProperties taskProperties) {
    this.mongoOperations = mongoOperations;
    // ...
}
```

---

## The Answer: Optional Dependencies

### Spring Cloud Task's Perspective (Library Provider)

Spring Cloud Task declares MongoDB as an **optional dependency**:

```xml
<!-- spring-cloud-task-core/pom.xml -->
<dependencies>
    <dependency>
        <groupId>org.springframework.data</groupId>
        <artifactId>spring-data-mongodb</artifactId>
        <optional>true</optional>  <!-- 👈 Key point! -->
    </dependency>
</dependencies>
```

**What `<optional>true</optional>` means:**
- The Spring Cloud Task library itself does not depend on MongoDB
- MongoDB support is **available but not required**
- **Users can choose** whether to use MongoDB or not

---

## User Scenarios

### Scenario 1: User wants JDBC (without MongoDB)

```xml
<!-- User's pom.xml -->
<dependencies>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-task</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
    </dependency>
</dependencies>
```

**Result:**
- ✅ No MongoDB dependency
- ✅ MongoDB classes not available at compile time
- ✅ Application works fine with JDBC
- ✅ No compilation errors

### Scenario 2: User wants MongoDB

```xml
<!-- User's pom.xml -->
<dependencies>
    <dependency>
        <groupId>org.springframework.cloud</groupId>
        <artifactId>spring-cloud-starter-task</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-mongodb</artifactId>
    </dependency>
</dependencies>
```

**Configuration:**
```yaml
spring:
  cloud:
    task:
      repository-type: mongodb
```

**Result:**
- ✅ MongoDB dependency added
- ✅ MongoDB classes available at runtime
- ✅ MongoDB auto-configuration activates
- ✅ MongoTaskExecutionDao created and used

---

## What Would Happen Without Object Type?

### If we used MongoOperations directly:

```java
// ❌ If TaskExecutionDaoFactoryBean did this:
import org.springframework.data.mongodb.core.MongoOperations;

public class TaskExecutionDaoFactoryBean {
    private MongoOperations mongoOperations;  // Direct type

    private void buildMongoTaskExecutionDao() {
        this.dao = new MongoTaskExecutionDao(mongoOperations, taskProperties);
    }
}
```

### Problem for JDBC-only users:

```bash
$ mvn compile
[ERROR] Cannot find symbol: class MongoOperations
[ERROR] Cannot find symbol: class MongoTaskExecutionDao
[ERROR] BUILD FAILURE
```

❌ **Even users who don't want MongoDB would get compilation errors!**

This violates the optional dependency principle.

---

## How It Works: Runtime Resolution

### TaskExecutionDaoFactoryBean uses reflection:

```java
public class TaskExecutionDaoFactoryBean {

    private Object mongoOperations;  // ✅ No compile-time dependency

    private void buildMongoTaskExecutionDao() {
        if (DatabaseType.isMongoDB(this.mongoOperations)) {
            try {
                // Check if MongoDB classes are available at runtime
                Class.forName("org.springframework.data.mongodb.core.MongoOperations");

                // Load MongoTaskExecutionDao via reflection
                Class<?> daoClass = Class.forName(
                    "org.springframework.cloud.task.repository.dao.MongoTaskExecutionDao");

                // Instantiate using Object-type constructor
                this.dao = (TaskExecutionDao) daoClass
                    .getConstructor(Object.class, TaskProperties.class)
                    .newInstance(this.mongoOperations, this.taskProperties);
            }
            catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "MongoDB support requires spring-data-mongodb dependency", e);
            }
        }
    }
}
```

**Key points:**
1. **Compile time**: No MongoDB imports → Works without MongoDB
2. **Runtime**: Reflection checks if MongoDB is available
3. **If available**: Creates MongoDB DAO dynamically
4. **If not available**: Uses JDBC or Map-based DAO instead

---

## Comparison: JDBC vs MongoDB

### JdbcTaskExecutionDao (No Problem)

```java
public JdbcTaskExecutionDao(DataSource dataSource, String tablePrefix) {
    this.dataSource = dataSource;
    this.tablePrefix = tablePrefix;
}
```

**In TaskExecutionDaoFactoryBean:**
```java
private void buildTaskExecutionDao(DataSource dataSource) {
    // ✅ Direct instantiation - no reflection needed
    this.dao = new JdbcTaskExecutionDao(dataSource, this.tablePrefix);
}
```

**Why no problem?**
- `DataSource` is from `javax.sql` (Java standard library)
- **Always available** in core module
- Can be directly imported and used

### MongoTaskExecutionDao (Needs Object Type)

```java
public MongoTaskExecutionDao(Object mongoOperations, TaskProperties taskProperties) {
    // Type checked at runtime
    if (!(mongoOperations instanceof MongoOperations)) {
        throw new IllegalArgumentException(...);
    }
    this.mongoOperations = (MongoOperations) mongoOperations;
}
```

**In TaskExecutionDaoFactoryBean:**
```java
private void buildMongoTaskExecutionDao() {
    // ❌ Cannot import MongoOperations directly
    // ✅ Use reflection to load class at runtime
    Class<?> daoClass = Class.forName("...MongoTaskExecutionDao");
    this.dao = (TaskExecutionDao) daoClass
        .getConstructor(Object.class, TaskProperties.class)  // 👈 Object.class
        .newInstance(this.mongoOperations, this.taskProperties);
}
```

**Why Object type?**
- `MongoOperations` is from `spring-data-mongodb` (optional dependency)
- **Not always available** at compile time
- Must use reflection for dynamic loading

---

## Comparison Table

| Aspect | JdbcTaskExecutionDao | MongoTaskExecutionDao |
|--------|---------------------|---------------------|
| **Dependency Type** | javax.sql (standard) | spring-data-mongodb (optional) |
| **Compile Time** | Always available | Optional |
| **In FactoryBean** | Direct `new` creation | Reflection creation |
| **Constructor Type** | `DataSource` (concrete) | `Object` (generic) |
| **Import in FactoryBean** | ✅ Possible | ❌ Not possible |
| **Pattern** | Standard | Optional Dependency Pattern |

---

## Runtime Flow

```
User Application Starts
    ↓
Spring Boot Auto-configuration
    ↓
┌─────────────────────────────────┐
│ Is MongoDB dependency present?   │
└─────────┬───────────────────────┘
          │
    ┌─────┴─────┐
    │           │
  Yes          No
    │           │
    ↓           ↓
MongoTask    DefaultTask
AutoConfig   AutoConfig
    ↓           ↓
MongoDB      JDBC/Map
DAO          DAO
```

---

## Analogy: Plugin System

Think of Spring Cloud Task as a **plugin system**:

```
Spring Cloud Task (Core)
├── JDBC Plugin (Built-in, always available)
├── MongoDB Plugin (Optional, user adds if needed)
└── Redis Plugin (Could be added in future)
```

- **Core** knows about all plugins
- **Compile time**: No hard dependencies on optional plugins
- **Runtime**: Plugins activate dynamically if present

---

## Why This Pattern?

### 1. User Choice
Users can choose their storage backend without pulling unnecessary dependencies:

```bash
# JDBC user
dependencies: spring-cloud-task + jdbc

# MongoDB user
dependencies: spring-cloud-task + mongodb

# Both work without forcing the other dependency
```

### 2. Smaller Dependency Tree
Users who only need JDBC don't download MongoDB libraries:

```bash
# Without optional pattern
spring-cloud-task → spring-data-mongodb (forced)
                 → mongodb-driver (forced)
                 → ... (many transitive deps)

# With optional pattern
spring-cloud-task → (no MongoDB deps unless user adds them)
```

### 3. Library Evolution
New storage backends can be added without breaking existing users:

```java
// Future: Redis support
public class RedisTaskExecutionDao implements TaskExecutionDao {
    public RedisTaskExecutionDao(Object redisTemplate, TaskProperties props) {
        // Same optional dependency pattern
    }
}
```

---

## Common Questions

### Q1: Why not just use two separate modules?

**Answer:** Possible, but creates maintenance burden:
- `spring-cloud-task-jdbc` module
- `spring-cloud-task-mongodb` module

Current approach:
- ✅ Single core module
- ✅ Auto-detection of available backends
- ✅ Less maintenance
- ✅ Easier for users (one dependency)

### Q2: Is this a Spring-specific pattern?

**Answer:** No, it's a common Maven/Gradle pattern:
- Optional dependencies exist in many frameworks
- Examples: Hibernate with various drivers, Jackson with different data formats
- Spring just uses it extensively

### Q3: Can I safely remove the Object constructor?

**Answer:** No, it would be a **breaking change**:
- Existing code using `TaskExecutionDaoFactoryBean` would break
- MongoDB support would require compile-time dependency
- Goes against Spring's design principles

### Q4: Is there a better alternative?

**Answer:** Modern approaches could use:
- **Java 9+ Services**: ServiceLoader for plugin discovery
- **Conditional compilation**: But not supported in standard Java/Maven
- **Separate modules**: But loses convenience

The current approach is the **best balance** for Maven-based projects.

---

## Code Examples

### Example 1: Direct Usage (Type-safe)

If you're directly creating the DAO (e.g., in tests or custom configuration):

```java
@Configuration
public class CustomTaskConfiguration {

    @Bean
    public TaskExecutionDao taskExecutionDao(
            MongoOperations mongoOperations,
            TaskProperties taskProperties) {
        // ✅ Use type-safe constructor
        return new MongoTaskExecutionDao(mongoOperations, taskProperties);
    }
}
```

### Example 2: Factory Usage (Reflection)

When using through the factory (normal usage):

```java
// This is what TaskExecutionDaoFactoryBean does internally
TaskExecutionDaoFactoryBean factory =
    new TaskExecutionDaoFactoryBean(mongoOperations, taskProperties);

TaskExecutionDao dao = factory.getObject();
// Internally uses Object-type constructor via reflection
```

---

## Related Patterns in Spring

This optional dependency pattern is used throughout Spring:

### Spring Data JPA
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
    <optional>true</optional>
</dependency>
```

### Spring Security OAuth2
```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-oauth2-client</artifactId>
    <optional>true</optional>
</dependency>
```

### Spring WebFlux
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
    <optional>true</optional>
</dependency>
```

---

## Best Practices

### For Library Authors

1. ✅ **Use optional dependencies** for alternative implementations
2. ✅ **Use Object type** in factory classes for optional deps
3. ✅ **Provide auto-configuration** for each optional backend
4. ✅ **Document clearly** which dependencies enable which features
5. ✅ **Use `@ConditionalOnClass`** for auto-configuration

### For Library Users

1. ✅ **Add only needed dependencies** (don't add MongoDB if using JDBC)
2. ✅ **Use auto-configuration** (don't manually create DAOs)
3. ✅ **Configure via properties** (e.g., `spring.cloud.task.repository-type`)
4. ✅ **Test with only your chosen backend**

---

## Conclusion

The `Object` type constructor in `MongoTaskExecutionDao` is **not a design flaw** but a **necessary implementation** of Spring's optional dependency pattern.

### Key Takeaways:

1. **Purpose**: Allow users to choose storage backends without forcing dependencies
2. **Mechanism**: Reflection + Object type avoids compile-time dependencies
3. **Pattern**: Standard practice in Spring ecosystem
4. **Alternative**: Type-safe constructor exists for direct usage
5. **Cannot Remove**: Would break optional dependency principle

This design enables Spring Cloud Task to:
- ✅ Support multiple storage backends
- ✅ Keep dependency tree minimal
- ✅ Allow user choice
- ✅ Enable future extensibility
- ✅ Follow Spring best practices

---

## References

### Code Locations
- **Factory**: `org.springframework.cloud.task.repository.support.TaskExecutionDaoFactoryBean`
- **MongoDB DAO**: `org.springframework.cloud.task.repository.dao.MongoTaskExecutionDao`
- **JDBC DAO**: `org.springframework.cloud.task.repository.dao.JdbcTaskExecutionDao`
- **Database Type**: `org.springframework.cloud.task.repository.support.DatabaseType`

### Related Documentation
- [Maven Optional Dependencies](https://maven.apache.org/guides/introduction/introduction-to-optional-and-excludes-dependencies.html)
- [Spring Boot Conditional Annotations](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.developing-auto-configuration.condition-annotations)
- [Spring Cloud Task Reference](https://spring.io/projects/spring-cloud-task)

### Related Issues
- [GitHub Issue #953](https://github.com/spring-cloud/spring-cloud-task/issues/953) - MongoDB Support Implementation

---

**Document Version**: 1.0
**Last Updated**: 2025-09-30
**Author**: Spring Cloud Task Development Team