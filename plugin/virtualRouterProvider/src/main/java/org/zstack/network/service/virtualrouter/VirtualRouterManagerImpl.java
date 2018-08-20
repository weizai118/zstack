package org.zstack.network.service.virtualrouter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import org.zstack.appliancevm.*;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.Platform;
import org.zstack.core.ansible.AnsibleFacade;
import org.zstack.core.asyncbatch.While;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.MessageSafe;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.db.*;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.thread.ChainTask;
import org.zstack.core.thread.SyncTaskChain;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.header.AbstractService;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.apimediator.GlobalApiMessageInterceptor;
import org.zstack.header.configuration.APIUpdateInstanceOfferingEvent;
import org.zstack.header.configuration.InstanceOfferingInventory;
import org.zstack.header.configuration.InstanceOfferingState;
import org.zstack.header.configuration.InstanceOfferingVO;
import org.zstack.header.core.Completion;
import org.zstack.header.core.FutureCompletion;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.core.ReturnValueCompletion;
import org.zstack.header.core.workflow.Flow;
import org.zstack.header.core.workflow.FlowChain;
import org.zstack.header.core.workflow.WhileCompletion;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.ErrorCodeList;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.HypervisorType;
import org.zstack.header.image.ImageInventory;
import org.zstack.header.image.ImageVO;
import org.zstack.header.managementnode.ManagementNodeReadyExtensionPoint;
import org.zstack.header.managementnode.PrepareDbInitialValueExtensionPoint;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.header.message.MessageReply;
import org.zstack.header.network.NetworkException;
import org.zstack.header.network.l2.APICreateL2NetworkMsg;
import org.zstack.header.network.l2.L2NetworkConstant;
import org.zstack.header.network.l2.L2NetworkCreateExtensionPoint;
import org.zstack.header.network.l2.L2NetworkInventory;
import org.zstack.header.network.l3.*;
import org.zstack.header.network.service.*;
import org.zstack.header.query.AddExpandedQueryExtensionPoint;
import org.zstack.header.query.ExpandedQueryAliasStruct;
import org.zstack.header.query.ExpandedQueryStruct;
import org.zstack.header.tag.SystemTagInventory;
import org.zstack.header.tag.SystemTagLifeCycleListener;
import org.zstack.header.vm.*;
import org.zstack.identity.AccountManager;
import org.zstack.network.l3.L3NetworkSystemTags;
import org.zstack.network.service.NetworkServiceManager;
import org.zstack.network.service.eip.EipConstant;
import org.zstack.network.service.eip.FilterVmNicsForEipInVirtualRouterExtensionPoint;
import org.zstack.network.service.lb.*;
import org.zstack.network.service.vip.*;
import org.zstack.network.service.virtualrouter.eip.VirtualRouterEipRefInventory;
import org.zstack.network.service.virtualrouter.lb.VirtualRouterLoadBalancerRefVO;
import org.zstack.network.service.virtualrouter.lb.VirtualRouterLoadBalancerRefVO_;
import org.zstack.network.service.virtualrouter.portforwarding.VirtualRouterPortForwardingRuleRefInventory;
import org.zstack.network.service.virtualrouter.vip.VirtualRouterVipInventory;
import org.zstack.network.service.virtualrouter.vip.VirtualRouterVipVO;
import org.zstack.network.service.virtualrouter.vip.VirtualRouterVipVO_;
import org.zstack.network.service.virtualrouter.vyos.VyosConstants;
import org.zstack.network.service.virtualrouter.vyos.VyosVersionManager;
import org.zstack.search.GetQuery;
import org.zstack.search.SearchQuery;
import org.zstack.tag.SystemTagCreator;
import org.zstack.tag.TagManager;
import org.zstack.utils.*;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.network.NetworkUtils;

import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.zstack.core.Platform.argerr;
import static org.zstack.core.Platform.operr;
import static org.zstack.core.progress.ProgressReportService.createSubTaskProgress;
import static org.zstack.network.service.virtualrouter.VirtualRouterConstant.VIRTUAL_ROUTER_PROVIDER_TYPE;
import static org.zstack.network.service.virtualrouter.VirtualRouterNicMetaData.GUEST_NIC_MASK;
import static org.zstack.network.service.virtualrouter.vyos.VyosConstants.VYOS_ROUTER_PROVIDER_TYPE;
import static org.zstack.utils.CollectionDSL.e;
import static org.zstack.utils.CollectionDSL.map;
import static org.zstack.utils.VipUseForList.SNAT_NETWORK_SERVICE_TYPE;

