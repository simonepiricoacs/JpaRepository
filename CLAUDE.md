# JpaRepository Module — JPA Persistence Implementation

## Purpose
Provides the concrete JPA/Hibernate implementation of the `BaseRepository` abstraction defined in `Core-api`. Handles entity persistence, JPQL query generation, transaction management, and dynamic repository instantiation. Supports both OSGi and Spring runtimes with separate sub-modules.

## Sub-modules

| Sub-module | Runtime | Key Classes |
|---|---|---|
| `JpaRepository-api` | All | `JpaRepository<T>`, `WaterJpaRepository<T>`, `JpaRepositoryManager`, `AbstractJpaEntity`, `AbstractJpaExpandableEntity` |
| `JpaRepository-osgi` | OSGi | `OsgiJpaRepositoryManager`, `OsgiBaseJpaRepository` |
| `JpaRepository-spring` | Spring | `SpringJpaRepositoryManager`, `RepositoryFactory`, `JpaRepositoryImpl` |
| `JpaRepository-test-utils` | Test | Test repositories, in-memory H2 setup, transaction test helpers |

## Entity Base Classes (JpaRepository-api)

### AbstractJpaEntity
```java
@MappedSuperclass
public abstract class AbstractJpaEntity extends AbstractEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private long id;

    @Version
    private int entityVersion;                  // optimistic locking

    @Column(updatable = false)
    private Date entityCreateDate;

    private Date entityModifyDate;
}
```

### AbstractJpaExpandableEntity
Extends `AbstractJpaEntity` with a `@Lob` JSON column (`extraFields`) for storing dynamic attributes without schema migrations.

```java
public abstract class AbstractJpaExpandableEntity extends AbstractJpaEntity {
    @Lob
    @Column(name = "extra_fields")
    private String extraFields;                  // JSON blob

    public <T> T getExtraField(String key, Class<T> type) { ... }
    public void setExtraField(String key, Object value) { ... }
}
```

## Repository Interface Pattern

```java
// In your module's -api sub-module
public interface MyEntityRepository extends BaseRepository<MyEntity> {
    // Custom query methods go here
    MyEntity findByName(String name);
    List<MyEntity> findByStatus(String status);
}

// The JpaRepositoryManager instantiates the implementation at runtime
// No manual implementation class needed for standard CRUD
```

## WaterJpaRepository<T>

Base JPA repository providing:
- `save(T entity)` — persist or merge
- `update(T entity)` — merge with optimistic lock check
- `find(long id)` — `EntityManager.find()`
- `findAll(int delta, int page, Query filter)` — JPQL with WHERE/ORDER BY
- `remove(long id)` — delete by ID
- `countAll(Query filter)` — COUNT query
- Direct `EntityManager` access for custom JPQL

## JpaRepositoryManager

Factory for creating and registering repository instances:

```java
// OSGi: uses OSGi ServiceRegistry
OsgiJpaRepositoryManager manager = ...;
manager.createRepository(MyEntity.class, MyEntityRepository.class);

// Spring: uses Spring ApplicationContext + @Bean registration
SpringJpaRepositoryManager manager = ...;
manager.createRepository(MyEntity.class, MyEntityRepository.class);
```

## Transaction Management

JPA transactions are managed by the runtime:
- **OSGi:** JTA transactions via Aries Transaction Manager
- **Spring:** `@Transactional` propagation managed by Spring TX Manager

Service methods in `BaseEntityServiceImpl` are wrapped in transactions automatically via the interceptor chain.

## Persistence Unit Configuration

### Spring (application.properties)
```properties
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect
```

### OSGi (persistence.xml)
```xml
<persistence-unit name="water-pu" transaction-type="JTA">
  <provider>org.hibernate.jpa.HibernatePersistenceProvider</provider>
  <!-- entities registered via JpaRepositoryManager -->
</persistence-unit>
```

## JpaRepository-test-utils

Provides:
- Pre-configured H2 in-memory `DataSource`
- `TestRepositoryFactory` for test-time repository wiring
- Transaction helpers for test isolation

Usage in tests:
```java
@ExtendWith(WaterTestExtension.class)
class MyRepoTest implements Service {
    @Inject @Setter private MyEntityRepository repository;
    // H2 auto-configured via JpaRepository-test-utils
}
```

## Dependencies
- `it.water.repository:Repository-entity` — `AbstractEntity`, exceptions
- `jakarta.persistence:jakarta.persistence-api` — JPA annotations
- `org.hibernate:hibernate-core` — JPA provider
- `it.water.core:Core-api` — `BaseRepository`, `ComponentRegistry`
- **OSGi only:** `org.apache.aries.jpa`, `osgi.jpa`
- **Spring only:** `org.springframework.data:spring-data-jpa`, `org.springframework:spring-orm`

## Code Generation Rules
- Entity JPA annotations (`@Entity`, `@Table`, `@Column`) belong in the **model** sub-module, on classes extending `AbstractJpaEntity`
- Repository interfaces extend `BaseRepository<T>` — placed in the **api** sub-module
- Never add `EntityManager` calls directly in service classes — use repository methods
- Custom JPQL queries go in the repository implementation, annotated with `@Query` (Spring) or named query (OSGi)
- Use `AbstractJpaExpandableEntity` when entities need schema-free extensibility (e.g., User, Company)
