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

import it.water.core.api.model.PaginableResult;
import it.water.core.api.registry.ComponentRegistry;
import it.water.core.api.repository.query.Query;
import it.water.core.api.repository.query.operands.FieldValueListOperand;
import it.water.core.api.repository.query.operands.FieldValueOperand;
import it.water.core.api.repository.query.operations.*;
import it.water.core.api.service.Service;
import it.water.core.interceptors.annotations.Inject;
import it.water.core.testing.utils.junit.WaterTestExtension;
import it.water.repository.entity.model.exceptions.DuplicateEntityException;
import it.water.repository.entity.model.exceptions.NoResultException;
import it.water.repository.jpa.api.TestEntityDetailsRepository;
import it.water.repository.jpa.api.TestEntityRepository;
import it.water.repository.jpa.constraints.DuplicateConstraintValidator;
import it.water.repository.jpa.entity.TestEntity;
import it.water.repository.jpa.entity.TestEntityDetails;
import it.water.repository.jpa.model.AbstractJpaExpandableEntity;
import it.water.repository.jpa.query.PredicateBuilder;
import it.water.repository.jpa.repository.TestEntityRepositoryImpl;
import it.water.repository.query.order.DefaultQueryOrder;
import it.water.repository.query.order.DefaultQueryOrderParameter;
import jakarta.persistence.criteria.Root;
import lombok.Setter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith({WaterTestExtension.class})
class JpaRepositoryTest implements Service {

    @Inject
    @Setter
    private ComponentRegistry componentRegistry;

    @Inject
    @Setter
    private TestEntityRepository testEntityRepository;

    @Inject
    @Setter
    private TestEntityDetailsRepository testEntityDetailsRepository;

    @Test
    @Order(0)
    void checkPreconditions() {
        Assertions.assertNotNull(testEntityRepository);
        Assertions.assertNotNull(testEntityRepository.getEntityManager());
    }

    @Test
    @Order(1)
    void testEntity() {
        TestEntity entity = new TestEntity();
        entity.setUniqueField("a");
        entity.setCombinedUniqueField1("b");
        entity.setCombinedUniqueField2("c");
        Assertions.assertEquals(0, entity.getId());
        Assertions.assertEquals(1, entity.getEntityVersion());
        Assertions.assertNotNull(entity.getEntityModifyDate());
        Assertions.assertNotNull(entity.getEntityCreateDate());
        testEntityRepository.getEntityManager().getTransaction().begin();
        testEntityRepository.persist(entity);
        testEntityRepository.getEntityManager().getTransaction().commit();
        Assertions.assertTrue(entity.getId() > 0);
        Assertions.assertEquals(1, entity.getEntityVersion());
    }

    //Test entity has 1 unique field , and another unique composite fields
    //this method tests whether repository raises duplicate exception correctly
    @Test
    @Order(2)
    void testDuplicateFails() {
        TestEntity entity = new TestEntity();
        entity.setUniqueField("a");
        Assertions.assertThrows(DuplicateEntityException.class, () -> {
            testEntityRepository.persist(entity);
        });
        //fails if cobined unique key constraint is matched
        entity.setUniqueField("a1");
        entity.setCombinedUniqueField1("b");
        entity.setCombinedUniqueField2("c");
        Assertions.assertThrows(DuplicateEntityException.class, () -> {
            testEntityRepository.persist(entity);
        });
        //pass
        entity.setUniqueField("a1");
        entity.setCombinedUniqueField1("b1");
        entity.setCombinedUniqueField2("c");
        testEntityRepository.persist(entity);
        Assertions.assertTrue(entity.getId() > 0);
    }

