package org.zstack.header.identity;

import org.zstack.header.vo.BaseResource;
import org.zstack.header.vo.EntityGraph;
import org.zstack.header.vo.ForeignKey;
import org.zstack.header.vo.ForeignKey.ReferenceOption;
import org.zstack.header.vo.ResourceVO;

import javax.persistence.*;
import java.sql.Timestamp;

@Entity
@Table
@BaseResource
@EntityGraph(
        parents = {
                @EntityGraph.Neighbour(type = AccountVO.class, myField = "accountUuid", targetField = "uuid")
        }
)
public class PolicyVO extends ResourceVO implements OwnedByAccount {
    @Column
    private String name;

    @Column
    @ForeignKey(parentEntityClass = AccountVO.class, parentKey = "uuid", onDeleteAction = ReferenceOption.CASCADE)
    private String accountUuid;

    @Column
    private String data;

    @Column
    private Timestamp createDate;

    @Column
    private Timestamp lastOpDate;

    @Column
    @Enumerated(EnumType.STRING)
    private PolicyType type = PolicyType.Customized;

    @PreUpdate
    private void preUpdate() {
        lastOpDate = null;
    }

    public PolicyType getType() {
        return type;
    }

    public void setType(PolicyType type) {
        this.type = type;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getAccountUuid() {
        return accountUuid;
    }

    public void setAccountUuid(String accountUuid) {
        this.accountUuid = accountUuid;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Timestamp getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Timestamp createDate) {
        this.createDate = createDate;
    }

    public Timestamp getLastOpDate() {
        return lastOpDate;
    }

    public void setLastOpDate(Timestamp lastOpDate) {
        this.lastOpDate = lastOpDate;
    }
}
