package org.zstack.testlib.identity

import org.zstack.sdk.AccountInventory
import org.zstack.sdk.SessionInventory
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.Spec
import org.zstack.testlib.SpecID
import org.zstack.testlib.SpecParam

/**
 * Created by xing5 on 2017/2/15.
 */
class AccountSpec extends Spec {
    @SpecParam(required = true)
    String name
    @SpecParam(required = true)
    String password

    AccountInventory inventory
    SessionInventory session

    AccountSpec(EnvSpec envSpec) {
        super(envSpec)
    }

    private List<String> roleNames = []

    void useRole(String rname) {
        preCreate {
            addDependency(rname, RoleSpec.class)
        }

        roleNames.add(rname)
    }

    void policy(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = PolicySpec.class) Closure c) {
        def spec = new PolicySpec(envSpec)
        c.delegate = spec
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c()
        addChild(spec)
    }

    void user(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = UserSpec.class) Closure c) {
        def spec = new UserSpec(envSpec)
        c.delegate = spec
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c()
        addChild(spec)
    }

    void group(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = UserGroupSpec.class) Closure c) {
        def spec = new UserGroupSpec(envSpec)
        c.delegate = spec
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c()
        addChild(spec)
    }

    void role(@DelegatesTo(strategy = Closure.DELEGATE_FIRST, value = RoleSpec.class) Closure c) {
        def spec = new RoleSpec(envSpec)
        c.delegate = spec
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c()
        addChild(spec)
    }

    SpecID create(String uuid, String sessionId) {
        inventory = createAccount {
            delegate.resourceUuid = uuid
            delegate.sessionId = sessionId
            delegate.name = name
            delegate.password = password
        }

        session = logInByAccount {
            delegate.accountName = name
            delegate.password = password
        } as SessionInventory

        postCreate {
            inventory = queryAccount {
                conditions = ["uuid=${inventory.uuid}".toString()]
            }[0]

            roleNames.each { rname ->
                def inv = findSpec(rname, RoleSpec.class).inventory

                attachRoleToAccount {
                    roleUuid = inv.uuid
                    accountUuid = inventory.uuid
                    delegate.sessionId = sessionId
                }
            }
        }

        return id(name, inventory.uuid)
    }

    Closure use() {
        String session = null

        return {
            if (session == null) {
                SessionInventory s = logInByAccount {
                    delegate.accountName = name
                    delegate.password = password
                } as SessionInventory

                session = s.uuid
            }

            return session
        }
    }

    @Override
    void delete(String sessionId) {
        if (inventory != null) {
            deleteAccount {
                delegate.uuid = inventory.uuid
                delegate.sessionId = sessionId
            }

            inventory = null
        }
    }
}