public class VirtualRouterManagerImpl extends AbstractService implements VirtualRouterManager,
        PrepareDbInitialValueExtensionPoint, L2NetworkCreateExtensionPoint,
        GlobalApiMessageInterceptor, AddExpandedQueryExtensionPoint, GetCandidateVmNicsForLoadBalancerExtensionPoint,
        FilterVmNicsForEipInVirtualRouterExtensionPoint, ApvmCascadeFilterExtensionPoint, ManagementNodeReadyExtensionPoint,
        VipCleanupExtensionPoint {
	private final static CLogger logger = Utils.getLogger(VirtualRouterManagerImpl.class);
	
	private final static List<String> supportedL2NetworkTypes = new ArrayList<String>();
	private NetworkServiceProviderInventory virtualRouterProvider;
	private Map<String, VirtualRouterHypervisorBackend> hypervisorBackends = new HashMap<String, VirtualRouterHypervisorBackend>();
    private Map<String, Integer> vrParallelismDegrees = new ConcurrentHashMap<String, Integer>();

    private List<String> virtualRouterPostCreateFlows;
    private List<String> virtualRouterPostStartFlows;
    private List<String> virtualRouterPostRebootFlows;
    private List<String> virtualRouterPostDestroyFlows;
    private List<String> virtualRouterReconnectFlows;
    private FlowChainBuilder postCreateFlowsBuilder;
    private FlowChainBuilder postStartFlowsBuilder;
    private FlowChainBuilder postRebootFlowsBuilder;
    private FlowChainBuilder postDestroyFlowsBuilder;
    private FlowChainBuilder reconnectFlowsBuilder;

    private List<VirtualRouterPostCreateFlowExtensionPoint> postCreateFlowExtensionPoints;
    private List<VirtualRouterPostStartFlowExtensionPoint> postStartFlowExtensionPoints;
    private List<VirtualRouterPostRebootFlowExtensionPoint> postRebootFlowExtensionPoints;
    private List<VirtualRouterPostReconnectFlowExtensionPoint> postReconnectFlowExtensionPoints;
    private List<VirtualRouterPostDestroyFlowExtensionPoint> postDestroyFlowExtensionPoints;
    private List<VipGetUsedPortRangeExtensionPoint> vipGetUsedPortRangeExtensionPoints;

	static {
		supportedL2NetworkTypes.add(L2NetworkConstant.L2_NO_VLAN_NETWORK_TYPE);
		supportedL2NetworkTypes.add(L2NetworkConstant.L2_VLAN_NETWORK_TYPE);
	}
	
	@Autowired
	private CloudBus bus;
	@Autowired
	private DatabaseFacade dbf;
	@Autowired
	private VirtualRouterProviderFactory providerFactory;
	@Autowired
	private PluginRegistry pluginRgty;
    @Autowired
    private AnsibleFacade asf;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private AccountManager acntMgr;
    @Autowired
    private ThreadFacade thdf;
    @Autowired
    private ApplianceVmFacade apvmf;
    @Autowired
    private TagManager tagMgr;
    @Autowired
    private NetworkServiceManager nwServiceMgr;
    @Autowired
    private VyosVersionManager vyosVersionManager;

	@Override
    @MessageSafe
	public void handleMessage(Message msg) {
        if (msg instanceof APIMessage) {
            handleApiMessage((APIMessage) msg);
        } else {
            handleLocalMessage(msg);
        }
	}

	private void handleLocalMessage(Message msg) {
        if (msg instanceof CreateVirtualRouterVmMsg) {
            handle((CreateVirtualRouterVmMsg) msg);
        } else if (msg instanceof CheckVirtualRouterVmVersionMsg) {
            handle((CheckVirtualRouterVmVersionMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void handle(final CreateVirtualRouterVmMsg msg) {
        thdf.chainSubmit(new ChainTask(msg) {
            @Override
            public String getSyncSignature() {
                return String.format("create-vr-for-l3-%s", msg.getL3Network().getUuid());
            }

            @Override
            public void run(final SyncTaskChain chain) {
                createVirtualRouter(msg, new NoErrorCompletion(msg, chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return getSyncSignature();
            }
        });
    }

    private void createVirtualRouter(final CreateVirtualRouterVmMsg msg, final NoErrorCompletion completion) {
        final L3NetworkInventory l3Network = msg.getL3Network();
        final VirtualRouterOfferingInventory offering = msg.getOffering();
        final CreateVirtualRouterVmReply reply = new CreateVirtualRouterVmReply();
        final String accountUuid = acntMgr.getOwnerAccountUuidOfResource(l3Network.getUuid());

        class newVirtualRouterJob {
            private void failAndReply(ErrorCode err) {
                reply.setError(err);
                bus.reply(msg, reply);
                completion.done();
            }

            private void openFirewall(ApplianceVmSpec aspec, String l3NetworkUuid, int port, ApplianceVmFirewallProtocol protocol) {
                ApplianceVmFirewallRuleInventory r = new ApplianceVmFirewallRuleInventory();
                r.setL3NetworkUuid(l3NetworkUuid);
                r.setStartPort(port);
                r.setEndPort(port);
                r.setProtocol(protocol.toString());
                aspec.getFirewallRules().add(r);
            }

            private void openAdditionalPorts(ApplianceVmSpec aspec, String mgmtNwUuid) {
                final List<String> tcpPorts = VirtualRouterGlobalProperty.TCP_PORTS_ON_MGMT_NIC;
                if (!tcpPorts.isEmpty()) {
                    List<Integer> ports = CollectionUtils.transformToList(tcpPorts, new Function<Integer, String>() {
                        @Override
                        public Integer call(String arg) {
                            return Integer.valueOf(arg);
                        }
                    });
                    for (int p : ports) {
                        openFirewall(aspec, mgmtNwUuid, p, ApplianceVmFirewallProtocol.tcp);
                    }
                }

                final List<String> udpPorts = VirtualRouterGlobalProperty.UDP_PORTS_ON_MGMT_NIC;
                if (!udpPorts.isEmpty()) {
                    List<Integer> ports = CollectionUtils.transformToList(udpPorts, new Function<Integer, String>() {
                        @Override
                        public Integer call(String arg) {
                            return Integer.valueOf(arg);
                        }
                    });
                    for (int p : ports) {
                        openFirewall(aspec, mgmtNwUuid, p, ApplianceVmFirewallProtocol.udp);
                    }
                }
            }

            private void checkIsIpRangeOverlap(){
                String priStartIp;
                String priEndIp;
                String pubStartIp;
                String pubEndIp;

                L3NetworkVO pubL3Network = Q.New(L3NetworkVO.class).eq(L3NetworkVO_.uuid,msg.getOffering().getPublicNetworkUuid()).find();
                List<IpRangeInventory> priIpranges = l3Network.getIpRanges();
                List<IpRangeInventory> pubIpranges = IpRangeInventory.valueOf(pubL3Network.getIpRanges());


                for(IpRangeInventory priIprange : priIpranges){
                    for(IpRangeInventory pubIprange : pubIpranges){

                        priStartIp = priIprange.getStartIp();
                        priEndIp = priIprange.getEndIp();
                        pubStartIp = pubIprange.getStartIp();
                        pubEndIp = pubIprange.getEndIp();

                        if(NetworkUtils.isIpv4RangeOverlap(priStartIp,priEndIp,pubStartIp,pubEndIp)){
                            throw new OperationFailureException(argerr("cannot create virtual Router vm while virtual router network overlaps with private network in ip "));
                        }

                    }
                }

            }
            void create() {
                List<String> neededService = l3Network.getNetworkServiceTypesFromProvider(new Callable<String>() {
                    @Override
                    public String call() {
                        SimpleQuery<NetworkServiceProviderVO> q = dbf.createQuery(NetworkServiceProviderVO.class);
                        q.select(NetworkServiceProviderVO_.uuid);
                        q.add(NetworkServiceProviderVO_.type, Op.EQ, msg.getProviderType());
                        return q.findValue();
                    }
                }.call());

                if (neededService.contains(NetworkServiceType.SNAT.toString()) && offering.getPublicNetworkUuid() == null) {
                    String err = String.format("L3Network[uuid:%s, name:%s] requires SNAT service, but default virtual router offering[uuid:%s, name:%s] doesn't have a public network", l3Network.getUuid(), l3Network.getName(), offering.getUuid(), offering.getName());
                    logger.warn(err);
                    failAndReply(errf.instantiateErrorCode(VirtualRouterErrors.NO_PUBLIC_NETWORK_IN_OFFERING, err));
                    return;
                }

                checkIsIpRangeOverlap();

                ImageVO imgvo = dbf.findByUuid(offering.getImageUuid(), ImageVO.class);

                final ApplianceVmSpec aspec = new ApplianceVmSpec();
                aspec.setSyncCreate(false);
                aspec.setTemplate(ImageInventory.valueOf(imgvo));
                aspec.setApplianceVmType(ApplianceVmType.valueOf(msg.getApplianceVmType()));
                aspec.setInstanceOffering(offering);
                aspec.setAccountUuid(accountUuid);
                aspec.setName(String.format("vrouter.l3.%s.%s", l3Network.getName(), l3Network.getUuid().substring(0, 6)));
                aspec.setInherentSystemTags(msg.getInherentSystemTags());
                aspec.setSshUsername(VirtualRouterGlobalConfig.SSH_USERNAME.value());
                aspec.setSshPort(VirtualRouterGlobalConfig.SSH_PORT.value(Integer.class));
                aspec.setAgentPort(msg.getApplianceVmAgentPort());

                L3NetworkInventory mgmtNw = L3NetworkInventory.valueOf(dbf.findByUuid(offering.getManagementNetworkUuid(), L3NetworkVO.class));
                ApplianceVmNicSpec mgmtNicSpec = new ApplianceVmNicSpec();
                mgmtNicSpec.setL3NetworkUuid(mgmtNw.getUuid());
                mgmtNicSpec.setMetaData(VirtualRouterNicMetaData.MANAGEMENT_NIC_MASK.toString());
                aspec.setManagementNic(mgmtNicSpec);

                String mgmtNwUuid = mgmtNw.getUuid();
                String pnwUuid;

                // NOTE: don't open 22 port here; 22 port is default opened on mgmt network in virtual router with restricted rules
                // open 22 here will cause a non-restricted rule to be added
                openFirewall(aspec, mgmtNwUuid, VirtualRouterGlobalProperty.AGENT_PORT, ApplianceVmFirewallProtocol.tcp);
                openAdditionalPorts(aspec, mgmtNwUuid);

                if (offering.getPublicNetworkUuid() != null && !offering.getManagementNetworkUuid().equals(offering.getPublicNetworkUuid())) {
                    L3NetworkInventory pnw = L3NetworkInventory.valueOf(dbf.findByUuid(offering.getPublicNetworkUuid(), L3NetworkVO.class));
                    ApplianceVmNicSpec pnicSpec = new ApplianceVmNicSpec();
                    pnicSpec.setL3NetworkUuid(pnw.getUuid());
                    pnicSpec.setMetaData(VirtualRouterNicMetaData.PUBLIC_NIC_MASK.toString());
                    aspec.getAdditionalNics().add(pnicSpec);
                    pnwUuid = pnicSpec.getL3NetworkUuid();
                    aspec.setDefaultRouteL3Network(pnw);
                } else {
                    // use management nic for both management and public
                    mgmtNicSpec.setMetaData(VirtualRouterNicMetaData.PUBLIC_AND_MANAGEMENT_NIC_MASK.toString());
                    pnwUuid = mgmtNwUuid;
                    aspec.setDefaultRouteL3Network(mgmtNw);
                }


                if (!l3Network.getUuid().equals(mgmtNwUuid) && !l3Network.getUuid().equals(pnwUuid)) {
                    ApplianceVmNicSpec nicSpec = new ApplianceVmNicSpec();
                    nicSpec.setL3NetworkUuid(l3Network.getUuid());
                    if ((L3NetworkSystemTags.ROUTER_INTERFACE_IP.hasTag(l3Network.getUuid()) || neededService.contains(NetworkServiceType.SNAT.toString())) && !msg.isNotGatewayForGuestL3Network()) {
                        DebugUtils.Assert(!l3Network.getIpRanges().isEmpty(), String.format("how can l3Network[uuid:%s] doesn't have ip range", l3Network.getUuid()));
                        IpRangeInventory ipr = l3Network.getIpRanges().get(0);
                        nicSpec.setL3NetworkUuid(l3Network.getUuid());
                        nicSpec.setAcquireOnNetwork(false);
                        nicSpec.setNetmask(ipr.getNetmask());
                        nicSpec.setIp(ipr.getGateway());
                        nicSpec.setGateway(ipr.getGateway());
                    }
                    if (L3NetworkSystemTags.ROUTER_INTERFACE_IP.hasTag(l3Network.getUuid())) {
                        nicSpec.setIp(L3NetworkSystemTags.ROUTER_INTERFACE_IP.getTokenByResourceUuid(l3Network.getUuid(), L3NetworkSystemTags.ROUTER_INTERFACE_IP_TOKEN));
                    }
                    aspec.getAdditionalNics().add(nicSpec);
                }

                ApplianceVmNicSpec guestNicSpec = mgmtNicSpec.getL3NetworkUuid().equals(l3Network.getUuid()) ? mgmtNicSpec : CollectionUtils.find(aspec.getAdditionalNics(), new Function<ApplianceVmNicSpec, ApplianceVmNicSpec>() {
                    @Override
                    public ApplianceVmNicSpec call(ApplianceVmNicSpec arg) {
                        return arg.getL3NetworkUuid().equals(l3Network.getUuid()) ? arg : null;
                    }
                });

                guestNicSpec.setMetaData(guestNicSpec.getMetaData() == null ? GUEST_NIC_MASK.toString()
                        : String.valueOf(Integer.valueOf(guestNicSpec.getMetaData()) | GUEST_NIC_MASK));

                if (neededService.contains(NetworkServiceType.DHCP.toString())) {
                    openFirewall(aspec, l3Network.getUuid(), 68, ApplianceVmFirewallProtocol.udp);
                    openFirewall(aspec, l3Network.getUuid(), 67, ApplianceVmFirewallProtocol.udp);
                }
                if (neededService.contains(NetworkServiceType.DNS.toString())) {
                    openFirewall(aspec, l3Network.getUuid(), 53, ApplianceVmFirewallProtocol.udp);
                }

                logger.debug(String.format("unable to find running virtual for L3Network[name:%s, uuid:%s], is about to create a new one",  l3Network.getName(), l3Network.getUuid()));
                apvmf.createApplianceVm(aspec, new ReturnValueCompletion<ApplianceVmInventory>(completion) {
                    @Override
                    public void success(ApplianceVmInventory apvm) {
                        String paraDegree = VirtualRouterSystemTags.VR_OFFERING_PARALLELISM_DEGREE.getTokenByResourceUuid(offering.getUuid(), VirtualRouterSystemTags.PARALLELISM_DEGREE_TOKEN);

                        if (paraDegree != null) {
                            SystemTagCreator creator = VirtualRouterSystemTags.VR_PARALLELISM_DEGREE.newSystemTagCreator(apvm.getUuid());
                            creator.setTagByTokens(map(e(
                                    VirtualRouterSystemTags.PARALLELISM_DEGREE_TOKEN,
                                    paraDegree
                            )));
                            creator.create();
                        }

                        reply.setInventory(VirtualRouterVmInventory.valueOf(dbf.findByUuid(apvm.getUuid(), VirtualRouterVmVO.class)));
                        bus.reply(msg, reply);
                        completion.done();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        failAndReply(errorCode);
                    }
                });
            }
        }

        new newVirtualRouterJob().create();
    }

    private void handleApiMessage(APIMessage msg) {
        if (msg instanceof APISearchVirtualRouterOffingMsg) {
            handle((APISearchVirtualRouterOffingMsg)msg);
        } else if (msg instanceof APIGetVirtualRouterOfferingMsg) {
            handle((APIGetVirtualRouterOfferingMsg) msg);
        } else if (msg instanceof APIUpdateVirtualRouterOfferingMsg) {
            handle((APIUpdateVirtualRouterOfferingMsg) msg);
        } else if (msg instanceof APIGetAttachablePublicL3ForVRouterMsg) {
            handle((APIGetAttachablePublicL3ForVRouterMsg) msg);
        } else if (msg instanceof APIGetVipUsedPortsMsg) {
            handle((APIGetVipUsedPortsMsg) msg);
        }else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private List<String> getVipUsedPortList(String vipUuid, String protocol){
        String useFor = Q.New(VipVO.class).select(VipVO_.useFor).eq(VipVO_.uuid, vipUuid).findValue();
        VipUseForList vipUseForList;
        if (useFor != null){
            vipUseForList = new VipUseForList(useFor);
        } else {
            vipUseForList = new VipUseForList();
        }

        List<RangeSet.Range> portRangeList = new ArrayList<RangeSet.Range>();
        for (VipGetUsedPortRangeExtensionPoint ext : vipGetUsedPortRangeExtensionPoints) {
            RangeSet range = ext.getVipUsePortRange(vipUuid, protocol, vipUseForList);
            portRangeList.addAll(range.getRanges());
        }

        RangeSet portRange = new RangeSet();
        portRange.setRanges(portRangeList);
        return portRange.sortAndToString();
    }

    private void handle(APIGetVipUsedPortsMsg msg) {
        String vipUuid = msg.getUuid();
        String protocl = msg.getProtocol().toUpperCase();

        APIGetVipUsedPortsReply reply = new APIGetVipUsedPortsReply();
        APIGetVipUsedPortsReply.VipPortRangeInventory inv = new APIGetVipUsedPortsReply.VipPortRangeInventory();
        inv.setUuid(vipUuid);
        inv.setProtocol(protocl);
        inv.setUsedPorts(getVipUsedPortList(vipUuid, protocl));
        reply.setInventories(Arrays.asList(inv));
        bus.reply(msg, reply);
    }

    private void handle(APIGetAttachablePublicL3ForVRouterMsg msg) {
	    APIGetAttachablePublicL3ForVRouterReply reply = new APIGetAttachablePublicL3ForVRouterReply();
	    List<L3NetworkVO> l3NetworkVOS = Q.New(L3NetworkVO.class).notEq(L3NetworkVO_.category, L3NetworkCategory.Private).list();
	    List<VmNicVO> vmNicVOS = Q.New(VmNicVO.class).eq(VmNicVO_.vmInstanceUuid, msg.getVmInstanceUuid()).list();

	    if (l3NetworkVOS == null || l3NetworkVOS.isEmpty()) {
	        reply.setInventories(new ArrayList<L3NetworkInventory>());
	        bus.reply(msg, reply);
	        return;
        }

        Set<L3NetworkVO> attachableL3NetworkVOS = new HashSet<>(l3NetworkVOS);

        for (L3NetworkVO l3NetworkVO : l3NetworkVOS) {
	        for (VmNicVO vmNicVO : vmNicVOS) {
	            if (l3NetworkVO.getIpRanges() == null || l3NetworkVO.getIpRanges().isEmpty()) {
                    attachableL3NetworkVOS.remove(l3NetworkVO);
                }
                String vmNicCidr = NetworkUtils.getCidrFromIpMask(vmNicVO.getIp(), vmNicVO.getNetmask());
                if (NetworkUtils.isCidrOverlap(l3NetworkVO.getIpRanges().stream().findFirst().get().getNetworkCidr(), vmNicCidr)) {
                    attachableL3NetworkVOS.remove(l3NetworkVO);
                }
                attachableL3NetworkVOS.removeAll(attachableL3NetworkVOS.stream()
                        .filter(vo -> vo.getUuid().equals(vmNicVO.getL3NetworkUuid()))
                        .collect(Collectors.toSet()));
            }
        }
        reply.setInventories(L3NetworkInventory.valueOf(attachableL3NetworkVOS));
        bus.reply(msg, reply);
    }

    private void handle(APIUpdateVirtualRouterOfferingMsg msg) {
        VirtualRouterOfferingVO ovo = dbf.findByUuid(msg.getUuid(), VirtualRouterOfferingVO.class);
        boolean updated = false;
        if (msg.getName() != null) {
            ovo.setName(msg.getName());
            updated = true;
        }
        if (msg.getDescription() != null) {
            ovo.setDescription(msg.getDescription());
            updated = true;
        }
        if (msg.getImageUuid() != null) {
            ovo.setImageUuid(msg.getImageUuid());
            updated = true;
        }

        if (updated) {
            ovo = dbf.updateAndRefresh(ovo);
        }

        if (msg.getIsDefault() != null) {
            DefaultVirtualRouterOfferingSelector selector = new DefaultVirtualRouterOfferingSelector();
            selector.setZoneUuid(ovo.getZoneUuid());
            selector.setPreferToBeDefault(msg.getIsDefault());
            selector.setOfferingUuid(ovo.getUuid());
            selector.selectDefaultOffering();
        }

        APIUpdateInstanceOfferingEvent evt = new APIUpdateInstanceOfferingEvent(msg.getId());
        evt.setInventory(VirtualRouterOfferingInventory.valueOf(dbf.reload(ovo)));
        bus.publish(evt);
    }

    private void handle(APIGetVirtualRouterOfferingMsg msg) {
        GetQuery q = new GetQuery();
        String res = q.getAsString(msg, VirtualRouterOfferingInventory.class);
        APIGetVirtualRouterOfferingReply reply = new APIGetVirtualRouterOfferingReply();
        reply.setInventory(res);
        bus.reply(msg, reply);
    }

    private void handle(APISearchVirtualRouterOffingMsg msg) {
        SearchQuery<VirtualRouterOfferingInventory> q = SearchQuery.create(msg, VirtualRouterOfferingInventory.class);
        APISearchVirtualRouterOffingReply reply = new APISearchVirtualRouterOffingReply();
        String res = q.listAsString();
        reply.setContent(res);
        bus.reply(msg, reply);
    }

    @Override
	public String getId() {
		return bus.makeLocalServiceId(VirtualRouterConstant.SERVICE_ID);
	}

	@Override
	public boolean start() {
		populateExtensions();
        deployAnsible();
		buildWorkFlowBuilder();

        VirtualRouterSystemTags.VR_PARALLELISM_DEGREE.installLifeCycleListener(new SystemTagLifeCycleListener() {
            @Override
            public void tagCreated(SystemTagInventory tag) {
                if (VirtualRouterSystemTags.VR_PARALLELISM_DEGREE.isMatch(tag.getTag())) {
                    String value = VirtualRouterSystemTags.VR_PARALLELISM_DEGREE.getTokenByTag(tag.getTag(), VirtualRouterSystemTags.PARALLELISM_DEGREE_TOKEN);
                    vrParallelismDegrees.put(tag.getResourceUuid(), Integer.valueOf(value));
                }
            }

            @Override
            public void tagDeleted(SystemTagInventory tag) {
                if (VirtualRouterSystemTags.VR_PARALLELISM_DEGREE.isMatch(tag.getTag())) {
                    vrParallelismDegrees.remove(tag.getResourceUuid());
                }
            }

            @Override
            public void tagUpdated(SystemTagInventory old, SystemTagInventory newTag) {
            }
        });
		return true;
	}

	@Override
	public boolean stop() {
		return true;
	}
	
	public void prepareDbInitialValue() {
		SimpleQuery<NetworkServiceProviderVO> query = dbf.createQuery(NetworkServiceProviderVO.class);
		query.add(NetworkServiceProviderVO_.type, Op.EQ, VIRTUAL_ROUTER_PROVIDER_TYPE);
		NetworkServiceProviderVO rpvo = query.find();
		if (rpvo != null) {
			virtualRouterProvider = NetworkServiceProviderInventory.valueOf(rpvo);
			return;
		}
		
		NetworkServiceProviderVO vo = new NetworkServiceProviderVO();
        vo.setUuid(Platform.getUuid());
		vo.setName(VIRTUAL_ROUTER_PROVIDER_TYPE);
		vo.setDescription("zstack virtual router network service provider");
		vo.getNetworkServiceTypes().add(NetworkServiceType.DHCP.toString());
		vo.getNetworkServiceTypes().add(NetworkServiceType.DNS.toString());
		vo.getNetworkServiceTypes().add(NetworkServiceType.SNAT.toString());
		vo.getNetworkServiceTypes().add(NetworkServiceType.PortForwarding.toString());
        vo.getNetworkServiceTypes().add(EipConstant.EIP_NETWORK_SERVICE_TYPE);
        vo.getNetworkServiceTypes().add(LoadBalancerConstants.LB_NETWORK_SERVICE_TYPE_STRING);
		vo.setType(VIRTUAL_ROUTER_PROVIDER_TYPE);
		vo = dbf.persistAndRefresh(vo);
		virtualRouterProvider = NetworkServiceProviderInventory.valueOf(vo);
	}
	
	private void populateExtensions() {
		for (VirtualRouterHypervisorBackend extp : pluginRgty.getExtensionList(VirtualRouterHypervisorBackend.class)) {
			VirtualRouterHypervisorBackend old = hypervisorBackends.get(extp.getVirtualRouterSupportedHypervisorType().toString());
            if (old != null) {
                throw new CloudRuntimeException(String.format("duplicate VirtualRouterHypervisorBackend[%s, %s] for type[%s]",
                        extp.getClass().getName(), old.getClass().getName(), old.getVirtualRouterSupportedHypervisorType()));
            }
			hypervisorBackends.put(extp.getVirtualRouterSupportedHypervisorType().toString(), extp);
		}

		postCreateFlowExtensionPoints = pluginRgty.getExtensionList(VirtualRouterPostCreateFlowExtensionPoint.class);
        postStartFlowExtensionPoints = pluginRgty.getExtensionList(VirtualRouterPostStartFlowExtensionPoint.class);
        postRebootFlowExtensionPoints = pluginRgty.getExtensionList(VirtualRouterPostRebootFlowExtensionPoint.class);
        postReconnectFlowExtensionPoints = pluginRgty.getExtensionList(VirtualRouterPostReconnectFlowExtensionPoint.class);
        postDestroyFlowExtensionPoints = pluginRgty.getExtensionList(VirtualRouterPostDestroyFlowExtensionPoint.class);
        vipGetUsedPortRangeExtensionPoints = pluginRgty.getExtensionList(VipGetUsedPortRangeExtensionPoint.class);
	}
	
	private NetworkServiceProviderVO getRouterVO() {
		SimpleQuery<NetworkServiceProviderVO> query = dbf.createQuery(NetworkServiceProviderVO.class);
		query.add(NetworkServiceProviderVO_.type, Op.EQ, VIRTUAL_ROUTER_PROVIDER_TYPE);
		return query.find();
	}

	@Override
	public void beforeCreateL2Network(APICreateL2NetworkMsg msg) throws NetworkException {
	}

	@Override
	public void afterCreateL2Network(L2NetworkInventory l2Network) {
		if (!supportedL2NetworkTypes.contains(l2Network.getType())) {
			return;
		}
		
		NetworkServiceProviderVO vo = getRouterVO();
		NetworkServiceProvider router = providerFactory.getNetworkServiceProvider(vo);
		try {
			router.attachToL2Network(l2Network, null);
		} catch (NetworkException e) {
			String err = String.format("unable to attach network service provider[uuid:%s, name:%s, type:%s] to l2network[uuid:%s, name:%s, type:%s], %s",
					vo.getUuid(), vo.getName(), vo.getType(), l2Network.getUuid(), l2Network.getName(), l2Network.getType(), e.getMessage());
			logger.warn(err, e);
			return;
		}
		
		NetworkServiceProviderL2NetworkRefVO ref = new NetworkServiceProviderL2NetworkRefVO();
		ref.setNetworkServiceProviderUuid(vo.getUuid());
		ref.setL2NetworkUuid(l2Network.getUuid());
		dbf.persist(ref);
		String info = String.format("successfully attach network service provider[uuid:%s, name:%s, type:%s] to l2network[uuid:%s, name:%s, type:%s]",
				vo.getUuid(), vo.getName(), vo.getType(), l2Network.getUuid(), l2Network.getName(), l2Network.getType());
		logger.debug(info);
	}

	@Override
	public NetworkServiceProviderInventory getVirtualRouterProvider() {
		return virtualRouterProvider;
	}

    private void deployAnsible() {
        if (CoreGlobalProperty.UNIT_TEST_ON) {
            return;
        }

        asf.deployModule(VirtualRouterConstant.ANSIBLE_MODULE_PATH, VirtualRouterConstant.ANSIBLE_PLAYBOOK_NAME);
    }
	
	@Override
	public VirtualRouterHypervisorBackend getHypervisorBackend(HypervisorType hypervisorType) {
		VirtualRouterHypervisorBackend b = hypervisorBackends.get(hypervisorType.toString());
		if (b == null) {
			throw new CloudRuntimeException(String.format("unable to find VirtualRouterHypervisorBackend for hypervisorType[%s]", hypervisorType));
		}
		return b;
	}

	@Override
	public String buildUrl(String mgmtNicIp, String subPath) {
        UriComponentsBuilder ub = UriComponentsBuilder.newInstance();
        ub.scheme(VirtualRouterGlobalProperty.AGENT_URL_SCHEME);

        if (CoreGlobalProperty.UNIT_TEST_ON) {
            ub.host("localhost");
        } else {
            ub.host(mgmtNicIp);
        }

        ub.port(VirtualRouterGlobalProperty.AGENT_PORT);
        if (!"".equals(VirtualRouterGlobalProperty.AGENT_URL_ROOT_PATH)) {
            ub.path(VirtualRouterGlobalProperty.AGENT_URL_ROOT_PATH);
        }
        ub.path(subPath);

        return ub.build().toUriString();
    }

	private void buildWorkFlowBuilder() {
        postCreateFlowsBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(virtualRouterPostCreateFlows).construct();
        postStartFlowsBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(virtualRouterPostStartFlows).construct();
        postRebootFlowsBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(virtualRouterPostRebootFlows).construct();
        postDestroyFlowsBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(virtualRouterPostDestroyFlows).construct();
        reconnectFlowsBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(virtualRouterReconnectFlows).construct();
	}

    @Override
    public List<String> selectL3NetworksNeedingSpecificNetworkService(List<String> candidate, NetworkServiceType nsType) {
        if (candidate == null || candidate.isEmpty()) {
            return new ArrayList<>(0);
        }

        // need to specify provider type due to that the provider might be Flat
        return SQL.New("select ref.l3NetworkUuid from NetworkServiceL3NetworkRefVO ref, NetworkServiceProviderVO nspv" +
                " where ref.l3NetworkUuid in (:candidate) and ref.networkServiceType = :stype" +
                " and nspv.uuid = ref.networkServiceProviderUuid and nspv.type in (:ntype)")
                .param("candidate", candidate)
                .param("stype", nsType.toString())
                .param("ntype", asList(VIRTUAL_ROUTER_PROVIDER_TYPE, VYOS_ROUTER_PROVIDER_TYPE))
                .list();
    }

    @Override
    public boolean isL3NetworkNeedingNetworkServiceByVirtualRouter(String l3Uuid, String nsType) {
        if (l3Uuid == null) {
            return false;
        }
        SimpleQuery<NetworkServiceL3NetworkRefVO> q = dbf.createQuery(NetworkServiceL3NetworkRefVO.class);
        q.add(NetworkServiceL3NetworkRefVO_.l3NetworkUuid, Op.EQ, l3Uuid);
        q.add(NetworkServiceL3NetworkRefVO_.networkServiceType, Op.EQ, nsType);
        // no need to specify provider type, L3 networks identified by candidates are served by virtual router or vyos
        return q.isExists();
    }

    @Override
    public boolean isL3NetworksNeedingNetworkServiceByVirtualRouter(List<String> l3Uuids, String nsType) {
        if (l3Uuids == null || l3Uuids.isEmpty()) {
            return false;
        }
        SimpleQuery<NetworkServiceL3NetworkRefVO> q = dbf.createQuery(NetworkServiceL3NetworkRefVO.class);
        q.add(NetworkServiceL3NetworkRefVO_.l3NetworkUuid, Op.IN, l3Uuids);
        q.add(NetworkServiceL3NetworkRefVO_.networkServiceType, Op.EQ, nsType);
        // no need to specify provider type, L3 networks identified by candidates are served by virtual router or vyos
        return q.isExists();
    }

    private void acquireVirtualRouterVmInternal(VirtualRouterStruct struct,  final ReturnValueCompletion<VirtualRouterVmInventory> completion) {
        final L3NetworkInventory l3Nw = struct.getL3Network();
        final VirtualRouterOfferingValidator validator = struct.getOfferingValidator();
        final VirtualRouterVmSelector selector = struct.getVirtualRouterVmSelector();

        VirtualRouterVmInventory vr = new Callable<VirtualRouterVmInventory>() {
            @Transactional(readOnly = true)
            private VirtualRouterVmVO findVR() {
                String sql = "select vr from VirtualRouterVmVO vr, VmNicVO nic where vr.uuid = nic.vmInstanceUuid and nic.l3NetworkUuid = :l3Uuid and nic.metaData in (:guestMeta)";
                TypedQuery<VirtualRouterVmVO> q = dbf.getEntityManager().createQuery(sql, VirtualRouterVmVO.class);
                q.setParameter("l3Uuid", l3Nw.getUuid());
                q.setParameter("guestMeta", VirtualRouterNicMetaData.GUEST_NIC_MASK_STRING_LIST);
                List<VirtualRouterVmVO> vrs = q.getResultList();

                if (vrs.isEmpty()) {
                    return null;
                }

                if (selector == null) {
                    return findTheEarliestOne(vrs);
                } else {
                    return selector.select(vrs);
                }
            }

            private VirtualRouterVmVO findTheEarliestOne(List<VirtualRouterVmVO> vrs) {
                VirtualRouterVmVO vr = null;
                for (VirtualRouterVmVO v : vrs) {
                    if (vr == null) {
                        vr = v;
                        continue;
                    }

                    vr = vr.getCreateDate().before(v.getCreateDate()) ? vr : v;
                }
                return vr;
            }

            @Override
            public VirtualRouterVmInventory call() {
                VirtualRouterVmVO vr = findVR();
                if (vr != null && !VmInstanceState.Running.equals(vr.getState())) {
                    throw new OperationFailureException(operr("virtual router[uuid:%s] for l3 network[uuid:%s] is not in Running state, current state is %s. We don't have HA feature now(it's coming soon), please restart it from UI and then try starting this vm again",
                                    vr.getUuid(), l3Nw.getUuid(), vr.getState()));
                }

                return vr == null ? null : new VirtualRouterVmInventory(vr);
            }
        }.call();

        if (vr != null) {
            completion.success(vr);
            return;
        }

        List<VirtualRouterOfferingInventory> offerings = findOfferingByGuestL3Network(l3Nw);
        if (offerings == null) {
            String err = String.format("unable to find a virtual router offering for l3Network[uuid:%s] in zone[uuid:%s], please at least create a default virtual router offering in that zone",
                    l3Nw.getUuid(), l3Nw.getZoneUuid());
            logger.warn(err);
            completion.fail(errf.instantiateErrorCode(VirtualRouterErrors.NO_DEFAULT_OFFERING, err));
            return;
        }

        if (struct.getVirtualRouterOfferingSelector() == null) {
            struct.setVirtualRouterOfferingSelector(new VirtualRouterOfferingSelector() {
                @Override
                public VirtualRouterOfferingInventory selectVirtualRouterOffering(L3NetworkInventory l3, List<VirtualRouterOfferingInventory> candidates) {
                    Optional<VirtualRouterOfferingInventory> opt = candidates.stream().filter(VirtualRouterOfferingInventory::isDefault).findAny();
                    return !opt.isPresent() ? candidates.get(0) : opt.get();
                }
            });
        }

        VirtualRouterOfferingInventory offering = struct.getVirtualRouterOfferingSelector().selectVirtualRouterOffering(l3Nw, offerings);

        if (validator != null) {
            validator.validate(offering);
        }

        CreateVirtualRouterVmMsg msg = new CreateVirtualRouterVmMsg();
        msg.setNotGatewayForGuestL3Network(struct.isNotGatewayForGuestL3Network());
        msg.setL3Network(l3Nw);
        msg.setOffering(offering);
        msg.setInherentSystemTags(struct.getInherentSystemTags());
        msg.setProviderType(struct.getProviderType());
        msg.setApplianceVmType(struct.getApplianceVmType());
        msg.setApplianceVmAgentPort(struct.getApplianceVmAgentPort());

        createSubTaskProgress("create a virtual router vm");
        bus.makeTargetServiceIdByResourceUuid(msg, VirtualRouterConstant.SERVICE_ID, l3Nw.getUuid());
        bus.send(msg, new CloudBusCallBack(completion) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    completion.fail(reply.getError());
                } else {
                    completion.success(((CreateVirtualRouterVmReply) reply).getInventory());
                }
            }
        });
    }

    @Override
    public void acquireVirtualRouterVm(VirtualRouterStruct struct, final ReturnValueCompletion<VirtualRouterVmInventory> completion) {
        for (BeforeAcquireVirtualRouterVmExtensionPoint extp : pluginRgty.getExtensionList(
                BeforeAcquireVirtualRouterVmExtensionPoint.class)) {
            extp.beforeAcquireVirtualRouterVmExtensionPoint(struct);
        }

        //TODO: find a way to remove the GLock
        String syncName = String.format("glock-vr-l3-%s", struct.getL3Network().getUuid());
        thdf.chainSubmit(new ChainTask(completion) {
            @Override
            public String getSyncSignature() {
                return syncName;
            }

            @Override
            public void run(final SyncTaskChain chain) {
                final GLock lock = new GLock(syncName, TimeUnit.HOURS.toSeconds(1));
                lock.setSeparateThreadEnabled(false);
                lock.lock();
                acquireVirtualRouterVmInternal(struct, new ReturnValueCompletion<VirtualRouterVmInventory>(chain, completion) {
                    @Override
                    public void success(VirtualRouterVmInventory returnValue) {
                        lock.unlock();
                        completion.success(returnValue);
                        chain.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        lock.unlock();
                        completion.fail(errorCode);
                        chain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return syncName;
            }
        });
    }

    @Override
    @Transactional(readOnly = true)
    public VirtualRouterVmInventory getVirtualRouterVm(L3NetworkInventory l3Nw) {
        String sql = "select vm from VirtualRouterVmVO vm, VmNicVO nic where vm.uuid = nic.vmInstanceUuid and nic.l3NetworkUuid = :l3Uuid and nic.metaData in (:guestMeta)";
        TypedQuery<VirtualRouterVmVO> q = dbf.getEntityManager().createQuery(sql, VirtualRouterVmVO.class);
        q.setParameter("l3Uuid", l3Nw.getUuid());
        q.setParameter("guestMeta", VirtualRouterNicMetaData.GUEST_NIC_MASK_STRING_LIST);
        List<VirtualRouterVmVO> vos = q.getResultList();
        if (vos.isEmpty()) {
            return null;
        }
        return VirtualRouterVmInventory.valueOf(vos.get(0));
    }

    @Override
    public boolean isVirtualRouterRunningForL3Network(String l3Uuid) {
        return countVirtualRouterRunningForL3Network(l3Uuid) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public long countVirtualRouterRunningForL3Network(String l3Uuid) {
        String sql = "select count(vm) from ApplianceVmVO vm, VmNicVO nic where vm.uuid = nic.vmInstanceUuid and vm.state = :vmState and nic.l3NetworkUuid = :l3Uuid and nic.metaData in (:guestMeta)";
        TypedQuery<Long> q = dbf.getEntityManager().createQuery(sql, Long.class);
        q.setParameter("l3Uuid", l3Uuid);
        q.setParameter("vmState", VmInstanceState.Running);
        q.setParameter("guestMeta", VirtualRouterNicMetaData.GUEST_NIC_MASK_STRING_LIST);
        return q.getSingleResult();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isVirtualRouterForL3Network(String l3Uuid) {
        String sql = "select vm from ApplianceVmVO vm, VmNicVO nic where vm.uuid = nic.vmInstanceUuid and nic.l3NetworkUuid = :l3Uuid and nic.metaData in (:guestMeta)";
        TypedQuery<Long> q = dbf.getEntityManager().createQuery(sql, Long.class);
        q.setParameter("l3Uuid", l3Uuid);
        q.setParameter("guestMeta", VirtualRouterNicMetaData.GUEST_NIC_MASK_STRING_LIST);
        Long count = q.getSingleResult();
        return count > 0;
    }

    @Transactional(readOnly = true)
    private List<VirtualRouterOfferingInventory> findOfferingByGuestL3Network(L3NetworkInventory guestL3) {
        String sql = "select offering from VirtualRouterOfferingVO offering, SystemTagVO stag where " +
                "offering.uuid = stag.resourceUuid and stag.resourceType = :type and offering.zoneUuid = :zoneUuid and stag.tag = :tag and offering.state = :state";
        TypedQuery<VirtualRouterOfferingVO> q = dbf.getEntityManager().createQuery(sql, VirtualRouterOfferingVO.class);
        q.setParameter("type", InstanceOfferingVO.class.getSimpleName());
        q.setParameter("zoneUuid", guestL3.getZoneUuid());
        q.setParameter("tag", VirtualRouterSystemTags.VR_OFFERING_GUEST_NETWORK.instantiateTag(map(e(VirtualRouterSystemTags.VR_OFFERING_GUEST_NETWORK_TOKEN, guestL3.getUuid()))));
        q.setParameter("state", InstanceOfferingState.Enabled);
        List<VirtualRouterOfferingVO> vos = q.getResultList();
        if (!vos.isEmpty()) {
            return VirtualRouterOfferingInventory.valueOf1(vos);
        }

        sql ="select offering from VirtualRouterOfferingVO offering where offering.zoneUuid = :zoneUuid and offering.state = :state";
        q = dbf.getEntityManager().createQuery(sql, VirtualRouterOfferingVO.class);
        q.setParameter("zoneUuid", guestL3.getZoneUuid());
        q.setParameter("state", InstanceOfferingState.Enabled);
        vos = q.getResultList();
        return vos.isEmpty() ? null : VirtualRouterOfferingInventory.valueOf1(vos);
    }

    @Override
    public List<Flow> getPostCreateFlows() {
        List<Flow> flows = new ArrayList<>();
        flows.addAll(postCreateFlowsBuilder.getFlows());
        flows.addAll(postCreateFlowExtensionPoints.stream().map(VirtualRouterPostCreateFlowExtensionPoint::virtualRouterPostCreateFlow).collect(Collectors.toList()));
        return flows;
    }

    @Override
    public List<Flow> getPostStartFlows() {
        List<Flow> flows = new ArrayList<>();
        flows.addAll(postStartFlowsBuilder.getFlows());
        flows.addAll(postStartFlowExtensionPoints.stream().map(VirtualRouterPostStartFlowExtensionPoint::virtualRouterPostStartFlow).collect(Collectors.toList()));
        return flows;
    }

    @Override
    public List<Flow> getPostRebootFlows() {
        List<Flow> flows = new ArrayList<>();
        flows.addAll(postRebootFlowsBuilder.getFlows());
        flows.addAll(postRebootFlowExtensionPoints.stream().map(VirtualRouterPostRebootFlowExtensionPoint::virtualRouterPostRebootFlow).collect(Collectors.toList()));
        return flows;
    }

    @Override
    public List<Flow> getPostStopFlows() {
        return null;
    }

    @Override
    public List<Flow> getPostMigrateFlows() {
        return null;
    }

    @Override
    public List<Flow> getPostDestroyFlows() {
        List<Flow> flows = new ArrayList<>();
        flows.addAll(postDestroyFlowsBuilder.getFlows());
        flows.addAll(postDestroyFlowExtensionPoints.stream().map(VirtualRouterPostDestroyFlowExtensionPoint::virtualRouterPostDestroyFlow).collect(Collectors.toList()));
        return flows;
    }

    @Override
    public FlowChain getReconnectFlowChain() {
        FlowChain chain = reconnectFlowsBuilder.build();
        for (VirtualRouterPostReconnectFlowExtensionPoint ext : postReconnectFlowExtensionPoints) {
            chain.then(ext.virtualRouterPostReconnectFlow());
        }
        return chain;
    }

    @Override
    public int getParallelismDegree(String vrUuid) {
        Integer degree = vrParallelismDegrees.get(vrUuid);
        return degree == null ? VirtualRouterGlobalConfig.COMMANDS_PARALELLISM_DEGREE.value(Integer.class) : degree;
    }

    public void setVirtualRouterPostStartFlows(List<String> virtualRouterPostStartFlows) {
        this.virtualRouterPostStartFlows = virtualRouterPostStartFlows;
    }

    public void setVirtualRouterPostRebootFlows(List<String> virtualRouterPostRebootFlows) {
        this.virtualRouterPostRebootFlows = virtualRouterPostRebootFlows;
    }


    public void setVirtualRouterPostDestroyFlows(List<String> virtualRouterPostDestroyFlows) {
        this.virtualRouterPostDestroyFlows = virtualRouterPostDestroyFlows;
    }

    public void setVirtualRouterPostCreateFlows(List<String> virtualRouterPostCreateFlows) {
        this.virtualRouterPostCreateFlows = virtualRouterPostCreateFlows;
    }

    public void setVirtualRouterReconnectFlows(List<String> virtualRouterReconnectFlows) {
        this.virtualRouterReconnectFlows = virtualRouterReconnectFlows;
    }

    @Override
    public List<Class> getMessageClassToIntercept() {
        List<Class> classes = new ArrayList<Class>();
        classes.add(APIAttachNetworkServiceToL3NetworkMsg.class);
        return classes;
    }

    @Override
    public InterceptorPosition getPosition() {
        return InterceptorPosition.END;
    }

    @Override
    public APIMessage intercept(APIMessage msg) throws ApiMessageInterceptionException {
        if (msg instanceof APIAttachNetworkServiceToL3NetworkMsg) {
            validate((APIAttachNetworkServiceToL3NetworkMsg) msg);
        }
        return msg;
    }

    private void validate(APIAttachNetworkServiceToL3NetworkMsg msg) {
        List<String> services = msg.getNetworkServices().get(virtualRouterProvider.getUuid());
        if (services == null) {
            return;
        }

        boolean snat = false;
        boolean portForwarding = false;
        boolean eip = false;

        SimpleQuery<NetworkServiceL3NetworkRefVO> q = dbf.createQuery(NetworkServiceL3NetworkRefVO.class);
        q.add(NetworkServiceL3NetworkRefVO_.l3NetworkUuid, Op.EQ, msg.getL3NetworkUuid());
        List<NetworkServiceL3NetworkRefVO> refs = q.list();
        for (NetworkServiceL3NetworkRefVO ref : refs) {
            if (ref.getNetworkServiceType().equals(NetworkServiceType.SNAT.toString())) {
                snat = true;
            }
        }

        for (String s : services) {
            if (NetworkServiceType.PortForwarding.toString().equals(s)) {
                portForwarding = true;
            }
            if (NetworkServiceType.SNAT.toString().equals(s)) {
                snat = true;
            }
            if (EipConstant.EIP_NETWORK_SERVICE_TYPE.equals(s)) {
                eip = true;
            }
        }

        if (!snat && eip) {
            throw new ApiMessageInterceptionException(argerr("failed tot attach virtual router network services to l3Network[uuid:%s]. When eip is selected, snat must be selected too", msg.getL3NetworkUuid()));
        }

        if (!snat && portForwarding) {
            throw new ApiMessageInterceptionException(argerr("failed tot attach virtual router network services to l3Network[uuid:%s]. When port forwarding is selected, snat must be selected too", msg.getL3NetworkUuid()));
        }
    }

    @Override
    public List<ExpandedQueryStruct> getExpandedQueryStructs() {
        List<ExpandedQueryStruct> structs = new ArrayList<ExpandedQueryStruct>();

        ExpandedQueryStruct struct = new ExpandedQueryStruct();
        struct.setExpandedField("virtualRouterEipRef");
        struct.setExpandedInventoryKey("virtualRouterVmUuid");
        struct.setHidden(true);
        struct.setForeignKey("uuid");
        struct.setInventoryClass(VirtualRouterEipRefInventory.class);
        struct.setInventoryClassToExpand(ApplianceVmInventory.class);
        structs.add(struct);

        struct = new ExpandedQueryStruct();
        struct.setExpandedField("virtualRouterVipRef");
        struct.setExpandedInventoryKey("virtualRouterVmUuid");
        struct.setHidden(true);
        struct.setForeignKey("uuid");
        struct.setInventoryClass(VirtualRouterVipInventory.class);
        struct.setInventoryClassToExpand(ApplianceVmInventory.class);
        structs.add(struct);

        struct = new ExpandedQueryStruct();
        struct.setExpandedField("virtualRouterPortforwardingRef");
        struct.setExpandedInventoryKey("virtualRouterVmUuid");
        struct.setHidden(true);
        struct.setForeignKey("uuid");
        struct.setInventoryClass(VirtualRouterPortForwardingRuleRefInventory.class);
        struct.setInventoryClassToExpand(ApplianceVmInventory.class);
        structs.add(struct);

        struct = new ExpandedQueryStruct();
        struct.setExpandedField("virtualRouterOffering");
        struct.setExpandedInventoryKey("uuid");
        struct.setForeignKey("instanceOfferingUuid");
        struct.setInventoryClass(VirtualRouterOfferingInventory.class);
        struct.setInventoryClassToExpand(ApplianceVmInventory.class);
        struct.setSuppressedInventoryClass(InstanceOfferingInventory.class);
        structs.add(struct);

        return structs;
    }

    @Override
    public List<ExpandedQueryAliasStruct> getExpandedQueryAliasesStructs() {
        List<ExpandedQueryAliasStruct> aliases = new ArrayList<ExpandedQueryAliasStruct>();

        ExpandedQueryAliasStruct as = new ExpandedQueryAliasStruct();
        as.setInventoryClass(ApplianceVmInventory.class);
        as.setAlias("eip");
        as.setExpandedField("virtualRouterEipRef.eip");
        aliases.add(as);

        as = new ExpandedQueryAliasStruct();
        as.setInventoryClass(ApplianceVmInventory.class);
        as.setAlias("vip");
        as.setExpandedField("virtualRouterVipRef.vip");
        aliases.add(as);

        as = new ExpandedQueryAliasStruct();
        as.setInventoryClass(ApplianceVmInventory.class);
        as.setAlias("portForwarding");
        as.setExpandedField("virtualRouterPortforwardingRef.portForwarding");
        aliases.add(as);
        return aliases;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VmNicInventory> filterVmNicsForEipInVirtualRouter(VipInventory vip, List<VmNicInventory> candidates) {
        if (candidates.isEmpty()){
            return candidates;
        }

        // Note(WeiW) Check vip has attached virtual router network
        Boolean vipForVirtualRouter = null;
        if (vip.getPeerL3NetworkUuids() != null && !vip.getPeerL3NetworkUuids().isEmpty()) {
            // Note(WeiW): The peer l3 must be all of vrouter l3 or flat l3
            NetworkServiceProviderType providerType = nwServiceMgr.
                    getTypeOfNetworkServiceProviderForService(vip.getPeerL3NetworkUuids().get(0), EipConstant.EIP_TYPE);
            // Todo(WeiW): Need to refactor to avoid hard code
            if (providerType.toString().equals(VYOS_ROUTER_PROVIDER_TYPE) ||
                    providerType.toString().equals(VIRTUAL_ROUTER_PROVIDER_TYPE)) {
                vipForVirtualRouter = true;
            } else {
                vipForVirtualRouter = false;
            }
        }

        // 1.get the vm nics which are managed by vrouter or virtual router.
        // it also means to ignore vm in flat.
        List<String> privateL3Uuids = candidates.stream().map(VmNicInventory::getL3NetworkUuid).distinct()
                .collect(Collectors.toList());
        List<String> innerl3Uuids = SQL.New("select ref.l3NetworkUuid" +
                " from NetworkServiceL3NetworkRefVO ref, NetworkServiceProviderVO pro" +
                " where pro.type in (:providerTypes)" +
                " and ref.networkServiceProviderUuid = pro.uuid" +
                " and ref.l3NetworkUuid in (:l3Uuids)", String.class)
                .param("l3Uuids", privateL3Uuids)
                .param("providerTypes", Arrays.asList(
                        VyosConstants.PROVIDER_TYPE.toString(),
                        VirtualRouterConstant.PROVIDER_TYPE.toString()))
                .list();

        List<VmNicInventory> vmNicInVirtualRouter = candidates.stream().filter(nic ->
                innerl3Uuids.contains(nic.getL3NetworkUuid()))
                .collect(Collectors.toList());

        if (vipForVirtualRouter != null && vipForVirtualRouter == true) {
            String vrUuid = Q.New(VirtualRouterVipVO.class).select(VirtualRouterVipVO_.virtualRouterVmUuid).eq(VirtualRouterVipVO_.uuid, vip.getUuid()).findValue();
            if (vrUuid == null) {
                vrUuid = getVipPeerL3NetworkAttachedVirtualRouter(vip);
            }
            if (vrUuid != null) {
                List<String> vrAttachedGuestL3 = Q.New(VmNicVO.class).select(VmNicVO_.l3NetworkUuid).eq(VmNicVO_.vmInstanceUuid, vrUuid).eq(VmNicVO_.metaData, GUEST_NIC_MASK).listValues();
                logger.debug(String.format("there is virtual router[uuid:%s] associate with vip[uuid:%s], will return candidates from vr guest l3 networks[%s]",
                        vrUuid, vip.getUuid(), vrAttachedGuestL3));
                Set<VmNicInventory> r = candidates.stream()
                        .filter(nic -> vrAttachedGuestL3.contains(nic.getL3NetworkUuid()))
                        .collect(Collectors.toSet());
                return new ArrayList<>(r);
            }

            logger.debug(String.format("there are no virtual router associate with vip[uuid:%s], and peer l3 exists, will return candidates from peer l3 networks[%s]",
                    vip.getUuid(), vip.getPeerL3NetworkUuids()));
            Set<VmNicInventory> r = candidates.stream()
                    .filter(nic -> vip.getPeerL3NetworkUuids().contains(nic.getL3NetworkUuid()))
                    .collect(Collectors.toSet());

            return new ArrayList<>(r);
        } else if (vipForVirtualRouter != null && vipForVirtualRouter == false) {
            logger.debug(String.format("remove all vmnics in virtual router network since vip[uuid:%s] has used in network which is not %s or %s",
                    vip.getUuid(), VYOS_ROUTER_PROVIDER_TYPE, VIRTUAL_ROUTER_PROVIDER_TYPE));
            candidates.removeAll(vmNicInVirtualRouter);
            return candidates;
        }

        // 2. keep vmnics which associated vrouter attached public network of vip
        List<String> peerL3Uuids = SQL.New("select l3.uuid" +
                " from VmNicVO nic, L3NetworkVO l3"  +
                " where nic.vmInstanceUuid in " +
                " (" +
                " select vm.uuid" +
                " from VmNicVO nic, ApplianceVmVO vm" +
                " where nic.l3NetworkUuid = :l3NetworkUuid" +
                " and nic.vmInstanceUuid = vm.uuid" +
                " )"+
                " and l3.uuid = nic.l3NetworkUuid" +
                " and l3.system = :isSystem")
                .param("l3NetworkUuid", vip.getL3NetworkUuid())
                .param("isSystem", false)
                .list();

        Set<VmNicInventory> r = candidates.stream()
            .filter(nic -> peerL3Uuids.contains(nic.getL3NetworkUuid()))
            .collect(Collectors.toSet());
        candidates.removeAll(vmNicInVirtualRouter);
        candidates.addAll(r);

        return candidates;
    }

    private String getVipPeerL3NetworkAttachedVirtualRouter(VipInventory vip) {
	    for (String l3Uuid : vip.getPeerL3NetworkUuids()) {
	        String vrUuid = Q.New(VmNicVO.class).select(VmNicVO_.vmInstanceUuid).eq(VmNicVO_.l3NetworkUuid, l3Uuid).eq(VmNicVO_.metaData, GUEST_NIC_MASK).findValue();
	        if (vrUuid != null) {
	            return vrUuid;
            }
        }

        return null;
    }

    private String getDedicatedRoleVrUuidFromVrUuids(List<String> uuids, String loadBalancerUuid) {
        Set<String> vrUuids = new HashSet<>(uuids);

        if (vrUuids.size() == 2
                && LoadBalancerSystemTags.SEPARATE_VR.hasTag(loadBalancerUuid)
                && vrUuids.stream().anyMatch(uuid -> VirtualRouterSystemTags.DEDICATED_ROLE_VR.hasTag(uuid))) {
            for (String uuid : vrUuids) {
                if (VirtualRouterSystemTags.DEDICATED_ROLE_VR.hasTag(uuid)) {
                    return uuid;
                }
            }
        }

        if (vrUuids.size() == 1) {
            return vrUuids.iterator().next();
        } else if (vrUuids.size() == 0) {
            return null;
        } else {
            throw new CloudRuntimeException(String.format("there are multiple virtual routers[uuids:%s]", vrUuids));
        }
    }

    @Override
    public List<VmNicInventory> getCandidateVmNicsForLoadBalancerInVirtualRouter(APIGetCandidateVmNicsForLoadBalancerMsg msg, List<VmNicInventory> candidates) {
        if(candidates == null || candidates.isEmpty()){
            return candidates;
        }

        List<String> vrUuids = Q.New(VirtualRouterLoadBalancerRefVO.class)
                .select(VirtualRouterLoadBalancerRefVO_.virtualRouterVmUuid)
                .eq(VirtualRouterLoadBalancerRefVO_.loadBalancerUuid, msg.getLoadBalancerUuid())
                .listValues();

        String vrUuid = getDedicatedRoleVrUuidFromVrUuids(vrUuids, msg.getLoadBalancerUuid());

        if (vrUuid != null) {
            return getCandidateVmNicsIfLoadBalancerBound(msg, candidates, vrUuid);
        }

        final List<String> peerL3NetworkUuids = SQL.New("select peer.l3NetworkUuid " +
                "from LoadBalancerVO lb, VipVO vip, VipPeerL3NetworkRefVO peer " +
                "where lb.vipUuid = vip.uuid " +
                "and vip.uuid = peer.vipUuid " +
                "and lb.uuid = :lbUuid")
                .param("lbUuid", msg.getLoadBalancerUuid())
                .list();

        if (peerL3NetworkUuids != null && !peerL3NetworkUuids.isEmpty()) {
            vrUuids = Q.New(VmNicVO.class).select(VmNicVO_.vmInstanceUuid)
                    .in(VmNicVO_.l3NetworkUuid, peerL3NetworkUuids)
                    .eq(VmNicVO_.metaData, VirtualRouterNicMetaData.GUEST_NIC_MASK)
                    .listValues();

            vrUuid = getDedicatedRoleVrUuidFromVrUuids(vrUuids, msg.getLoadBalancerUuid());

            if (vrUuid == null) {
                return getCandidateVmNicsIfPeerL3NetworkExists(msg, candidates.stream()
                        .filter(n -> peerL3NetworkUuids.contains(n.getL3NetworkUuid()))
                        .collect(Collectors.toList()), peerL3NetworkUuids);
            }

            return getCandidateVmNicsIfLoadBalancerBound(msg, candidates, vrUuid);
        }

        VipVO lbVipVO = SQL.New("select vip from LoadBalancerVO lb, VipVO vip " +
                "where lb.vipUuid = vip.uuid " +
                "and lb.uuid = :lbUuid")
                .param("lbUuid", msg.getLoadBalancerUuid()).find();

        if (lbVipVO.getUseFor() != null && lbVipVO.getUseFor().contains(SNAT_NETWORK_SERVICE_TYPE)) {
            vrUuid = Q.New(VirtualRouterVipVO.class).select(VirtualRouterVipVO_.virtualRouterVmUuid)
                    .eq(VirtualRouterVipVO_.uuid, lbVipVO.getUuid()).findValue();
            return getCandidateVmNicsIfLoadBalancerBound(msg, candidates, vrUuid);
        }

        if (vrUuid != null) {
            return getCandidateVmNicsIfLoadBalancerBound(msg, candidates, vrUuid);
        }

        return new SQLBatchWithReturn<List<VmNicInventory>>(){

            @Override
            protected List<VmNicInventory> scripts() {

                //1.get the vm nics which are managed by vrouter or virtual router.
                List<String>  inners = sql("select l3.uuid from L3NetworkVO l3, NetworkServiceL3NetworkRefVO ref, NetworkServiceProviderVO pro" +
                        " where l3.uuid = ref.l3NetworkUuid and ref.networkServiceProviderUuid = pro.uuid and l3.uuid in (:l3Uuids)" +
                        " and pro.type in (:providerType)", String.class)
                        .param("l3Uuids", candidates.stream().map(VmNicInventory::getL3NetworkUuid).collect(Collectors.toList()))
                        .param("providerType", Arrays.asList(VyosConstants.PROVIDER_TYPE.toString(),VirtualRouterConstant.PROVIDER_TYPE.toString()))
                        .list();

                List<VmNicInventory> ret = candidates.stream().filter(nic -> inners.contains(nic.getL3NetworkUuid())).collect(Collectors.toList());
                if(ret.size() == 0){
                    return new ArrayList<VmNicInventory>();
                }

                //2.check the l3 of vm nic is peer l3 of the loadbalancer
                List<Tuple> tuples = sql("select vm.managementNetworkUuid, vm.defaultRouteL3NetworkUuid from VipVO vip, ApplianceVmVO vm" +
                        " where vip.uuid = (select vipUuid from LoadBalancerVO where uuid = :lbUuid)" +
                        " and vm.defaultRouteL3NetworkUuid = vip.l3NetworkUuid", Tuple.class)
                        .param("lbUuid",msg.getLoadBalancerUuid()).list();
                if(tuples.size() == 0){
                    return new ArrayList<VmNicInventory>();
                }

                List<String> publics = new ArrayList<>();
                List<String> managements = new ArrayList<>();
                for(Tuple tuple: tuples){
                    publics.add((String) tuple.get(1));
                    managements.add((String) tuple.get(0));
                }

                List<String> peerL3Uuids = sql("select l3NetworkUuid from VmNicVO"  +
                        " where vmInstanceUuid in (select uuid from ApplianceVmVO where defaultRouteL3NetworkUuid in (:publics))" +
                        " and l3NetworkUuid not in (:publics)" +
                        " and l3NetworkUuid not in (:managements)")
                        .param("publics",publics)
                        .param("managements",managements).list();


                return ret.stream().filter(nic -> peerL3Uuids.contains(nic.getL3NetworkUuid())).collect(Collectors.toList());

            }
        }.execute();
    }

    private List<VmNicInventory> getCandidateVmNicsIfPeerL3NetworkExists(APIGetCandidateVmNicsForLoadBalancerMsg msg, List<VmNicInventory> candidates, List<String> peerL3NetworkUuids) {
	    return candidates.stream()
                .filter(n -> peerL3NetworkUuids.contains(n.getL3NetworkUuid()))
                .collect(Collectors.toList());
    }

    private List<VmNicInventory> getCandidateVmNicsIfLoadBalancerBound(APIGetCandidateVmNicsForLoadBalancerMsg msg, List<VmNicInventory> candidates, String vrUuid) {
        List<String> candidatesUuids = candidates.stream().map(n -> n.getUuid()).collect(Collectors.toList());
        logger.debug(String.format("loadbalancer[uuid:%s] has bound to virtual router[uuid:%s], " +
                        "continue working with vmnics:%s", msg.getLoadBalancerUuid(), vrUuid, candidatesUuids));

        return new SQLBatchWithReturn<List<VmNicInventory>>(){

            @Override
            protected List<VmNicInventory> scripts() {
                List<String> guestL3Uuids = Q.New(VmNicVO.class)
                        .select(VmNicVO_.l3NetworkUuid)
                        .eq(VmNicVO_.vmInstanceUuid, vrUuid)
                        .eq(VmNicVO_.metaData, VirtualRouterNicMetaData.GUEST_NIC_MASK)
                        .listValues();

                if (guestL3Uuids == null || guestL3Uuids.isEmpty()) {
                    return new ArrayList<>();
                }

                List<String> vmNicUuids = SQL.New("select nic.uuid from VmNicVO nic, VmInstanceEO vm " +
                        "where vm.uuid = nic.vmInstanceUuid " +
                        "and vm.type = :vmType " +
                        "and vm.state in (:vmState) " +
                        "and nic.l3NetworkUuid in (:l3s) " +
                        "and nic.metaData is NULL")
                        .param("vmType", VmInstanceConstant.USER_VM_TYPE.toString())
                        .param("vmState", asList(VmInstanceState.Running, VmInstanceState.Stopped))
                        .param("l3s", guestL3Uuids)
                        .list();

                List<String> attachedVmNicUuids = Q.New(LoadBalancerListenerVmNicRefVO.class)
                        .select(LoadBalancerListenerVmNicRefVO_.vmNicUuid)
                        .eq(LoadBalancerListenerVmNicRefVO_.listenerUuid, msg.getListenerUuid())
                        .listValues();

                return candidates.stream()
                        .filter( nic -> vmNicUuids.contains(nic.getUuid()))
                        .filter( nic -> !attachedVmNicUuids.contains(nic.getUuid()))
                        .collect(Collectors.toList());
            }
        }.execute();
    }

    private void applianceVmsCascadeDeleteAdditionPubclicNic(List<VmNicInventory> toDeleteNics) {
        ErrorCodeList errList = new ErrorCodeList();
        FutureCompletion completion = new FutureCompletion(null);
        new While<>(toDeleteNics).each((VmNicInventory nic, WhileCompletion completion1) -> {
            if (!dbf.isExist(nic.getUuid(), VmNicVO.class)) {
                logger.debug(String.format("nic[uuid:%s] not exists, skip", nic.getUuid()));
                completion1.done();
                return;
            }
            DetachNicFromVmMsg msg = new DetachNicFromVmMsg();
            msg.setVmNicUuid(nic.getUuid());
            msg.setVmInstanceUuid(nic.getVmInstanceUuid());
            bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, nic.getVmInstanceUuid());
            bus.send(msg, new CloudBusCallBack(null) {
                @Override
                public void run(MessageReply reply) {
                    if (!reply.isSuccess()) {
                        if (!dbf.isExist(nic.getUuid(), VmNicVO.class)) {
                            logger.info(String.format("nic[uuid:%s] not exists, mark it as success", nic.getUuid()));
                            completion1.done();
                        } else {
                            errList.getCauses().add(reply.getError());
                            logger.error(String.format("detach nic[uuid: %s] for " +
                                    "delete l3[uuid: %s] failed", nic.getUuid(), nic.getL3NetworkUuid()));
                            completion1.allDone();
                        }
                    } else {
                        logger.debug(String.format("detach nic[uuid: %s] for " +
                                "delete l3[uuid: %s] success", nic.getUuid(), nic.getL3NetworkUuid()));
                        completion1.done();
                    }
                }
            });
        }).run(new NoErrorCompletion() {
            @Override
            public void done() {
                if (!errList.getCauses().isEmpty()) {
                    completion.fail(errList.getCauses().get(0));
                } else {
                    logger.info(String.format("detach nics[%s] for delete l3[uuid:%s] success",
                            toDeleteNics.stream()
                                    .map(n -> n.getUuid())
                                    .collect(Collectors.toList()),
                            toDeleteNics.stream().map(n-> n.getL3NetworkUuid()).collect(Collectors.toSet())));
                    completion.success();
                }
            }
        });

        completion.await(TimeUnit.MINUTES.toMillis(30));
        if (!completion.isSuccess()) {
            throw new OperationFailureException(operr("can not detach nic [uuid:%s]", toDeleteNics.stream()
                    .map(n -> n.getUuid())
                    .collect(Collectors.toList())).causedBy(completion.getErrorCode()));
        }
    }

    private List<ApplianceVmVO> applianceVmsToBeDeleted(List<ApplianceVmVO> applianceVmVOS, List<String> deletedUuids) {
        List<ApplianceVmVO> vos = new ArrayList<>();
        for (ApplianceVmVO vo : applianceVmVOS) {
            VirtualRouterVmInventory vrInv = VirtualRouterVmInventory.valueOf(dbf.findByUuid(vo.getUuid(), VirtualRouterVmVO.class));

            List<String> l3Uuids = new ArrayList<>();
            l3Uuids.addAll(vrInv.getGuestL3Networks());
            l3Uuids.add(vrInv.getPublicNetworkUuid());
            l3Uuids.add(vrInv.getManagementNetworkUuid());
            for(String uuid: l3Uuids) {
                if (deletedUuids.contains(uuid)) {
                    vos.add(vo);
                    break;
                }
            }
        }

        return vos;
    }

    List<VmNicInventory> applianceVmsAdditionalPublicNic(List<ApplianceVmVO> applianceVmVOS, List<String> parentIssuerUuids) {
        List<VmNicInventory> toDeleteNics = new ArrayList<>();
        for (ApplianceVmVO vo : applianceVmVOS) {
            VirtualRouterVmInventory vrInv = VirtualRouterVmInventory.valueOf(dbf.findByUuid(vo.getUuid(), VirtualRouterVmVO.class));
            for (VmNicInventory nic : vrInv.getAdditionalPublicNics()) {
                if (parentIssuerUuids.contains(nic.getL3NetworkUuid())) {
                    toDeleteNics.add(nic);
                }
            }
        }

        return toDeleteNics;
    }

    @Override
    public List<ApplianceVmVO> filterApplianceVmCascade(List<ApplianceVmVO> applianceVmVOS, String parentIssuer, List<String> parentIssuerUuids) {

        if (parentIssuer.equals(L3NetworkVO.class.getSimpleName())) {
            List<ApplianceVmVO> vos = applianceVmsToBeDeleted(applianceVmVOS, parentIssuerUuids);

            applianceVmVOS.removeAll(vos);
            List<VmNicInventory> toDeleteNics = applianceVmsAdditionalPublicNic(applianceVmVOS, parentIssuerUuids);
            applianceVmsCascadeDeleteAdditionPubclicNic(toDeleteNics);

            return vos;
        } else if (parentIssuer.equals(IpRangeVO.class.getSimpleName())) {
            final List<String> iprL3Uuids = CollectionUtils.transformToList((List<String>) parentIssuerUuids, new Function<String, String>() {
                @Override
                public String call(String arg) {
                    return Q.New(IpRangeVO.class).eq(IpRangeVO_.uuid, arg).select(IpRangeVO_.l3NetworkUuid).findValue();
                }
            });
            List<ApplianceVmVO> vos = applianceVmsToBeDeleted(applianceVmVOS, iprL3Uuids);

            applianceVmVOS.removeAll(vos);
            List<VmNicInventory> toDeleteNics = applianceVmsAdditionalPublicNic(applianceVmVOS, iprL3Uuids);
            applianceVmsCascadeDeleteAdditionPubclicNic(toDeleteNics);

            return vos;
        } else {
            return applianceVmVOS;
        }
    }

    private void handle(CheckVirtualRouterVmVersionMsg cmsg) {
        CheckVirtualRouterVmVersionReply reply = new CheckVirtualRouterVmVersionReply();

        /* reply message back asap to avoid blocking mn node startup */
        bus.reply(cmsg, reply);

        VirtualRouterVmVO vrVo = dbf.findByUuid(cmsg.getVirtualRouterVmUuid(), VirtualRouterVmVO.class);
        VirtualRouterVmInventory inv = VirtualRouterVmInventory.valueOf(vrVo);
        if (VirtualRouterConstant.VIRTUAL_ROUTER_VM_TYPE.equals(inv.getApplianceVmType())) {
            return;
        }

        vyosVersionManager.vyosRouterVersionCheck(inv.getUuid(), new Completion(cmsg) {
            @Override
            public void success() {
                logger.debug(String.format("virtual router[uuid: %s] has same version as management node", inv.getUuid()));
            }

            @Override
            public void fail(ErrorCode errorCode) {
                logger.warn(String.format("virtual router[uuid: %s] need to be reconnected because %s", inv.getUuid(), errorCode.getDetails()));
                ReconnectVirtualRouterVmMsg msg = new ReconnectVirtualRouterVmMsg();
                msg.setVirtualRouterVmUuid(inv.getUuid());
                bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, inv.getUuid());
                bus.send(msg, new CloudBusCallBack(msg) {
                    @Override
                    public void run(MessageReply reply) {
                        if (!reply.isSuccess()) {
                            logger.warn(String.format("virtual router[uuid:%s] reconnection failed, because %s", inv.getUuid(), reply.getError()));
                        } else {
                            logger.debug(String.format("virtual router[uuid:%s] reconnect successfully", inv.getUuid()));
                        }
                    }
                });
            }
        });
    }

    @Override
    public void managementNodeReady() {
        List<VirtualRouterVmVO> vrVos = Q.New(VirtualRouterVmVO.class).list();
        for (VirtualRouterVmVO vrVo : vrVos) {
            CheckVirtualRouterVmVersionMsg msg = new CheckVirtualRouterVmVersionMsg();
            msg.setVirtualRouterVmUuid(vrVo.getUuid());
            bus.makeTargetServiceIdByResourceUuid(msg, VirtualRouterConstant.SERVICE_ID, vrVo.getUuid());
            bus.send(msg, new CloudBusCallBack(msg) {
                @Override
                public void run(MessageReply reply) {
                    if (!reply.isSuccess()) {
                        logger.warn(String.format("virtual router[uuid:%s] check version message failed, because %s", vrVo.getUuid(), reply.getError()));
                    } else {
                        logger.debug(String.format("virtual router[uuid:%s] check version message successfully", vrVo.getUuid()));
                    }
                }
            });
        }
    }

    @Override
    public void cleanupVip(String uuid) {
        SQL.New(VirtualRouterVipVO.class).eq(VirtualRouterVipVO_.uuid, uuid).delete();
    }
}
