package org.zstack.compute.vm;

import org.zstack.header.tag.TagDefinition;
import org.zstack.header.vm.VmInstanceVO;
import org.zstack.tag.PatternedSystemTag;

/**
 */
@TagDefinition
public class VmSystemTags {
    public static String HOSTNAME_TOKEN = "hostname";
    public static PatternedSystemTag HOSTNAME = new PatternedSystemTag(String.format("hostname::{%s}", HOSTNAME_TOKEN), VmInstanceVO.class);

    public static String STATIC_IP_L3_UUID_TOKEN = "l3NetworkUuid";
    public static String STATIC_IP_TOKEN = "staticIp";
    public static PatternedSystemTag STATIC_IP = new PatternedSystemTag(String.format("staticIp::{%s}::{%s}", STATIC_IP_L3_UUID_TOKEN, STATIC_IP_TOKEN), VmInstanceVO.class);

    public static String MAC_TOKEN = "customMac";
    public static PatternedSystemTag CUSTOM_MAC = new PatternedSystemTag(String.format("customMac::{%s}::{%s}", STATIC_IP_L3_UUID_TOKEN, MAC_TOKEN), VmInstanceVO.class);

    public static PatternedSystemTag WINDOWS_VOLUME_ON_VIRTIO = new PatternedSystemTag("windows::virtioVolume", VmInstanceVO.class);

    public static String USERDATA_TOKEN = "userdata";
    public static PatternedSystemTag USERDATA = new PatternedSystemTag(String.format("userdata::{%s}", USERDATA_TOKEN), VmInstanceVO.class);

    public static String SSHKEY_TOKEN = "sshkey";
    public static PatternedSystemTag SSHKEY = new PatternedSystemTag(String.format("sshkey::{%s}", SSHKEY_TOKEN), VmInstanceVO.class);

    public static String ROOT_PASSWORD_TOKEN = "rootPassword";
    public static PatternedSystemTag ROOT_PASSWORD = new PatternedSystemTag(String.format("rootPassword::{%s}", ROOT_PASSWORD_TOKEN), VmInstanceVO.class);

    public static String ISO_DEVICEID_TOKEN = "deviceId";
    public static String ISO_TOKEN = "iso";
    public static PatternedSystemTag ISO = new PatternedSystemTag(String.format("iso::{%s}::{%s}", ISO_TOKEN, ISO_DEVICEID_TOKEN), VmInstanceVO.class);

    public static String BOOT_ORDER_TOKEN = "bootOrder";
    public static PatternedSystemTag BOOT_ORDER = new PatternedSystemTag(String.format("bootOrder::{%s}", BOOT_ORDER_TOKEN), VmInstanceVO.class);

    // set cdromBootOnce::true to set vm boot from cdrom once only
    public static String CDROM_BOOT_ONCE_TOKEN = "cdromBootOnce";
    public static PatternedSystemTag CDROM_BOOT_ONCE = new PatternedSystemTag(String.format("cdromBootOnce::{%s}", CDROM_BOOT_ONCE_TOKEN), VmInstanceVO.class);

    public static String CONSOLE_PASSWORD_TOKEN = "consolePassword";
    public static PatternedSystemTag CONSOLE_PASSWORD = new PatternedSystemTag(String.format("consolePassword::{%s}",CONSOLE_PASSWORD_TOKEN),VmInstanceVO.class);

    // set usbRedirect::true to enable usb redirect
    public static String USB_REDIRECT_TOKEN = "usbRedirect";
    public static PatternedSystemTag USB_REDIRECT = new PatternedSystemTag(String.format("usbRedirect::{%s}",USB_REDIRECT_TOKEN),VmInstanceVO.class);

    // set rdpEnable::true to enable RDP tag
    public static String RDP_ENABLE_TOKEN = "RDPEnable";
    public static PatternedSystemTag RDP_ENABLE = new PatternedSystemTag(String.format("RDPEnable::{%s}",RDP_ENABLE_TOKEN),VmInstanceVO.class);

    // set VDIMonitorNumber::Integer to set how many monitor will be support for VDI
    public static String VDI_MONITOR_NUMBER_TOKEN = "VDIMonitorNumber";
    public static PatternedSystemTag VDI_MONITOR_NUMBER = new PatternedSystemTag(String.format("VDIMonitorNumber::{%s}",VDI_MONITOR_NUMBER_TOKEN),VmInstanceVO.class);

    public static String INSTANCEOFFERING_ONLINECHANGE_TOKEN = "instanceOfferingOnliechange";
    public static PatternedSystemTag INSTANCEOFFERING_ONLIECHANGE = new PatternedSystemTag(String.format("instanceOfferingOnlinechange::{%s}",INSTANCEOFFERING_ONLINECHANGE_TOKEN),VmInstanceVO.class);

    public static String PENDING_CAPACITY_CHNAGE_CPU_NUM_TOKEN = "cpuNum";
    public static String PENDING_CAPACITY_CHNAGE_CPU_SPEED_TOKEN = "cpuSpeed";
    public static String PENDING_CAPACITY_CHNAGE_MEMORY_TOKEN = "memory";
    public static PatternedSystemTag PENDING_CAPACITY_CHANGE = new PatternedSystemTag(
            String.format("pendingCapacityChange::cpuNum::{%s}::cpuSpeed::{%s}::memory::{%s}",  PENDING_CAPACITY_CHNAGE_CPU_NUM_TOKEN, PENDING_CAPACITY_CHNAGE_CPU_SPEED_TOKEN, PENDING_CAPACITY_CHNAGE_MEMORY_TOKEN),
            VmInstanceVO.class
    );
    public static String VM_INJECT_QEMUGA_TOKEN = "qemuga";
    public static PatternedSystemTag VM_INJECT_QEMUGA = new PatternedSystemTag(String.format("%s", VM_INJECT_QEMUGA_TOKEN), VmInstanceVO.class);

    public static String PRIMARY_STORAGE_UUID_FOR_DATA_VOLUME_TOKEN = "primaryStorageUuidForDataVolume";
    public static PatternedSystemTag PRIMARY_STORAGE_UUID_FOR_DATA_VOLUME = new PatternedSystemTag(String.format("primaryStorageUuidForDataVolume::{%s}", PRIMARY_STORAGE_UUID_FOR_DATA_VOLUME_TOKEN), VmInstanceVO.class);

    public static final String VM_SYSTEM_SERIAL_NUMBER_TOKEN = "vmSystemSerialNumber";
    public static PatternedSystemTag VM_SYSTEM_SERIAL_NUMBER = new PatternedSystemTag(String.format("vmSystemSerialNumber::{%s}", VM_SYSTEM_SERIAL_NUMBER_TOKEN), VmInstanceVO.class);

    public static String RELEASE_NIC_AFTER_DETACH_NIC_TOKEN = "releaseVmNicAfterDetachVmNic";
    public static PatternedSystemTag RELEASE_NIC_AFTER_DETACH_NIC = new PatternedSystemTag(String.format("releaseVmNicAfterDetachVmNic::{%s}", RELEASE_NIC_AFTER_DETACH_NIC_TOKEN), VmInstanceVO.class);

}
