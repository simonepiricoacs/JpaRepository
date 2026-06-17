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

import it.water.core.api.bundle.ApplicationProperties;
import it.water.repository.jpa.constraints.DuplicateConstraintValidator;
import it.water.repository.jpa.entity.TestEntity;
import it.water.repository.jpa.repository.TestEntityRepositoryImpl;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Unit tests for {@link BaseJpaRepositoryImpl#resolveMaxPageSize()}.
 *
 * Fix #22 coverage targets for the resolve logic:
 *  - applicationProperties is null  → DEFAULT_MAX_PAGE_SIZE (200)
 *  - property is absent (null)       → DEFAULT_MAX_PAGE_SIZE
 *  - property is a valid positive int → that configured value
 *  - property is zero                → DEFAULT_MAX_PAGE_SIZE
 *  - property is negative            → DEFAULT_MAX_PAGE_SIZE
 *  - property is an unparseable string → DEFAULT_MAX_PAGE_SIZE
 *  - property is a valid string representation of a positive int → that value
 *  - property returned as non-String object → toString() is used, value honoured
 */
@ExtendWith(MockitoExtension.class)
class ResolveMaxPageSizeTest {

    @Mock
    private ApplicationProperties applicationProperties;

    @Mock
    private EntityManager entityManager;

    /**
     * Inner subclass that exposes the protected {@code resolveMaxPageSize()} method.
     * Lives in the same package as {@link BaseJpaRepositoryImpl}, so the protected method
     * is accessible directly without reflection.
     */
    private static class InspectableRepo extends TestEntityRepositoryImpl {

        InspectableRepo(EntityManager em) {
            super(TestEntity.class, em, new DuplicateConstraintValidator());
        }

        @Override
        public void txExpr(Transactional.TxType txType, Consumer<EntityManager> function) {
            function.accept(getEntityManager());
        }

        @Override
        public <R> R tx(Transactional.TxType txType, Function<EntityManager, R> function) {
            return function.apply(getEntityManager());
        }

        int callResolveMaxPageSize() {
            return resolveMaxPageSize();
        }
    }

    private InspectableRepo makeRepo(ApplicationProperties props) {
        InspectableRepo repo = new InspectableRepo(entityManager);
        repo.setApplicationProperties(props);
        return repo;
    }

    // ------------------------------------------------------------------
    // Constant verification
    // ------------------------------------------------------------------

    @Test
    void defaultMaxPageSizeConstant_is200() {
        Assertions.assertEquals(200, BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE);
    }

    @Test
    void maxPageSizePropertyConstant_matchesExpectedKey() {
        Assertions.assertEquals("water.rest.pagination.max.delta",
                BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY);
    }

    // ------------------------------------------------------------------
    // applicationProperties == null
    // ------------------------------------------------------------------

    @Test
    void resolveMaxPageSize_applicationPropertiesNull_returnsDefault() {
        InspectableRepo repo = makeRepo(null);
        Assertions.assertEquals(BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE,
                repo.callResolveMaxPageSize());
    }

    // ------------------------------------------------------------------
    // property absent (getProperty returns null)
    // ------------------------------------------------------------------

    @Test
    void resolveMaxPageSize_propertyAbsent_returnsDefault() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn(null);
        InspectableRepo repo = makeRepo(applicationProperties);
        Assertions.assertEquals(BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE,
                repo.callResolveMaxPageSize());
    }

    // ------------------------------------------------------------------
    // Valid positive integer (as String)
    // ------------------------------------------------------------------

    @Test
    void resolveMaxPageSize_validPositiveIntegerProperty_returnsConfiguredValue() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn("500");
        InspectableRepo repo = makeRepo(applicationProperties);
        Assertions.assertEquals(500, repo.callResolveMaxPageSize());
    }

    @Test
    void resolveMaxPageSize_validPositiveIntegerPropertyWithWhitespace_returnsConfiguredValue() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn("  100  ");
        InspectableRepo repo = makeRepo(applicationProperties);
        Assertions.assertEquals(100, repo.callResolveMaxPageSize());
    }

    // ------------------------------------------------------------------
    // Property is zero → fall back to default
    // ------------------------------------------------------------------

    @Test
    void resolveMaxPageSize_propertyIsZero_returnsDefault() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn("0");
        InspectableRepo repo = makeRepo(applicationProperties);
        Assertions.assertEquals(BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE,
                repo.callResolveMaxPageSize());
    }

    // ------------------------------------------------------------------
    // Property is negative → fall back to default
    // ------------------------------------------------------------------

    @Test
    void resolveMaxPageSize_propertyIsNegative_returnsDefault() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn("-50");
        InspectableRepo repo = makeRepo(applicationProperties);
        Assertions.assertEquals(BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE,
                repo.callResolveMaxPageSize());
    }

    // ------------------------------------------------------------------
    // Property is non-numeric (unparseable) → fall back to default
    // ------------------------------------------------------------------

    @Test
    void resolveMaxPageSize_propertyIsNonNumeric_returnsDefault() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn("large");
        InspectableRepo repo = makeRepo(applicationProperties);
        Assertions.assertEquals(BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE,
                repo.callResolveMaxPageSize());
    }

    @Test
    void resolveMaxPageSize_propertyIsEmptyString_returnsDefault() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn("");
        InspectableRepo repo = makeRepo(applicationProperties);
        // Integer.parseInt("".trim()) throws NumberFormatException → default
        Assertions.assertEquals(BaseJpaRepositoryImpl.DEFAULT_MAX_PAGE_SIZE,
                repo.callResolveMaxPageSize());
    }

    // ------------------------------------------------------------------
    // Property returned as a non-String object whose toString() is a number
    // ------------------------------------------------------------------

    @Test
    void resolveMaxPageSize_propertyReturnedAsIntegerObject_returnsConfiguredValue() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn(300);
        InspectableRepo repo = makeRepo(applicationProperties);
        Assertions.assertEquals(300, repo.callResolveMaxPageSize());
    }

    @Test
    void resolveMaxPageSize_propertyReturnedAsIntegerObjectValueOne_returnsOne() {
        Mockito.when(applicationProperties.getProperty(BaseJpaRepositoryImpl.MAX_PAGE_SIZE_PROPERTY))
               .thenReturn(1);
        InspectableRepo repo = makeRepo(applicationProperties);
        Assertions.assertEquals(1, repo.callResolveMaxPageSize());
    }
}
