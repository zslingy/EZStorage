package com.zerofall.ezstorage.network.client;

import java.time.LocalDateTime;
import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.zerofall.ezstorage.container.ContainerStorageCore;
import com.zerofall.ezstorage.network.server.MsgStorage;

import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;

public class HandlerMsgStorage implements IMessageHandler<MsgStorage, IMessage> {

    // Reassembly of a snapshot that did not fit into one packet. Only one gui can be open at a time, so a single
    // slot of scratch space is enough; a new part 0 restarts it.
    private static String assemblingId;
    private static NBTTagCompound assemblingMeta;
    private static NBTTagList assemblingEntries = new NBTTagList();
    private static int[] assemblingSlots = new int[64];
    private static int assemblingSlotCount;

    @Override
    public IMessage onMessage(MsgStorage message, MessageContext ctx) {
        // Reading the inventory replaces its backing list, so it has to happen on the client thread instead of the
        // netty thread this handler is called from.
        Minecraft.getMinecraft()
            .func_152344_a(() -> apply(message));
        return null;
    }

    private static void apply(MsgStorage message) {
        EntityClientPlayerMP player = FMLClientHandler.instance()
            .getClientPlayerEntity();
        if (player == null) {
            return;
        }
        if (!(player.openContainer instanceof ContainerStorageCore container) || container.inventory == null) {
            reset();
            return;
        }
        if (message.parts > 1) {
            applyPart(message, container);
            return;
        }
        if (message.getInventory(container.inventory)) {
            container.inventoryUpdateTimestamp = LocalDateTime.now();
        }
    }

    private static void applyPart(MsgStorage message, ContainerStorageCore container) {
        if (message.part == 0) {
            reset();
            assemblingId = message.storageId;
            assemblingMeta = message.inventoryNbtTag;
        }
        if (assemblingMeta == null || message.storageId == null || !message.storageId.equals(assemblingId)) {
            return;
        }
        NBTTagList entries = message.inventoryNbtTag.getTagList("Internal", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            assemblingEntries.appendTag(entries.getCompoundTagAt(i));
        }
        int[] slots = message.inventoryNbtTag.getIntArray("InternalSlots");
        if (assemblingSlotCount + slots.length > assemblingSlots.length) {
            assemblingSlots = Arrays.copyOf(assemblingSlots, Math.max(assemblingSlots.length * 2, assemblingSlotCount + slots.length));
        }
        System.arraycopy(slots, 0, assemblingSlots, assemblingSlotCount, slots.length);
        assemblingSlotCount += slots.length;

        if (message.part != message.parts - 1) {
            return;
        }
        NBTTagCompound merged = new NBTTagCompound();
        merged.setTag("Internal", assemblingEntries);
        merged.setIntArray("InternalSlots", Arrays.copyOf(assemblingSlots, assemblingSlotCount));
        merged.setInteger("NextSlotId", assemblingMeta.getInteger("NextSlotId"));
        merged.setLong("InternalMax", assemblingMeta.getLong("InternalMax"));
        merged.setBoolean("isDisabled", assemblingMeta.getBoolean("isDisabled"));
        container.inventory.readFromPacketNBT(merged);
        container.inventory.id = assemblingId;
        container.inventoryUpdateTimestamp = LocalDateTime.now();
        reset();
    }

    private static void reset() {
        assemblingId = null;
        assemblingMeta = null;
        assemblingEntries = new NBTTagList();
        assemblingSlotCount = 0;
    }
}
