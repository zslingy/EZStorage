package com.zerofall.ezstorage.network.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.zerofall.ezstorage.EZStorage;
import com.zerofall.ezstorage.util.EZInventory;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/**
 * The complete content of one storage, in one or more parts.
 * <p>
 * A single packet only travels when the gzipped payload fits into a vanilla 1.7.10 custom payload (a short length, so
 * ~32k); anything larger - a big storage can hold tens of thousands of types - is split into parts that the client
 * reassembles. Delivering the whole thing at once is exactly the old behaviour that made the mod stutter, so this is
 * only ever sent when the gui is opened or after a re-sort; per-change updates use {@link MsgStorageDelta}.
 */
public class MsgStorage implements IMessage {

    /** Vanilla 1.7.10 writes the payload length as a short; stay well below it. */
    public static final int SIZE_LIMIT = 30000;
    /** Entries probed per part before a part is measured; a part is halved until it fits. */
    private static final int CHUNK = 512;

    public String storageId;
    public NBTTagCompound inventoryNbtTag;
    public int part;
    public int parts = 1;

    public MsgStorage() {}

    public MsgStorage(String storageId, NBTTagCompound inventoryNbtTag) {
        this.storageId = storageId;
        this.inventoryNbtTag = inventoryNbtTag;
    }

    public MsgStorage(EZInventory inventory) {
        this(inventory == null ? null : inventory.id, inventory);
    }

    public MsgStorage(String storageId, EZInventory inventory) {
        this.storageId = storageId;
        inventoryNbtTag = new NBTTagCompound();
        if (inventory != null) {
            // Packet flavour: the on-disk format stays untouched, but the client also learns every slot id so the
            // deltas that follow can address slots instead of shipping the whole inventory again.
            inventory.writeToPacketNBT(inventoryNbtTag);
        }
    }

    /**
     * Builds every part of the snapshot for {@code inventory}, each small enough to survive the vanilla packet limit.
     */
    public static List<MsgStorage> createAll(EZInventory inventory) {
        List<MsgStorage> result = new ArrayList<MsgStorage>();
        String id = inventory == null ? null : inventory.id;
        if (inventory == null) {
            result.add(new MsgStorage(id, new NBTTagCompound()));
            return result;
        }
        NBTTagCompound full = new NBTTagCompound();
        inventory.writeToPacketNBT(full);
        NBTTagList list = full.getTagList("Internal", 10);
        int[] slots = full.getIntArray("InternalSlots");
        int total = list.tagCount();
        if (total == 0) {
            result.add(new MsgStorage(id, full));
            return result;
        }
        int from = 0;
        while (from < total) {
            int to = Math.min(total, from + CHUNK);
            NBTTagCompound slice = slice(full, list, slots, from, to);
            // A part can still be too fat (deep item nbt); halve it until it fits or only one entry is left.
            while (to - from > 1 && compressedSize(slice) > SIZE_LIMIT) {
                to = from + Math.max(1, (to - from) / 2);
                slice = slice(full, list, slots, from, to);
            }
            MsgStorage message = new MsgStorage(id, slice);
            result.add(message);
            from = to;
        }
        for (int i = 0; i < result.size(); i++) {
            result.get(i).part = i;
            result.get(i).parts = result.size();
        }
        return result;
    }

    /** Sends the complete inventory to one player, split into as many packets as the size limit requires. */
    public static void sendAll(EZInventory inventory, EntityPlayerMP player) {
        if (player == null) {
            return;
        }
        for (MsgStorage message : createAll(inventory)) {
            EZStorage.instance.network.sendTo(message, player);
        }
    }

    private static NBTTagCompound slice(NBTTagCompound full, NBTTagList list, int[] slots, int from, int to) {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList sub = new NBTTagList();
        for (int i = from; i < to; i++) {
            sub.appendTag(list.getCompoundTagAt(i));
        }
        tag.setTag("Internal", sub);
        int slotFrom = Math.min(from, slots.length);
        int slotTo = Math.min(to, slots.length);
        tag.setIntArray("InternalSlots", Arrays.copyOfRange(slots, slotFrom, slotTo));
        tag.setInteger("NextSlotId", full.getInteger("NextSlotId"));
        tag.setLong("InternalMax", full.getLong("InternalMax"));
        tag.setBoolean("isDisabled", full.getBoolean("isDisabled"));
        return tag;
    }

    private static int compressedSize(NBTTagCompound tag) {
        try {
            return CompressedStreamTools.compress(tag).length;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    public EZInventory getInventory() {
        EZInventory inventory = new EZInventory();
        if (getInventory(inventory)) {
            return inventory;
        }
        return null;
    }

    public boolean getInventory(EZInventory inventory) {
        if (inventory == null || inventoryNbtTag == null || inventoryNbtTag.hasNoTags()) {
            return false;
        }
        inventory.readFromPacketNBT(inventoryNbtTag);
        // The deltas that follow are addressed to this storage, so the client side copy has to carry its id too.
        inventory.id = this.storageId;
        return true;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        storageId = ByteBufUtils.readUTF8String(buf);
        part = buf.readInt();
        parts = buf.readInt();
        int length = buf.readInt();
        if (length > 0) {
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            try {
                inventoryNbtTag = CompressedStreamTools.func_152457_a(bytes, net.minecraft.nbt.NBTSizeTracker.field_152451_a);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, storageId == null ? "" : storageId);
        buf.writeInt(part);
        buf.writeInt(parts);
        try {
            if (inventoryNbtTag != null) {
                byte[] bytes = CompressedStreamTools.compress(inventoryNbtTag);
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
            } else {
                buf.writeInt(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
