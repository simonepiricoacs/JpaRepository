
/*
 * Copyright 2024 Aristide Cittadino
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.water.repository.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.water.core.api.bundle.ApplicationProperties;
import it.water.core.api.entity.owned.OwnedResource;
import it.water.core.api.model.BaseEntity;
import it.water.core.api.model.EntityExtension;
import it.water.core.api.model.ExpandableEntity;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.repository.BaseRepository;
import it.water.core.api.repository.RepositoryConstraintValidator;
import it.water.core.api.repository.query.Query;
import it.water.core.api.repository.query.QueryBuilder;
import it.water.core.api.repository.query.QueryOrder;
import it.water.core.api.repository.query.QueryOrderParameter;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.model.exceptions.WaterRuntimeException;
import it.water.repository.entity.model.PaginatedResult;
import it.water.repository.entity.model.exceptions.EntityNotFound;
import it.water.repository.entity.model.exceptions.NoResultException;
import it.water.repository.jpa.api.JpaRepository;
import it.water.repository.jpa.constraints.DuplicateConstraintValidator;
import it.water.repository.jpa.constraints.RepositoryConstraintValidatorsManager;
import it.water.repository.jpa.query.PredicateBuilder;
import it.water.repository.query.DefaultQueryBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.spi.PersistenceProvider;
import jakarta.persistence.spi.PersistenceProviderResolver;
import jakarta.persistence.spi.PersistenceProviderResolverHolder;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import jakarta.transaction.Transactional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;


/**
 * @param <T> parameter that indicates a generic class which must extend Base Entity
 *            Model class for BaseRepositoryImpl.
 *            This class implements all methods for basic CRUD operations defined in
 *            BaseRepository interface. These methods are reusable by all
 *            entities that interact with the platform.
 * @Author Aristide Cittadino
 */
public abstract class BaseJpaRepositoryImpl<T extends BaseEntity> implements JpaRepository<T> {
    public static final String WATER_DEFAULT_PERSISTENCE_UNIT_NAME = "water-default-persistence-unit";

    /**
     * Property name to configure the maximum allowed page size (delta) for findAll queries.
     */
    public static final String MAX_PAGE_SIZE_PROPERTY = "water.rest.pagination.max.delta";

    /**
     * Default maximum page size applied when the property is absent or unparseable.
     */
    public static final int DEFAULT_MAX_PAGE_SIZE = 200;

    @Inject
    @Setter
    @Getter(AccessLevel.PROTECTED)
    private ComponentRegistry componentRegistry;

    @Inject
    @Setter
    private ApplicationProperties applicationProperties;

    @Getter(AccessLevel.PROTECTED)
    private Logger log = LoggerFactory.getLogger(BaseJpaRepositoryImpl.class.getName());

    /**
     * Generic class for Water platform
     */
    protected Class<T> type;

    /**
     * Local Entity Manager may be null if not passed in the constructor.
     * We leave the possibility to override this method which is used internally in this class
     * and let children classes to set how to retrieve it. For Example in OSGi contest it will be retrieved
     * in concrete repository classes using the @PersistenceUnit EntityManagerFactor.createEntityManager
     */
    @Getter(AccessLevel.PUBLIC)
    private EntityManager entityManager;

    /**
     * Global initialized entity managers if they are not provided from the constructor.
     * this map saves each entity manager based on the persistence unit name in order to not create more entity manager
     * than the actually defined persistence unit name
     */
    private static Map<String, EntityManager> globalEntityManagers = new HashMap<>();

    /**
     * Persistence Unit related to the entity manager that must be created for this repository.
     */
    private String persistenceUnitName;

    protected RepositoryConstraintValidatorsManager dbConstraintsValidatorManager;

    @Override
    public Class<T> getEntityType() {
        return type;
    }

    /**
     * Generic constructor it will try to load an entity manager finding persistence.xml inside project path using default persistence unit name
     *
     * @param type
     */
    protected BaseJpaRepositoryImpl(Class<T> type) {
        setupJpaRepository(WATER_DEFAULT_PERSISTENCE_UNIT_NAME, type);
        this.initJpaRepository(initDefaultEntityManager(), new DuplicateConstraintValidator());
    }

