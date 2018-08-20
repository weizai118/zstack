package org.zstack.header.vm;

import org.springframework.http.HttpMethod;
import org.zstack.header.identity.Action;
import org.zstack.header.message.APICreateMessage;
import org.zstack.header.message.APIEvent;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.APIParam;
import org.zstack.header.network.l3.L3NetworkVO;
import org.zstack.header.notification.ApiNotification;
import org.zstack.header.other.APIAuditor;
import org.zstack.header.rest.RestRequest;
import org.zstack.header.tag.TagResourceType;


@TagResourceType(VmNicVO.class)
@Action(category = VmInstanceConstant.ACTION_CATEGORY)
@RestRequest(
        path = "/nics",
        method = HttpMethod.POST,
        responseClass = APICreateVmNicEvent.class,
        parameterName = "params"
)
public class APICreateVmNicMsg extends APICreateMessage implements APIAuditor {

    /**
     * @desc uuid of L3Network where the nic will be created
     */
    @APIParam(resourceType = L3NetworkVO.class, checkAccount = true)
    private String l3NetworkUuid;

    @APIParam(required = false)
    private String ip;

    public String getL3NetworkUuid() {
        return l3NetworkUuid;
    }

    public void setL3NetworkUuid(String l3NetworkUuid) {
        this.l3NetworkUuid = l3NetworkUuid;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public static APICreateVmNicMsg __example__() {
        APICreateVmNicMsg msg = new APICreateVmNicMsg();
        msg.setL3NetworkUuid(uuid());
        return msg;
    }

    public ApiNotification __notification__() {
        APIMessage that = this;

        return new ApiNotification() {
            @Override
            public void after(APIEvent evt) {
                if (evt.isSuccess()) {
                    ntfy("Created").resource(((APICreateVmNicEvent) evt).getInventory().getUuid(), VmNicVO.class.getSimpleName())
                            .messageAndEvent(that, evt).done();
                }
            }
        };
    }

    @Override
    public Result audit(APIMessage msg, APIEvent rsp) {
        return new Result(rsp.isSuccess() ? ((APICreateVmNicEvent)rsp).getInventory().getUuid() : "", VmNicVO.class);
    }
}
