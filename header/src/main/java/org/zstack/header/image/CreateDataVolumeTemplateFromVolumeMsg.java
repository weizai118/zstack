package org.zstack.header.image;

import org.zstack.header.identity.SessionInventory;
import org.zstack.header.message.*;
import org.zstack.header.notification.ApiNotification;
import org.zstack.header.other.APIAuditor;
import org.zstack.header.storage.backup.BackupStorageVO;
import org.zstack.header.volume.VolumeVO;

import java.util.Collections;
import java.util.List;


/**
 * Created by camile on 3/8/2018.
 * copy by APICreateDataVolumeTemplateFromVolumeMsg
 */
public class CreateDataVolumeTemplateFromVolumeMsg extends NeedReplyMessage {
    private String name;
    private String description;
    private String volumeUuid;
    private List<String> backupStorageUuids;
    private SessionInventory session;
    private String resourceUuid;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getBackupStorageUuids() {
        return backupStorageUuids;
    }

    public void setBackupStorageUuids(List<String> backupStorageUuids) {
        this.backupStorageUuids = backupStorageUuids;
    }

    public String getVolumeUuid() {
        return volumeUuid;
    }

    public void setVolumeUuid(String volumeUuid) {
        this.volumeUuid = volumeUuid;
    }

    public SessionInventory getSession() {
        return session;
    }

    public void setSession(SessionInventory session) {
        this.session = session;
    }

    public String getResourceUuid() {
        return resourceUuid;
    }

    public void setResourceUuid(String resourceUuid) {
        this.resourceUuid = resourceUuid;
    }
}
