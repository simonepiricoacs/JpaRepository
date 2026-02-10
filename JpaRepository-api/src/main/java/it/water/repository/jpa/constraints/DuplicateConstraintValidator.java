
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

package it.water.repository.jpa.constraints;

import it.water.core.api.model.BaseEntity;
import it.water.core.api.repository.BaseRepository;
import it.water.core.api.repository.RepositoryConstraintValidator;
import it.water.core.api.repository.query.Query;
import it.water.repository.entity.model.exceptions.DuplicateEntityException;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;


/**
 * @Author Aristide Cittadino.
 * Duplicate Constraint Validator: it checks wheter the current saving entity has already been saved on the database according
 * to defined unique constraints.
 */
public class DuplicateConstraintValidator implements RepositoryConstraintValidator {
    private Logger log = LoggerFactory.getLogger(DuplicateConstraintValidator.class);

    /**
     * Based on @UniqueConstriant hibernate annotation this method tries to check if
     * the entity is already present in the database without generating rollback
     * exception
     */
    @Override
    public <T extends BaseEntity> void checkConstraint(T entity, Class<T> type, BaseRepository<T> entityRepository) {
        log.debug("Checking duplicates for entity {}", type.getName());
        Table[] tableAnnotation = entity.getClass().getAnnotationsByType(Table.class);
        if (tableAnnotation != null && tableAnnotation.length > 0) {
            UniqueConstraint[] uniqueConstraints = tableAnnotation[0].uniqueConstraints();
            processUniqueContraints(uniqueConstraints, entity, type, entityRepository);
        }
    }

    /**
     * @param uniqueConstraints
     * @param entity
     * @param type
     * @param entityRepository
     * @param <T>
     */
    private <T extends BaseEntity> void processUniqueContraints(UniqueConstraint[] uniqueConstraints, T entity, Class<T> type, BaseRepository<T> entityRepository) {
        if (uniqueConstraints != null && uniqueConstraints.length > 0) {
            for (int i = 0; i < uniqueConstraints.length; i++) {
                String[] columnNames = uniqueConstraints[i].columnNames();
                Query filter = createQueryFilter(entity, columnNames, type, entityRepository);
                if (filter == null) {
                    log.debug("Could not build query filter for unique constraint, skipping duplicate check");
                    continue;
                }
                log.debug("Executing duplicate check query with filter: {}", filter);
                try {
                    T result = entityRepository.find(filter);
                    // if the entity has not the same id than it's duplicated
                    if (result.getId() != entity.getId())
                        throw new DuplicateEntityException(columnNames);
                } catch (it.water.repository.entity.model.exceptions.NoResultException | NoResultException e) {
                    log.debug("Entity duplicate check passed!");
                }
            }
        }
    }


    /**
     * @param entity      Current entity
     * @param columnNames list of unique column names
     * @param type        entity type
     * @param <T>         Entity which is an WaterBaseEntity
     * @param entityRepository
     * @return Query to check wheter duplicate entity exists or not
     */
    private <T extends BaseEntity> Query createQueryFilter(T entity, String[] columnNames, Class<T> type, BaseRepository<T> entityRepository) {
        log.debug("Creating query filter...");
        Query compositeQuery = null;
        for (int j = 0; j < columnNames.length; j++) {
            String fieldName = columnNames[j];
            String innerField = null;
            // Field is a relationship, so we need to do 2 invocations
            if (fieldName.contains("_")) {
                String temp = fieldName;
                fieldName = fieldName.substring(0, fieldName.indexOf("_"));
                innerField = temp.substring(temp.indexOf("_") + 1);
            }

            String getterMethod = "get" + fieldName.substring(0, 1).toUpperCase()
                    + fieldName.substring(1);
            Query condition = buildCondition(entity, type, fieldName, innerField, getterMethod, entityRepository);
            if (condition == null) {
                return null;
            }
            if (compositeQuery == null) {
                compositeQuery = condition;
            } else {
                compositeQuery = compositeQuery.and(condition);
            }
        }
        return compositeQuery;
    }

    /**
     * Supports null values for FK fields by leveraging equalTo(null) which translates to IS NULL in JPA.
     *
     * @param entity
     * @param type
     * @param fieldName
     * @param innerField
     * @param getterMethodName
     * @param entityRepository
     * @return Query object to check whether duplicate entity exists or not
     * @param <T>
     */

    private <T extends BaseEntity> Query buildCondition(T entity, Class<T> type, String fieldName, String innerField, String getterMethodName, BaseRepository<T> entityRepository) {
        try {
            Method m = type.getMethod(getterMethodName);
            Object value = null;
            // when inner field is null, the relative getter is invoked on the
            // target entity
            if (innerField == null)
                value = m.invoke(entity);
                // when inner field is != null then, the getter method is called on the
                // related
                // entity
            else {
                String getterInnerMethod = "get"
                        + innerField.substring(0, 1).toUpperCase()
                        + innerField.substring(1);
                Object innerEntity = m.invoke(entity);
                if (innerEntity != null) {
                    Method innerMethod = innerEntity.getClass()
                            .getMethod(getterInnerMethod);
                    value = innerMethod.invoke(innerEntity);
                } else {
                    value = null;
                }
            }
            String queryFieldPath = (innerField == null) ? fieldName : fieldName + "." + innerField;
            // equalTo(null) will be translated to IS NULL by the PredicateBuilder
            return entityRepository.getQueryBuilderInstance().field(queryFieldPath).equalTo(value);
        } catch (Exception e) {
            log.error("Impossible to find getter method {} on {}", getterMethodName, type.getName());
            return null;
        }
    }
}
