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

package it.water.repository.jpa.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import it.water.core.api.entity.tenant.TenantResource;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;


/**
 * @Author Aristide Cittadino.
 * Base class for expandable JPA entities that belong to a single company (tenant).
 * Extends {@link AbstractJpaExpandableEntity} so a tenantized entity stays expandable, and carries
 * the opaque, nullable {@code companyId} column mandated by {@link TenantResource}
 * (null = global / cross-tenant instance). The company id is server-assigned from the active
 * company in the SecurityContext, therefore it is {@code @JsonIgnore} (not client-settable),
 * mirroring how {@code ownerUserId} is handled on owned resources.
 */
@MappedSuperclass
public abstract class AbstractJpaExpandableTenantEntity extends AbstractJpaExpandableEntity implements TenantResource {

    private Long companyId;

    @Override
    @Column(name = "company_id")
    @JsonIgnore
    public Long getCompanyId() {
        return companyId;
    }

    @Override
    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }
}
