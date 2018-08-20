package org.zstack.sdk.identity.role;

import org.zstack.sdk.identity.role.RoleType;
import org.zstack.sdk.identity.role.RoleState;

public class RoleInventory  {

    public java.lang.String uuid;
    public void setUuid(java.lang.String uuid) {
        this.uuid = uuid;
    }
    public java.lang.String getUuid() {
        return this.uuid;
    }

    public java.lang.String name;
    public void setName(java.lang.String name) {
        this.name = name;
    }
    public java.lang.String getName() {
        return this.name;
    }

    public java.lang.String description;
    public void setDescription(java.lang.String description) {
        this.description = description;
    }
    public java.lang.String getDescription() {
        return this.description;
    }

    public RoleType type;
    public void setType(RoleType type) {
        this.type = type;
    }
    public RoleType getType() {
        return this.type;
    }

    public RoleState state;
    public void setState(RoleState state) {
        this.state = state;
    }
    public RoleState getState() {
        return this.state;
    }

    public java.sql.Timestamp createDate;
    public void setCreateDate(java.sql.Timestamp createDate) {
        this.createDate = createDate;
    }
    public java.sql.Timestamp getCreateDate() {
        return this.createDate;
    }

    public java.sql.Timestamp lastOpDate;
    public void setLastOpDate(java.sql.Timestamp lastOpDate) {
        this.lastOpDate = lastOpDate;
    }
    public java.sql.Timestamp getLastOpDate() {
        return this.lastOpDate;
    }

    public java.util.List statements;
    public void setStatements(java.util.List statements) {
        this.statements = statements;
    }
    public java.util.List getStatements() {
        return this.statements;
    }

}