    @Test
    @Order(3)
    void testFind() {
        DefaultQueryOrder order = new DefaultQueryOrder();
        //test descending order
        order.addOrderField("uniqueField", false);
        PaginableResult<TestEntity> results = testEntityRepository.findAll(4, 1, null, order);
        Assertions.assertEquals(2, results.getResults().size());
        Assertions.assertEquals("a1", results.getResults().stream().findFirst().get().getUniqueField());
        //test query
        Query filter = testEntityRepository.getQueryBuilderInstance().field("uniqueField").equalTo("a").or(testEntityRepository.getQueryBuilderInstance().field("uniqueField").equalTo("a1"));
        Query filter1 = testEntityRepository.getQueryBuilderInstance().createQueryFilter("uniqueField=a OR uniqueField=a1");
        Assertions.assertNotNull(testEntityRepository.getQueryBuilderInstance().createQueryFilter("(uniqueField=a)"));
        Assertions.assertEquals(filter.getDefinition(), filter1.getDefinition());
        Assertions.assertNotNull(testEntityRepository.getQueryBuilderInstance().createQueryFilter("-@das"));
        results = testEntityRepository.findAll(10, 1, filter, order);
        Assertions.assertEquals(2, results.getResults().size());

        filter = testEntityRepository.getQueryBuilderInstance().field("uniqueField").equalTo("a1");
        results = testEntityRepository.findAll(10, 1, filter, order);
        Assertions.assertEquals(1, results.getResults().size());

        TestEntity specificEntity = testEntityRepository.find(filter);
        Assertions.assertNotNull(specificEntity);
        Assertions.assertEquals("a1", specificEntity.getUniqueField());
    }

    @Test
    @Order(4)
    void testFindAndUpdate() {
        TestEntity foundEntity = testEntityRepository.find(testEntityRepository.getQueryBuilderInstance().field("uniqueField").equalTo("a"));
        //find by id
        Assertions.assertNotNull(testEntityRepository.find(foundEntity.getId()));
        foundEntity.setUniqueField("a2");
        testEntityRepository.update(foundEntity);
        Assertions.assertEquals("a2", testEntityRepository.find(foundEntity.getId()).getUniqueField());
        Query q = testEntityRepository.getQueryBuilderInstance().field("uniqueField").equalTo("a");
        Assertions.assertThrows(it.water.repository.entity.model.exceptions.NoResultException.class, () -> {
            testEntityRepository.find(q);
        });
    }

    @Test
    @Order(5)
    void testRemoveById() {
        TestEntity newEntity = new TestEntity();
        newEntity.setUniqueField("uniqueField");
        newEntity.setCombinedUniqueField1("uniqueCombined1");
        newEntity.setCombinedUniqueField2("uniqueCombined12");
        testEntityRepository.persist(newEntity);
        long entityId = newEntity.getId();
        testEntityRepository.getEntityManager().flush();
        testEntityRepository.remove(entityId);
        Assertions.assertThrows(it.water.repository.entity.model.exceptions.NoResultException.class, () -> {
            testEntityRepository.find(entityId);
        });
    }

    @Test
    @Order(6)
    void testRemoveEntity() {
        TestEntity newEntity = new TestEntity();
        newEntity.setUniqueField("uniqueField");
        newEntity.setCombinedUniqueField1("uniqueCombined1");
        newEntity.setCombinedUniqueField2("uniqueCombined12");
        testEntityRepository.persist(newEntity);
        long entityId = newEntity.getId();
        testEntityRepository.getEntityManager().flush();
        testEntityRepository.remove(newEntity);
        Assertions.assertThrows(it.water.repository.entity.model.exceptions.NoResultException.class, () -> {
            testEntityRepository.find(entityId);
        });
    }

    @Test
    @Order(7)
    void testRemoveEntityByIds() {
        TestEntity newEntity = new TestEntity();
        newEntity.setUniqueField("uniqueField");
        newEntity.setCombinedUniqueField1("uniqueCombined1");
        newEntity.setCombinedUniqueField2("uniqueCombined12");
        TestEntity newEntity1 = new TestEntity();
        newEntity.setUniqueField("uniqueField1");
        newEntity.setCombinedUniqueField1("uniqueCombined3");
        newEntity.setCombinedUniqueField2("uniqueCombined4");
        testEntityRepository.persist(newEntity);
        testEntityRepository.persist(newEntity1);
        testEntityRepository.getEntityManager().flush();
        long entityId = newEntity.getId();
        long entity1Id = newEntity1.getId();
        List<Long> ids = new ArrayList<>();
        ids.add(entityId);
        ids.add(entity1Id);
        testEntityRepository.removeAllByIds(ids);
        Assertions.assertThrows(it.water.repository.entity.model.exceptions.NoResultException.class, () -> {
            testEntityRepository.find(entityId);
        });
        Assertions.assertThrows(it.water.repository.entity.model.exceptions.NoResultException.class, () -> {
            testEntityRepository.find(entity1Id);
        });
    }

