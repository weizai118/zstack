package org.zstack.test.integration.networkservice.provider.virtualrouter

import org.springframework.http.HttpEntity
import org.zstack.core.db.DatabaseFacade
import org.zstack.core.db.Q
import org.zstack.header.network.l3.L3NetworkHostRouteVO
import org.zstack.header.network.l3.L3NetworkHostRouteVO_
import org.zstack.header.network.service.NetworkServiceConstants
import org.zstack.network.service.flat.FlatNetworkSystemTags
import org.zstack.network.service.virtualrouter.VirtualRouterCommands
import org.zstack.network.service.virtualrouter.VirtualRouterConstant
import org.zstack.network.service.virtualrouter.VirtualRouterVmVO
import org.zstack.sdk.L2NetworkInventory
import org.zstack.sdk.L3NetworkInventory
import org.zstack.sdk.VmInstanceInventory
import org.zstack.test.integration.networkservice.provider.NetworkServiceProviderTest
import org.zstack.testlib.EnvSpec
import org.zstack.testlib.SubCase
import org.zstack.utils.gson.JSONObjectUtil

/**
 * Created by weiwang on 01/06/2017.
 */
class VirtualRouterAttachNetworkCase extends SubCase {

    EnvSpec env
    DatabaseFacade dbf

    @Override
    void setup() {
        useSpring(NetworkServiceProviderTest.springSpec)
    }

    @Override
    void environment() {
        env = VirtualRouterNetworkServiceEnv.oneVmOneHostVyosOnEipEnv()
    }

    @Override
    void test() {
        env.create {
            testHostRouteOnL3Network()
            testAttachOneSystemNetwork()
        }
    }

    void testHostRouteOnL3Network() {
        def l3 = env.inventoryByName("l3") as L3NetworkInventory
        assert !Q.New(L3NetworkHostRouteVO.class).eq(L3NetworkHostRouteVO_.l3NetworkUuid, l3.uuid)
                .eq(L3NetworkHostRouteVO_.prefix, NetworkServiceConstants.METADATA_HOST_PREFIX).exists
    }

    void testAttachOneSystemNetwork() {
        def l2 = env.inventoryByName("l2") as L2NetworkInventory
        def l2_1 = env.inventoryByName("l2-1") as L2NetworkInventory

        L3NetworkInventory l3_1 = createL3Network {
            delegate.category = "Public"
            delegate.l2NetworkUuid = l2.uuid
            delegate.name = "pubL3-2"
        }

        addIpRange {
            delegate.name = "TestIpRange"
            delegate.l3NetworkUuid = l3_1.uuid
            delegate.startIp = "11.168.200.10"
            delegate.endIp = "11.168.200.253"
            delegate.gateway = "11.168.200.1"
            delegate.netmask = "255.255.255.0"
        }

        L3NetworkInventory l3_2 = createL3Network {
            delegate.category = "Public"
            delegate.l2NetworkUuid = l2_1.uuid
            delegate.name = "pubL3-3"
        }

        addIpRange {
            delegate.name = "TestIpRange"
            delegate.l3NetworkUuid = l3_2.uuid
            delegate.startIp = "11.168.200.50"
            delegate.endIp = "11.168.200.60"
            delegate.gateway = "11.168.200.1"
            delegate.netmask = "255.255.255.0"
        }

        VirtualRouterVmVO vr = Q.New(VirtualRouterVmVO.class).find()

        VirtualRouterCommands.ConfigureNicCmd cmd = null
        env.afterSimulator(VirtualRouterConstant.VR_CONFIGURE_NIC_PATH) { rsp, HttpEntity<String> e ->
            cmd = JSONObjectUtil.toObject(e.body, VirtualRouterCommands.ConfigureNicCmd.class)
            return rsp
        }

        VmInstanceInventory inv = attachL3NetworkToVm {
            delegate.l3NetworkUuid = l3_1.getUuid()
            delegate.vmInstanceUuid = vr.uuid
        }

        assert cmd != null

        VirtualRouterCommands.RemoveNicCmd rcmd = null
        env.afterSimulator(VirtualRouterConstant.VR_REMOVE_NIC_PATH) { rsp, HttpEntity<String> e ->
            rcmd = JSONObjectUtil.toObject(e.body, VirtualRouterCommands.RemoveNicCmd.class)
            return rsp
        }

        expect(AssertionError.class) {
            attachL3NetworkToVm {
                delegate.l3NetworkUuid = l3_2.getUuid()
                delegate.vmInstanceUuid = vr.uuid
            }
        }

        detachL3NetworkFromVm {
            delegate.vmNicUuid = (inv.vmNics.stream()
                    .filter{nic -> l3_1.getUuid().equals(nic.l3NetworkUuid)}.find() as org.zstack.sdk.VmNicInventory).uuid
        }

        assert rcmd != null
    }

    @Override
    void clean() {
        env.delete()
    }
}