    /**
     * Generic constructor it will try to load an entity manager finding persistence.xml inside project path using the specified persistence unit name
     *
     * @param type
     */
    protected BaseJpaRepositoryImpl(Class<T> type, String persistenceUnitName) {
        setupJpaRepository(persistenceUnitName, type);
        this.initJpaRepository(initDefaultEntityManager(), new DuplicateConstraintValidator());
    }

    /**
     * Generic constructor to force all parameters
     *
     * @param type
     */
    protected BaseJpaRepositoryImpl(Class<T> type, String persistenceUnitName, EntityManager entityManager) {
        setupJpaRepository(persistenceUnitName, type);
        this.initJpaRepository(entityManager, new DuplicateConstraintValidator());
    }

    /**
     * Constructor for WaterBaseRepositoryImpl
     *
     * @param type parameter that indicates a generic entity
     */
    protected BaseJpaRepositoryImpl(Class<T> type, EntityManager entityManager) {
        setupJpaRepository(WATER_DEFAULT_PERSISTENCE_UNIT_NAME, type);
        this.initJpaRepository(entityManager, new DuplicateConstraintValidator());
    }

    protected BaseJpaRepositoryImpl(Class<T> type, EntityManager entityManager, RepositoryConstraintValidator... dbConstraintValidators) {
        setupJpaRepository(WATER_DEFAULT_PERSISTENCE_UNIT_NAME, type);
        this.initJpaRepository(entityManager, dbConstraintValidators);
    }

    protected void initJpaRepository(EntityManager entityManager, RepositoryConstraintValidator... dbConstraintValidators) {
        this.entityManager = entityManager;
        this.dbConstraintsValidatorManager = new RepositoryConstraintValidatorsManager(dbConstraintValidators);
    }

    private void setupJpaRepository(String persistenceUnitName, Class<T> type) {
        this.type = type;
        this.persistenceUnitName = persistenceUnitName;
    }

    private synchronized EntityManager initDefaultEntityManager() {
        if (!globalEntityManagers.containsKey(this.persistenceUnitName)) {
            try {
                EntityManagerFactory entityManagerFactory = createDefaultEntityManagerFactory();
                globalEntityManagers.put(this.persistenceUnitName, entityManagerFactory.createEntityManager());
            } catch (Exception e) {
                globalEntityManagers.remove(this.persistenceUnitName);
                getLog().warn(e.getMessage(), e);
                return null;
            }
        }
        return globalEntityManagers.get(this.persistenceUnitName);
    }

    protected EntityManagerFactory createDefaultEntityManagerFactory() {
        //default persistence unit info is focused on tests,so resource local and use hibernate, override this method to change the logic
        Properties jpaProperties = new Properties();
        jpaProperties.put("javax.persistence.jdbc.driver", "org.hsqldb.jdbcDriver");
        jpaProperties.put("javax.persistence.jdbc.url", "jdbc:hsqldb:mem:testdb");
        jpaProperties.put("javax.persistence.jdbc.user", "sa");
        jpaProperties.put("javax.persistence.jdbc.password", "");
        return setupEntityManagerFactory(persistenceUnitName, PersistenceUnitTransactionType.RESOURCE_LOCAL, null, null, jpaProperties);
    }

    protected EntityManagerFactory setupEntityManagerFactory(String persistenceUnitProviderClassName, PersistenceUnitTransactionType transactionType, DataSource jtaDs, DataSource noJtaDs, Properties properties) {
        EntityManagerFactory emf = null;
        PersistenceProviderResolver resolver = PersistenceProviderResolverHolder.getPersistenceProviderResolver();
        WaterPersistenceUnitInfo waterPersistenceUnitInfo = new WaterPersistenceUnitInfo(persistenceUnitName, type, persistenceUnitProviderClassName, transactionType, jtaDs, noJtaDs, properties);
        for (PersistenceProvider provider : resolver.getPersistenceProviders()) {
            emf = provider.createContainerEntityManagerFactory(waterPersistenceUnitInfo, properties);
            if (emf != null) {
                break;
            }
        }
        if (emf == null) {
            throw new PersistenceException("No Persistence provider for EntityManager named " + persistenceUnitName);
        }
        return emf;
    }