    @Test
    @Order(8)
    void testRemoveEntities() {
        TestEntity newEntity = new TestEntity();
        newEntity.setUniqueField("uniqueField");
        newEntity.setCombinedUniqueField1("uniqueCombined1");
        newEntity.setCombinedUniqueField2("uniqueCombined12");
        TestEntity newEntity1 = new TestEntity();
        newEntity.setUniqueField("uniqueField1");
        newEntity.setCombinedUniqueField1("uniqueCombined3");
        newEntity.setCombinedUniqueField2("uniqueCombined4");
        testEntityRepository.persist(newEntity);
        testEntityRepository.persist(newEntity1);
        testEntityRepository.getEntityManager().flush();
        long entityId = newEntity.getId();
        long entity1Id = newEntity1.getId();
        List<TestEntity> entities = new ArrayList<>();
        entities.add(newEntity);
        entities.add(newEntity1);
        testEntityRepository.removeAll(entities);
        Assertions.assertThrows(it.water.repository.entity.model.exceptions.NoResultException.class, () -> {
            testEntityRepository.find(entityId);
        });
        Assertions.assertThrows(NoResultException.class, () -> {
            testEntityRepository.find(entity1Id);
        });
    }

    @Test
    @Order(8)
    void testRemoveAll() {
        testEntityRepository.removeAll();
        Assertions.assertEquals(0, testEntityRepository.findAll(1, 1, null, null).getResults().size());
    }

    @Test
    @Order(9)
    void testOrderParameter() {
        DefaultQueryOrderParameter param1 = new DefaultQueryOrderParameter();
        param1.setName("a");
        DefaultQueryOrderParameter param2 = new DefaultQueryOrderParameter();
        param2.setName("a");
        DefaultQueryOrderParameter param3 = new DefaultQueryOrderParameter();
        param3.setName("b");
        Assertions.assertEquals(param1, param2);
        Assertions.assertNotEquals(param1, param3);
        Assertions.assertNotEquals(param2, param3);
    }

    @Test
    @Order(10)
    void testPredicateGeneration() {
        Root<TestEntity> root = testEntityRepository.getEntityManager().getCriteriaBuilder().createQuery(TestEntity.class).from(TestEntity.class);
        PredicateBuilder<TestEntity> predicateBuilder = new PredicateBuilder<>(root, testEntityRepository.getEntityManager().getCriteriaBuilder().createQuery(TestEntity.class), testEntityRepository.getEntityManager().getCriteriaBuilder());
        NotOperation notOperation = new NotOperation();
        notOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("uniqueField").equalTo("a"));
        Assertions.assertEquals("NOT (uniqueField = a)", notOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(notOperation));

