package org.zstack.heder.storage.volume.backup;

import org.zstack.sdk.ImageInventory;

public class CreateRootVolumeTemplateFromVolumeBackupResult {
    public ImageInventory inventory;
    public void setInventory(ImageInventory inventory) {
        this.inventory = inventory;
    }
    public ImageInventory getInventory() {
        return this.inventory;
    }

}
