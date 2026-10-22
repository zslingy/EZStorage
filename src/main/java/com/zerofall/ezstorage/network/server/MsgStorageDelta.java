package com.zerofall.ezstorage.network.server;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.zerofall.ezstorage.util.EZInventory;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * Incremental update for one storage: only the slots whose count changed since the previous tick, addressed by slot
 * id. Newly created slots carry their content, existing ones only their new absolute count - so a pipe feeding a
 * chest no longer re-sends (and the client no longer re-parses) the whole inventory per item.
 */
public class MsgStorageDelta implements IMessage {

    public String storageId;
    public List<EZInventory.SyncEntry> entries = new ArrayList<EZInventory.SyncEntry>();

    public MsgStorageDelta() {}

    public MsgStorageDelta(String storageId, List<EZInventory.SyncEntry> entries) {
        this.storageId = storageId;
        this.entries = entries;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        storageId = ByteBufUtils.readUTF8String(buf);
        int count = buf.readInt();
        entries = new ArrayList<EZInventory.SyncEntry>(count);
        for (int i = 0; i < count; i++) {
            int slotId = buf.readInt();
            long amount = buf.readLong();
            ItemStack stack = null;
            if (buf.readBoolean()) {
                NBTTagCompound tag = ByteBufUtils.readTag(buf);
                if (tag != null) {
                    stack = ItemStack.loadItemStackFromNBT(tag);
                }
            }
            entries.add(EZInventory.syncEntry(slotId, amount, stack));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, storageId == null ? "" : storageId);
        buf.writeInt(entries.size());
        for (EZInventory.SyncEntry entry : entries) {
            buf.writeInt(entry.slotId);
            buf.writeLong(entry.count);
            if (entry.stack != null) {
                buf.writeBoolean(true);
                NBTTagCompound tag = new NBTTagCompound();
                entry.stack.writeToNBT(tag);
                ByteBufUtils.writeTag(buf, tag);
            } else {
                buf.writeBoolean(false);
            }
        }
    }

    /** The stored NBT form, kept for debugging only. */
    public NBTTagCompound toTag() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("id", storageId);
        NBTTagList list = new NBTTagList();
        for (EZInventory.SyncEntry entry : entries) {
            NBTTagCompound entryTag = new NBTTagCompound();
            entryTag.setInteger("slot", entry.slotId);
            entryTag.setLong("count", entry.count);
            if (entry.stack != null) {
                NBTTagCompound stackTag = new NBTTagCompound();
                entry.stack.writeToNBT(stackTag);
                entryTag.setTag("stack", stackTag);
            }
            list.appendTag(entryTag);
        }
        tag.setTag("entries", list);
        return tag;
    }
}
