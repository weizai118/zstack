package org.zstack.header.storage.snapshot;

import java.io.Serializable;

/**
 * Create by weiwang at 2018/6/11
 */
public class TakeSnapshotsOnKvmJobStruct implements Serializable {
    private String volumeUuid;
    private Integer deviceId;
    private String installPath;
    private String vmInstanceUuid;
    private String previousInstallPath;
    private String newVolumeInstallPath;
    private String snapshotUuid;
    private boolean live;
    private boolean full = false;

    public String getVolumeUuid() {
        return volumeUuid;
    }

    public void setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
    }

    public Integer getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Integer deviceId) {
        this.deviceId = deviceId;
    }

    public String getInstallPath() {
        return installPath;
    }

    public void setInstallPath(String installPath) {
        this.installPath = installPath;
    }

    public String getVmInstanceUuid() {
        return vmInstanceUuid;
    }

    public void setVmInstanceUuid(String vmInstanceUuid) {
        this.vmInstanceUuid = vmInstanceUuid;
    }

    public String getPreviousInstallPath() {
        return previousInstallPath;
    }

    public void setPreviousInstallPath(String previousInstallPath) {
        this.previousInstallPath = previousInstallPath;
    }

    public String getNewVolumeInstallPath() {
        return newVolumeInstallPath;
    }

    public void setNewVolumeInstallPath(String newVolumeInstallPath) {
        this.newVolumeInstallPath = newVolumeInstallPath;
    }

    public String getSnapshotUuid() {
        return snapshotUuid;
    }

    public void setSnapshotUuid(String snapshotUuid) {
        this.snapshotUuid = snapshotUuid;
    }

    public boolean isLive() {
        return live;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public boolean isFull() {
        return full;
    }

    public void setFull(boolean full) {
        this.full = full;
    }
}