    /**
     * Identifies if the current context supports transaction or not.
     * For example in test environment where no application server is running transactional annotation won't work
     * Used on method with tx Type Required, this means that when we enter in this method there should be an active transaction
     *
     * @return
     */
    protected boolean isTransactionalSupported(EntityManager em) {
        //Every eventual exception we have accessing the transaction context means that transaction system is working
        try {
            return em != null && (em.isJoinedToTransaction() || (em.getTransaction() != null && em.getTransaction().isActive()));
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Identifies if the current context supports transaction or not.
     * For example in test environment where no application server is running transactional annotation won't work
     */
    private void startTransactionIfNeeded(EntityManager em) {
        if (!isTransactionalSupported(em))
            em.getTransaction().begin();
    }

    /**
     * Identifies if the current context supports transaction or not.
     * For example in test environment where no application server is running transactional annotation won't work
     */
    private void commitTransactionIfNeeded(EntityManager em) {
        if (!isTransactionalSupported(em))
            em.getTransaction().commit();
    }

    /**
     * Save an entity in database
     * Can be overridden in order to change the logic how to retrieve entity manager
     */
    @Override
    public T persist(T entity) {
        return this.persist(entity, null);
    }

    /**
     * Executes the runnable inside the persist transaction
     *
     * @param entity
     * @param runnable
     * @return
     */
    @Override
    public T persist(T entity, Runnable runnable) {
        return tx(Transactional.TxType.REQUIRED, em -> doPersist(entity, runnable, em));
    }

    /**
     * Persistence logic with a specific entity manager
     *
     * @param entity
     * @param em
     * @return
     */
    protected T doPersist(T entity, Runnable task, EntityManager em) {
        startTransactionIfNeeded(em);
        try {
            log.debug("Repository Saving entity {}: {}", this.type.getSimpleName(), entity);
            this.dbConstraintsValidatorManager.runCheck(entity, this.type, this);
            log.debug("Transaction found, invoke persist");
            //managing expandable entity in the same transaction
            em.persist(entity);
            doPersistOnExpandableEntity(entity);
            log.debug("Entity persisted: {}", entity);
            if (task != null)
                task.run();
            commitTransactionIfNeeded(em);
            return entity;
        } catch (RuntimeException e) {
            //only in context where @transactional is not supported
            if (!isTransactionalSupported(em)) {
                em.getTransaction().rollback();
            }
            throw e;
        }
    }

    /**
     * @param entity
     */
    private void doPersistOnExpandableEntity(T entity) {
        processExpandableEntity(entity, (entityExtension, extensionRepository) -> {
            //forcing extension to have same primary key as its master entity and primary key is createad automatically
            entityExtension.setupExtensionFields(0, entity);
            extensionRepository.persist(entityExtension);
        });
    }

    /**
     * Update an entity in database
     * Can be overridden in order to change the logic how to retrieve entity manager
     */
    @Override
    public T update(T entity) {
        return update(entity, null);
    }

    /**
     * Update and executes task inside the same transaction of the update
     *
     * @param entity
     * @param runnable
     * @return
     */
    @Override
    public T update(T entity, Runnable runnable) {
        return tx(Transactional.TxType.REQUIRED, em -> doUpdate(entity, runnable, em));
    }

    /**
     * Persistence logic with a specific entity manager
     *
     * @param entity
     * @param em
     * @return
     */
    protected T doUpdate(T entity, Runnable task, EntityManager em) {
        try {
            startTransactionIfNeeded(em);
            log.debug("Repository Update entity {}: {}", this.type.getSimpleName(), entity);
            this.dbConstraintsValidatorManager.runCheck(entity, this.type, this);
            //Enforcing the concept that the owner cannot be changed
            //TO DO: check if it is useful or not
            T entityFromDb = em.find(type, entity.getId());
            if (entityFromDb instanceof OwnedResource ownedFromDb) {
                Long oldOwnerId = ownedFromDb.getOwnerUserId();
                OwnedResource owned = (OwnedResource) entity;
                owned.setOwnerUserId(oldOwnerId);
            }
            if (entity.getId() > 0) {
                log.debug("Updating entity");
                boolean upgradeVersionManually = !em.contains(entity);
                T updateEntity = em.merge(entity);
                //forcing the extension
                if (upgradeVersionManually) {
                    //incresing manually version since entities can come basically from non managed contexts (like rest with jackson)
                    updateEntity.setEntityVersion(updateEntity.getEntityVersion().intValue() + 1);
                }
                log.debug("Entity merged: {}", entity);
                //managing expandable entity
                if (entity.isExpandableEntity()) {
                    ExpandableEntity expandableEntity = (ExpandableEntity) entity;
                    fillEntityWithExtension(updateEntity, expandableEntity.getExtension());
                    doUpdateOnExpandableEntity(updateEntity);
                }
                if (task != null)
                    task.run();
                commitTransactionIfNeeded(em);
                return updateEntity;
            }
        } catch (RuntimeException e) {
            //only in context where @transactional is not supported
            if (!isTransactionalSupported(em)) {
                em.getTransaction().rollback();
            }
            throw e;
        }
        throw new EntityNotFound();
    }

    /**
     * @param entity
     */
    private void doUpdateOnExpandableEntity(T entity) {
        processExpandableEntity(entity, (entityExtension, extensionRepository) -> {
            boolean alreadyExists = true;
            long extensionId = 0;
            try {
                Query q = findByRelatedEntityId(extensionRepository, entity);
                BaseEntity extensionOnDb = extensionRepository.find(q);
                extensionId = extensionOnDb.getId();
            } catch (NoResultException e) {
                alreadyExists = false;
            }
            //always force to have same entity id as its master entity
            entityExtension.setupExtensionFields(extensionId, entity);
            //entity is new lets persist it for the first time
            if (!alreadyExists) {
                extensionRepository.persist(entityExtension);
            } else {
                entityExtension = (EntityExtension) extensionRepository.update(entityExtension);
            }
            fillEntityWithExtension(entity, entityExtension);
        });
    }

    /**
     * Remove an entity by id
     * Can be overridden in order to change the logic how to retrieve entity manager
     */
    @Override
    public void remove(long id) {
        remove(id, null);
    }

    /**
     * Remove and entity by id and executes runnables inseide the same transaction
     *
     * @param id
     * @param runnable
     */
    @Override
    public void remove(long id, Runnable runnable) {
        txExpr(Transactional.TxType.REQUIRED, em -> doRemove(id, runnable, em));
    }

    protected void doRemove(long id, Runnable task, EntityManager em) {
        try {
            startTransactionIfNeeded(em);
            log.debug("Repository Remove entity {} with id: {}", this.type.getSimpleName(), id);
            T entity = em.find(type, id);
            doRemove(entity, em);
            if (task != null)
                task.run();
            commitTransactionIfNeeded(em);
        } catch (RuntimeException e) {
            //only in context where @transactional is not supported
            if (!isTransactionalSupported(em)) {
                em.getTransaction().rollback();
            }
            throw e;
        }
    }

    /**
     * @param entity
     */
    private void doRemoveOnExpandableEntity(T entity) {
        processExpandableEntity(entity, (entityExtension, extensionRepository) -> {
            //extension entity is filled in the remove method
            ExpandableEntity expandableEntity = (ExpandableEntity) entity;
            BaseEntity extensionEntity = expandableEntity.getExtension();
            extensionRepository.remove(extensionEntity.getId());
        });
    }

    protected void doRemove(T entity, EntityManager em) {
        try {
            startTransactionIfNeeded(em);
            log.debug("Repository Remove entity {} with id: {}", this.type.getSimpleName(), entity.getId());
            entity = em.merge(entity);
            fillEntityWithExtension(entity);
            em.remove(entity);
            //process expandable entity
            doRemoveOnExpandableEntity(entity);
            log.debug("Entity {}  with id: {}  removed", this.type.getSimpleName(), entity.getId());
            commitTransactionIfNeeded(em);
        } catch (RuntimeException e) {
            //only in context where @transactional is not supported
            if (!isTransactionalSupported(em)) {
                em.getTransaction().rollback();
            }
            throw e;
        }
    }

    /**
     * Can be overridden in order to change the logic how to retrieve entity manager
     *
     * @param entity
     */
    @Override
    public void remove(T entity) {
        log.debug("Repository Remove all entities {}: {}", this.type.getSimpleName(), entity);
        //post actions are preserved
        txExpr(Transactional.TxType.REQUIRED, em -> doRemove(entity, em));
    }

    @Override
    public void removeAllByIds(Iterable<Long> ids) {
        log.debug("Repository Remove all entities {} by ids {}", this.type.getSimpleName(), ids);
        //post actions are preserved
        ids.forEach(this::remove);
    }

    @Override
    public void removeAll(Iterable<T> entities) {
        log.debug("Repository Remove all entities {}: {}", this.type.getSimpleName(), entities);
        //post actions are preserved
        entities.forEach(entity -> this.remove(entity.getId()));
    }

    @Override
    public void removeAll() {
        log.debug("Repository Remove all entities {}", this.type.getSimpleName());
        Collection<T> entites = this.findAll(-1, -1, null, null).getResults();
        //post actions are preserved
        entites.forEach(entity -> remove(entity.getId()));
    }

    /**
     * @param id parameter that indicates a entity id
     * @return
     */
    @Override
    public T find(long id) {
        log.debug("Repository Find entity {} with id: {}", this.type.getSimpleName(), id);
        Query filter = this.getQueryBuilderInstance().field("id").equalTo(id);
        if (filter != null)
            return this.find(filter);
        throw new NoResultException();
    }


    /**
     * @param filterStr filter
     * @return
     */
    @Override
    public T find(String filterStr) {
        Query filter = getQueryBuilderInstance().createQueryFilter(filterStr);
        return find(filter);
    }

    /**
     * Can be overridden in order to change the logic how to retrieve entity manager
     *
     * @param filter filter
     * @return
     */
    @Override
    public T find(Query filter) {
        T entity = tx(Transactional.TxType.SUPPORTS, em -> doFind(filter, em));
        if (entity == null)
            throw new NoResultException();
        return entity;
    }

    @SuppressWarnings("unchecked")
    protected T doFind(Query filter, EntityManager em) {
        log.debug("Repository Find entity {} with filter: {}", this.type.getSimpleName(), filter);
        log.debug("Transaction found, invoke find");
        CriteriaBuilder criteriaBuilder = em.getCriteriaBuilder();
        CriteriaQuery<T> query = criteriaBuilder.createQuery(this.type);
        Root<T> entityDef = query.from(this.type);
        Predicate condition = (filter != null) ? toPredicate(filter, entityDef, query, criteriaBuilder) : null;
        CriteriaQuery<T> criteriaQuery = (condition != null) ? query.select(entityDef).where(condition) : query.select(entityDef);
        jakarta.persistence.Query q = em.createQuery(criteriaQuery);
        try {
            T entity = (T) q.getSingleResult();
            log.debug("Found entity: {}", entity);
            //Detaching entity in order to prevent unwanted logic
            em.detach(entity);
            //Managing extension
            fillEntityWithExtension(entity);
            return entity;
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        } catch (Exception e) {
            throw new WaterRuntimeException("Generic error, while executing find: " + e.getMessage());
        }
    }

    /**
     * Find all entity
     * Can be overridden in order to change the logic how to retrieve entity manager
     */
    @Override
    public PaginatedResult<T> findAll(int delta, int page, Query filter, QueryOrder queryOrder) {
        return tx(Transactional.TxType.SUPPORTS, em -> doFindAll(delta, page, filter, queryOrder, em));
    }

    @SuppressWarnings("unchecked")
    protected PaginatedResult<T> doFindAll(int delta, int page, Query filter, QueryOrder queryOrder, EntityManager em) {
        log.debug("Repository Find All entities {}", this.type.getSimpleName());
        jakarta.persistence.Query q = createQuery(filter, queryOrder, em);
        int lastPageNumber = 1;
        int nextPage = 1;
        //Resolving the configured maximum page size cap (defense against unbounded result sets / DoS)
        int maxPageSize = resolveMaxPageSize();

        if (delta > 0 && page > 0) {
            //clamping requested delta to the configured maximum
            delta = Math.min(delta, maxPageSize);
            Long countResults = countAll(filter);
            lastPageNumber = (int) (Math.ceil(countResults / (double) delta));
            nextPage = (page <= lastPageNumber - 1) ? page + 1 : 1;
            //Executing paginated query
            int firstResult = (page - 1) * delta;
            q.setFirstResult(firstResult);
            q.setMaxResults(delta);
        } else {
            //even when pagination is not requested (null/<=0 delta or page) a bounded page size must be enforced
            q.setMaxResults(maxPageSize);
        }

        Collection<T> results = q.getResultList();
        PaginatedResult<T> paginatedResult = new PaginatedResult<>(lastPageNumber, page, nextPage, delta, results);
        //NOTE: we do not fill all entities with extension because it may lead to performance problem
        //if the user needs the details in the find all, he can retrieve this data using the specific service
        //and can do api or result composition in the client
        log.debug("Query results: {}", results);
        return paginatedResult;
    }

    /**
     * Resolves the maximum allowed page size from the configured property
     * {@link #MAX_PAGE_SIZE_PROPERTY}, falling back to {@link #DEFAULT_MAX_PAGE_SIZE}
     * when the property is absent, unparseable, non-positive, or when no
     * ApplicationProperties component is available.
     *
     * @return the effective maximum page size (always positive)
     */
    protected int resolveMaxPageSize() {
        if (applicationProperties == null)
            return DEFAULT_MAX_PAGE_SIZE;
        Object raw = applicationProperties.getProperty(MAX_PAGE_SIZE_PROPERTY);
        if (raw == null)
            return DEFAULT_MAX_PAGE_SIZE;
        try {
            int configured = Integer.parseInt(raw.toString().trim());
            return configured > 0 ? configured : DEFAULT_MAX_PAGE_SIZE;
        } catch (NumberFormatException e) {
            log.warn("Invalid value for {}: '{}', falling back to default {}", MAX_PAGE_SIZE_PROPERTY, raw, DEFAULT_MAX_PAGE_SIZE);
            return DEFAULT_MAX_PAGE_SIZE;
        }
    }


    /**
     * Can be overridden in order to change the logic how to retrieve entity manager
     *
     * @param filter filter query filter, can be null
     * @return entity count based on a specified filter
     */
    @Override
    public long countAll(Query filter) {
        return tx(Transactional.TxType.SUPPORTS, em -> doCountAll(filter, em));
    }

    protected long doCountAll(Query filter, EntityManager em) {
        log.debug("Repository countAll entities {}", this.type.getSimpleName());
        CriteriaBuilder criteriaBuilder = em.getCriteriaBuilder();
        //constructing query and count query
        CriteriaQuery<Long> countQuery = criteriaBuilder.createQuery(Long.class);
        Root<T> entityDefCount = countQuery.from(this.type);
        Predicate conditionCount = (filter != null) ? toPredicate(filter, entityDefCount, countQuery, criteriaBuilder) : null;
        countQuery = (conditionCount != null) ? countQuery.select(criteriaBuilder.count(entityDefCount)).where(conditionCount) : countQuery.select(criteriaBuilder.count(entityDefCount));
        //Executing count query
        jakarta.persistence.Query countQueryFinal = em.createQuery(countQuery);
        return (Long) countQueryFinal.getSingleResult();
    }


    private List<Order> getOrders(CriteriaBuilder criteriaBuilder, Root<T> entityDef, QueryOrder queryOrder) {
        List<Order> criteriaOrderClause = new ArrayList<>();
        List<QueryOrderParameter> parameterList = queryOrder.getParametersList();
        if (parameterList != null && !parameterList.isEmpty()) {
            for (QueryOrderParameter orderParameter : parameterList) {
                criteriaOrderClause.add((orderParameter.isAsc()) ? criteriaBuilder.asc(entityDef.get(orderParameter.getName())) : criteriaBuilder.desc(entityDef.get(orderParameter.getName())));
            }
        }
        return criteriaOrderClause;
    }

    private jakarta.persistence.Query createQuery(Query filter, QueryOrder queryOrder, EntityManager em) {
        CriteriaBuilder criteriaBuilder = em.getCriteriaBuilder();
        CriteriaQuery<T> criteriaQuery = criteriaBuilder.createQuery(this.type);
        Root<T> entityDef = criteriaQuery.from(this.type);
        Predicate condition = (filter != null) ? toPredicate(filter, entityDef, criteriaQuery, criteriaBuilder) : null;
        criteriaQuery = (condition != null) ? criteriaQuery.select(entityDef).where(condition) : criteriaQuery.select(entityDef);
        //adding order if necessary
        criteriaQuery = (queryOrder != null && queryOrder.getParametersList() != null && !queryOrder.getParametersList().isEmpty()) ? criteriaQuery.orderBy(getOrders(criteriaBuilder, entityDef, queryOrder)) : criteriaQuery;
        return em.createQuery(criteriaQuery);
    }

    private Predicate toPredicate(Query filter, Root<T> entityDef, CriteriaQuery<?> criteriaQuery, CriteriaBuilder criteriaBuilder) {
        PredicateBuilder<T> predicateBuilder = new PredicateBuilder<>(entityDef, criteriaQuery, criteriaBuilder);
        return predicateBuilder.buildPredicate(filter);
    }

    @Override
    public QueryBuilder getQueryBuilderInstance() {
        return new DefaultQueryBuilder();
    }

    /**
     * Define the default persistence unit name
     *
     * @return
     */
    protected String getPersistenceUnitName() {
        return persistenceUnitName;
    }

    /**
     * Allow to process a task if an extension is found.
     *
     * @param entity
     * @param task
     */
    private void processExpandableEntity(T entity, BiConsumer<EntityExtension, BaseRepository<BaseEntity>> task) {
        EntityExtension extension = entity.isExpandableEntity()?((ExpandableEntity)entity).getExtension():null;
        if (extension != null) {
            log.debug("Entity {} is expandable search for an extension...", this.type.getName());
            @SuppressWarnings("unchecked")
            BaseRepository<BaseEntity> extensionRepository = (BaseRepository<BaseEntity>) this.componentRegistry.findEntityExtensionRepository(this.type);
            if (extensionRepository != null) {
                log.debug("Expansion found {} for entity {}, completing task", extensionRepository.getEntityType(), type.getName());
                task.accept(extension, extensionRepository);
            }
        }
    }


    /**
     * Fills the entity, eventually, with the extensioj if found
     *
     * @param entity
     */
    private void fillEntityWithExtension(T entity) {
        if (entity.isExpandableEntity()) {
            BaseRepository<?> baseRepository = this.componentRegistry.findEntityExtensionRepository(this.type);
            if (baseRepository != null) {
                try {
                    //Entity extension should have the same id of the master entity
                    Query q = findByRelatedEntityId(baseRepository, entity);
                    EntityExtension ext = (EntityExtension) baseRepository.find(q);
                    fillEntityWithExtension(entity, ext);
                } catch (jakarta.persistence.NoResultException | NoResultException ex) {
                    log.debug("No entity extension found for enitity {} with id {}", this.type.getName(), entity.getId());
                }
            }
        }
    }

    /**
     * @param entity
     * @param ext
     */
    private void fillEntityWithExtension(T entity, EntityExtension ext) {
        ExpandableEntity exp = (ExpandableEntity) entity;
        exp.setExtension(ext);
    }

    private Query findByRelatedEntityId(BaseRepository<?> entityExpansionRepository, T entity) {
        return entityExpansionRepository.getQueryBuilderInstance().field("relatedEntityId").equalTo(entity.getId());
    }
}