        NotEqualTo notEqualToOperation = new NotEqualTo();
        Assertions.assertEquals("NotEqualTo (!=)", notEqualToOperation.getName());
        notEqualToOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("uniqueField"), new FieldValueOperand("a"));
        Assertions.assertEquals("uniqueField <> a", notEqualToOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(notEqualToOperation));

        EqualTo equalToOperation = new EqualTo();
        Assertions.assertEquals("EqualTo (=)", equalToOperation.getName());
        equalToOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("uniqueField"), new FieldValueOperand("a"));
        Assertions.assertEquals("uniqueField = a", equalToOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(equalToOperation));

        LowerThan lowerThanOperation = new LowerThan();
        lowerThanOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("numberField"), new FieldValueOperand(10));
        Assertions.assertEquals("numberField < 10", lowerThanOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(lowerThanOperation));

        LowerOrEqualThan lowerOrEqualThanOperation = new LowerOrEqualThan();
        lowerOrEqualThanOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("numberField"), new FieldValueOperand(10));
        Assertions.assertEquals("numberField <= 10", lowerOrEqualThanOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(lowerOrEqualThanOperation));

        GreaterThan greaterThanOperation = new GreaterThan();
        greaterThanOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("numberField"), new FieldValueOperand(10));
        Assertions.assertEquals("numberField > 10", greaterThanOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(greaterThanOperation));

        GreaterOrEqualThan greaterOrEqualThanOperation = new GreaterOrEqualThan();
        greaterOrEqualThanOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("numberField"), new FieldValueOperand(10));
        Assertions.assertEquals("numberField >= 10", greaterOrEqualThanOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(greaterOrEqualThanOperation));

        Like likeOperation = new Like();
        likeOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("uniqueField"), new FieldValueOperand("a"));
        Assertions.assertEquals("uniqueField LIKE a", likeOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(likeOperation));

        In inOperation = new In();
        inOperation.defineOperands(testEntityRepository.getQueryBuilderInstance().field("uniqueField"), new FieldValueListOperand(List.of("a", "b")));
        Assertions.assertEquals("uniqueField IN (a,b)", inOperation.getDefinition());
        Assertions.assertNotNull(predicateBuilder.buildPredicate(inOperation));

    }

    @Test
    @Order(11)
    void testBaseJpaRepositoryConstructors() {
        Assertions.assertDoesNotThrow(() -> new TestEntityRepositoryImpl());
        Assertions.assertDoesNotThrow(() -> new TestEntityRepositoryImpl(TestEntity.class, "water-default-persistence-unit", testEntityRepository.getEntityManager()));
        Assertions.assertDoesNotThrow(() -> new TestEntityRepositoryImpl(TestEntity.class, testEntityRepository.getEntityManager()));
        Assertions.assertDoesNotThrow(() -> new TestEntityRepositoryImpl(TestEntity.class, testEntityRepository.getEntityManager(), new DuplicateConstraintValidator()));
    }

    @Test
    @Order(12)
    void testEntityExtension() {
        TestEntity testEntity = new TestEntity();
        TestEntityDetails testEntityDetails = new TestEntityDetails();
        //for coverage
        AbstractJpaExpandableEntity expandableEntity = (AbstractJpaExpandableEntity) testEntity;
        expandableEntity.setExtraFields("test", "test");
        expandableEntity.setExtraFields(new HashMap<>());
        Assertions.assertNotNull(expandableEntity.getExtraFields());
        testEntityDetails.setExtensionField("extensionField");
        testEntityDetails.setExtensionField2(2);

        testEntity.setUniqueField("a");
        testEntity.setCombinedUniqueField1("b");
        testEntity.setCombinedUniqueField2("c");
        //this will be done automatically in the rest module converting maps into extension
        testEntity.setExtension(testEntityDetails);
        testEntity = testEntityRepository.persist(testEntity);
        testEntity = testEntityRepository.find(testEntity.getId());
        //checking data is loaded into the extension object
        Assertions.assertNotNull(testEntity.getExtension());
        testEntityDetails = (TestEntityDetails) testEntity.getExtension();
        Assertions.assertEquals("extensionField", testEntityDetails.getExtensionField());
        Assertions.assertEquals(2, testEntityDetails.getExtensionField2());
        Assertions.assertEquals(testEntity.getId(), testEntityDetails.getRelatedEntityId());
        //Check updating
        testEntityDetails.setExtensionField("extensionFieldUpdated");
        testEntity.setExtension(testEntityDetails);
        testEntity = testEntityRepository.update(testEntity);
        testEntity = testEntityRepository.find(testEntity.getId());
        testEntityDetails = (TestEntityDetails) testEntity.getExtension();
        Assertions.assertEquals("extensionFieldUpdated", testEntityDetails.getExtensionField());
        Assertions.assertEquals(2, testEntityDetails.getEntityVersion());
        long toRemoveEntityId = testEntity.getId();
        Query q = testEntityDetailsRepository.getQueryBuilderInstance().field("relatedEntityId").equalTo(toRemoveEntityId);
        Assertions.assertDoesNotThrow(() -> testEntityDetailsRepository.find(q));
        testEntityRepository.remove(testEntity);
        Assertions.assertThrows(NoResultException.class, () -> testEntityDetailsRepository.find(q));
    }

    @Test
    @Order(13)
    void testPersistenceUnitInfo() {
        WaterPersistenceUnitInfo waterPersistenceUnitInfo = new WaterPersistenceUnitInfo("water-default-persistence", TestEntity.class);
        Assertions.assertDoesNotThrow(() -> waterPersistenceUnitInfo.addManagedClass("classProva"));
        Assertions.assertNotNull(waterPersistenceUnitInfo.getPersistenceProviderClassName());
        Assertions.assertNotNull(waterPersistenceUnitInfo.getPersistenceXMLSchemaVersion());
    }


    @SuppressWarnings("unused")
    private void createAndPersisteExampleEntity() {
        TestEntity testEntity = new TestEntity();
        testEntity.setUniqueField("a");
        testEntity.setCombinedUniqueField1("b");
        testEntity.setCombinedUniqueField2("c");
        testEntityRepository.persist(testEntity);
    }
}
